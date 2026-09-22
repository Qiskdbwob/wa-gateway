TASK BRIEF

Build Agent Core on Existing Working WhatsApp Gateway

Tujuan

Project ini sudah memiliki WhatsApp Gateway native berbasis "whatsmeow" yang berhasil dibuild dan telah diuji mengirim pesan WhatsApp ke nomor sendiri.

Tugas kamu sekarang adalah membangun Agent Core di atas gateway yang sudah bekerja tersebut.

WhatsApp Gateway harus diperlakukan sebagai transport layer, bukan sebagai bagian yang perlu ditulis ulang.

Target arsitektur:

WhatsApp
→ WhatsApp Gateway
→ Agent Core
→ Agent Loop
→ Model Provider / Router
→ Tools / Memory / Subagents
→ Response
→ WhatsApp Gateway
→ WhatsApp

Jangan mengganti implementasi WhatsApp Gateway hanya karena menurutmu ada pendekatan yang lebih sederhana.

---

ATURAN WAJIB

1. Jangan menghapus, mengganti, atau me-rewrite WhatsApp Gateway yang sudah bekerja.

2. Sebelum coding, audit project terlebih dahulu:
   
   - struktur project
   - implementasi WhatsApp Gateway
   - public API gateway
   - event/message listener
   - fungsi send message
   - lifecycle connection
   - storage/session
   - dependency
   - build configuration
   - bagaimana Kotlin saat ini berkomunikasi dengan Go gateway

3. Jangan mengasumsikan API library.
   Jika membutuhkan API "whatsmeow", periksa implementasi/dependency yang benar-benar digunakan project.

4. Jangan membuat fake implementation hanya supaya build berhasil.

5. Jangan membuat mock WhatsApp Gateway jika gateway nyata sudah tersedia.

6. Jangan mengubah working feature hanya untuk melakukan refactor yang tidak diperlukan.

7. Jangan mengimplementasikan seluruh roadmap sekaligus.

8. Implementasikan secara bertahap dan pastikan setiap tahap:
   
   - compile
   - build
   - tidak merusak gateway
   - dapat diuji
   - memiliki error handling

9. Jika menemukan API, dependency, atau requirement yang tidak jelas, lakukan audit terhadap project terlebih dahulu. Jangan mengarang.

10. Setelah setiap fase selesai, lakukan verification dan jelaskan:

- file yang berubah
- fungsi yang ditambahkan
- dependency yang ditambahkan
- bagaimana fitur bekerja
- bagaimana cara mengujinya
- masalah yang belum diselesaikan

---

PHASE 0 — AUDIT EXISTING PROJECT

Jangan langsung coding.

Audit project dan temukan:

WhatsApp Gateway

Cari:

- Go gateway
- "WaGatewayManager"
- listener/event interface
- send message API
- receive message event
- connection state
- authentication state
- reconnect logic
- storage/session
- AAR/JNI/gomobile integration
- foreground service jika sudah ada

Buat pemetaan internal:

WhatsApp incoming message
        ↓
Gateway event
        ↓
Kotlin callback/interface
        ↓
??? Agent Core entry point

Dan:

Agent response
        ↓
Agent Core
        ↓
??? WhatsApp send API
        ↓
Gateway
        ↓
WhatsApp

Jangan mengubah gateway pada fase ini.

Jika public API gateway belum cukup untuk Agent Core, tambahkan adapter kecil di atas API yang sudah ada daripada mengganti gateway.

---

PHASE 1 — AGENT CORE MINIMAL

Bangun Agent Core minimal terlebih dahulu.

Tujuan fase ini:

WhatsApp message
→ Agent Core
→ LLM
→ Agent Core
→ WhatsApp response

Agent Core harus memiliki konsep:

Agent
AgentSession
AgentMessage
AgentLoop
ModelProvider
Model
Tool
ToolResult

Minimal Agent:

id
name
systemPrompt
provider
model
enabled

Minimal Session:

sessionId
agentId
channel
conversationId
createdAt
updatedAt

Minimal Message:

id
sessionId
role
content
timestamp

Role minimal:

USER
ASSISTANT
SYSTEM
TOOL

---

PHASE 2 — MODEL PROVIDER ABSTRACTION

Jangan membuat Agent bergantung langsung pada satu provider.

Buat abstraction:

ModelProvider
    ↓
Model
    ↓
ChatRequest
    ↓
ChatResponse

Provider minimal harus memungkinkan OpenAI-compatible API.

Struktur konsep:

Provider
 ├── name
 ├── baseUrl
 ├── apiKey
 └── models

Model
 ├── id
 ├── providerId
 ├── capabilities
 └── configuration

Capabilities minimal:

text
vision
audio
video
toolCalling
reasoning
contextWindow

Jangan mengklaim model memiliki capability tertentu jika belum diverifikasi.

API key jangan ditulis hardcoded di source code.

---

PHASE 3 — AGENT LOOP

Implementasikan Agent Loop sebagai pusat eksekusi.

Alur minimal:

Receive user message
        ↓
Load session
        ↓
Build context
        ↓
Call model
        ↓
Check response
        ↓
If tool call → execute tool
        ↓
Add tool result to context
        ↓
Call model again
        ↓
Final response
        ↓
Persist message
        ↓
Send response

Agent tidak boleh berhenti diam-diam ketika terjadi error.

Gunakan state machine:

IDLE
THINKING
CALLING_TOOL
WAITING_TOOL
DELEGATING
WAITING_SUB_AGENT
REFLECTING
RETRYING
FALLBACK
WAITING_APPROVAL
COMPLETED
FAILED

Untuk fase pertama cukup implementasikan state yang memang dibutuhkan, tetapi arsitekturnya harus memungkinkan state tambahan tanpa rewrite besar.

---

PHASE 4 — ERROR HANDLING + RETRY + FALLBACK

Agent Loop harus mampu menangani:

- network error
- timeout
- rate limit
- provider unavailable
- invalid request
- context overflow
- tool error
- authentication error
- model menghasilkan response kosong
- malformed tool call

Jangan fallback untuk semua error secara membabi buta.

Klasifikasikan error.

Contoh:

TRANSIENT
RATE_LIMIT
TIMEOUT
PROVIDER_UNAVAILABLE
CONTEXT_OVERFLOW
INVALID_REQUEST
AUTH_ERROR
TOOL_ERROR
UNKNOWN

Routing:

Primary Model
      ↓
failure classification
      ↓
retry jika layak
      ↓
fallback jika layak
      ↓
next model
      ↓
final failure

Tambahkan batas retry agar tidak terjadi infinite loop.

---

PHASE 5 — SESSION STORAGE

Conversation WhatsApp jangan hanya disimpan di memory RAM.

Buat persistent session storage.

Minimal:

Session
Message
Agent
Provider
Model

Setiap pesan WhatsApp harus dapat dipetakan ke session.

Contoh:

WhatsApp account
    ↓
phone/contact/chat identifier
    ↓
AgentSession
    ↓
Message history

Pastikan restart aplikasi tidak menghilangkan conversation history.

Jangan membuat satu session global untuk seluruh user/chat jika arsitektur gateway memberikan identifier yang memungkinkan pemisahan conversation.

---

PHASE 6 — TOOL SYSTEM

Setelah basic agent loop bekerja, tambahkan abstraction Tool.

Minimal:

Tool
 ├── id
 ├── name
 ├── description
 ├── inputSchema
 ├── permission
 └── execute()

ToolResult:

success
output
error
metadata

Agent Loop harus dapat:

LLM
 ↓
tool call
 ↓
Tool Registry
 ↓
Tool
 ↓
Tool Result
 ↓
LLM

Buat Tool Registry agar tools dapat ditambahkan tanpa mengubah Agent Loop.

---

PHASE 7 — WORKSPACE ISOLATION

Agent harus memiliki workspace.

Contoh:

/workspaces/{agentId}/

File tools tidak boleh bebas mengakses filesystem.

Buat satu permission/path layer yang memastikan:

requestedPath
      ↓
resolve absolute path
      ↓
verify inside workspace
      ↓
allow / reject

Agent tidak boleh melakukan path traversal seperti:

../
../../

untuk keluar dari workspace.

File tools minimal:

list
read
write
append
move
copy
delete
mkdir

Tetapi semua harus melalui workspace permission layer.

---

PHASE 8 — TERMINAL TOOL

Buat abstraction terminal yang nantinya dapat menjalankan shell command dalam workspace.

Contoh command yang mungkin diperlukan:

pwd
ls
cat
grep
sed
awk
python
node
git
curl
gh

Namun jangan memberikan unrestricted shell access.

Buat permission policy.

Command yang dapat mengubah environment/package harus membutuhkan approval manual, misalnya:

apt install
apk add
pkg install
pip install
npm install
apt upgrade
apk upgrade
pkg upgrade
rm

Jangan hanya melakukan string matching sederhana jika dapat dibuat permission layer yang lebih aman.

Approval minimal mendukung:

APPROVE_ONCE
APPROVE_SESSION
APPROVE_ALWAYS
REJECT

---

PHASE 9 — PERMISSION / APPROVAL SYSTEM

Ini adalah bagian inti Agent Core.

Tool yang berisiko tidak boleh langsung dijalankan tanpa permission.

Contoh:

Agent
 ↓
Tool Call
 ↓
Permission Layer
 ↓
Approved?
 ├── yes → execute
 └── no → WAITING_APPROVAL

Permission harus dapat dikaitkan dengan:

Agent
Tool
Action
Workspace
Session

Jangan membuat approval system yang hanya hardcoded untuk satu command.

Harus menjadi infrastructure yang dapat digunakan oleh:

- terminal
- filesystem
- MCP
- browser
- external network
- git push
- scheduler
- future tools

---

PHASE 10 — SEARCH SYSTEM

Buat unified search abstraction.

Daripada membuat banyak API yang tidak konsisten:

search_tools()
search_memory()
search_learning()
search_session()

buat:

search(query, scope)

Scope:

session
memory
learning
skills
tools
knowledge
all

Search result minimal:

sourceType
sourceId
title
content
score
timestamp
metadata

Contoh:

search(
    query = "apa yang kita bahas kemarin?",
    scope = "session"
)

---

PHASE 11 — MEMORY ARCHITECTURE

Jangan mencampur semua jenis memory.

Gunakan beberapa layer:

Session Memory
Episodic Memory
Semantic / Knowledge Memory
Learning
Procedural Skills

Definisi:

Session Memory

Conversation mentah.

Episodic Memory

Peristiwa penting dari interaction.

Contoh:

User asked to implement feature X.
Agent encountered error Y.
Agent solved it using approach Z.

Knowledge Memory

Informasi yang dianggap persistent.

Learning

Pelajaran yang diperoleh dari pengalaman/error.

Procedural Skills

Instruksi reusable dalam format Markdown.

---

PHASE 12 — LEARNING PIPELINE

Jangan langsung menyimpan setiap kesalahan agent sebagai permanent rule.

Gunakan:

Observation
    ↓
Candidate Learning
    ↓
Validation
    ↓
Promotion
    ↓
Active Learning

Status:

candidate
verified
active
obsolete

Contoh:

Agent gagal menggunakan API X.
        ↓
Catat error.
        ↓
Buat candidate learning.
        ↓
Validasi apakah penyebabnya benar.
        ↓
Jika valid → active learning.

Learning harus dapat digunakan kembali oleh Agent Loop.

---

PHASE 13 — CONTEXT MANAGER

Agent harus memiliki Context Manager.

Context tidak boleh hanya:

system prompt
+
seluruh chat history

Context Manager nantinya menggabungkan:

System Prompt
+
Recent Messages
+
Important Messages
+
Relevant Session History
+
Relevant Memory
+
Relevant Learning
+
Relevant Skills
+
Current Task
+
Tool Results

Jika context terlalu besar:

old context
    ↓
summarize
    ↓
episodic memory
    ↓
compact
    ↓
continue agent loop

Agent tidak boleh berhenti hanya karena context overflow jika masih memungkinkan dilakukan compaction/fallback.

---

PHASE 14 — SUBAGENT INFRASTRUCTURE

Setelah Agent Loop stabil, implementasikan subagent.

Subagent harus menjadi object yang memiliki:

id
name
persona
systemPrompt
provider
model
tools
permissions
workspace
timeout

Main Agent dapat melakukan:

delegate(
    agentId,
    task,
    background = true
)

Result:

TaskResult
 ├── status
 ├── summary
 ├── evidence
 ├── files
 ├── errors
 └── duration

Subagent harus dapat berjalan di background.

Main Agent tidak harus menunggu jika task memang asynchronous.

---

PHASE 15 — COUNCIL MODE

Council bukan infrastructure terpisah.

Council harus menggunakan subagent system.

Contoh:

Main Agent
    ↓
Agent A — researcher
Agent B — critic
Agent C — implementer
Agent D — alternative thinker
    ↓
Main Agent sebagai moderator
    ↓
Final synthesis

Council harus memiliki stopping condition agar agent tidak memanggil subagent tanpa batas.

---

PHASE 16 — MULTIMODAL DELEGATION

Model capability harus menentukan apakah agent dapat memahami:

text
image
audio
video

Jika main model tidak memiliki vision:

User sends image
        ↓
Main Agent
        ↓
Vision-capable Subagent
        ↓
Analysis
        ↓
Main Agent
        ↓
Response

Ini harus menggunakan infrastructure subagent yang sama, bukan sistem khusus yang terpisah.

---

PHASE 17 — OBSERVABILITY

Tambahkan logging terstruktur.

Catat minimal:

timestamp
session
agent
provider
model
event
duration
token usage
tool
tool result
error
retry
fallback
subagent

Event contoh:

USER_MESSAGE
MODEL_REQUEST
MODEL_RESPONSE
TOOL_CALL
TOOL_RESULT
SUBAGENT_START
SUBAGENT_COMPLETE
RETRY
FALLBACK
APPROVAL_REQUEST
ERROR

Jangan menyimpan API key atau secret di log.

---

PHASE 18 — LATENCY / MODEL PROBE

Buat model probe.

Minimal test:

1 + 1 =

Tujuannya bukan mengukur kualitas model.

Yang diukur:

available
connect latency
time to first token jika tersedia
total latency
timestamp
error

Simpan hasil probe untuk routing/diagnostic.

---

PHASE 19 — AGENT DASHBOARD

Setelah core stabil, buat dashboard.

Dashboard harus menunjukkan:

Agent status
Current task
Current model
Provider
Token usage
Latency
Current tool
Subagents
Last error
Activity timeline

Jangan hanya membuat dashboard berupa angka statistik.

Dashboard harus membantu mengetahui apa yang sedang dilakukan Agent.

---

PHASE 20 — TASK / JOB SYSTEM

Buat konsep Task agar background task, scheduler, subagent, dan autonomous loop dapat menggunakan infrastructure yang sama.

Minimal:

Task
 ├── id
 ├── name
 ├── status
 ├── agent
 ├── model
 ├── tools
 ├── prompt
 ├── createdAt
 ├── startedAt
 ├── completedAt
 ├── result
 ├── error
 └── executionLog

Status:

QUEUED
RUNNING
WAITING
COMPLETED
FAILED
CANCELLED

---

PHASE 21 — SCHEDULER

Setelah Task System tersedia, scheduler dapat dibangun di atasnya.

ScheduledTask:

id
name
schedule
prompt
agent
model
tools
enabled
lastRun
nextRun
executionHistory

UI harus memungkinkan:

view
pause
resume
edit
delete
run now

Jangan membuat scheduler terpisah dari Task System.

---

PHASE 22 — MCP CONNECTOR

MCP harus menjadi connector layer.

Konfigurasi minimal:

name
transport
baseUrl
headers
authentication
enabledTools
permissionPolicy

Transport yang direncanakan:

HTTP
HTTPS
SSE

MCP tools harus masuk ke Tool Registry.

Jangan otomatis mempercayai semua MCP tools.

MCP tetap melewati Permission Layer.

---

PHASE 23 — KNOWLEDGE / SKILLS UI

Buat pemisahan yang jelas:

Documents
Skills
Rules
Learnings
Imported Knowledge

Setiap item dapat memiliki:

source
createdAt
updatedAt
lastUsed
status

UI nantinya harus memungkinkan:

view
edit
delete
add
import
export/download

Import knowledge dari GitHub dapat ditambahkan setelah core stabil.

---

PHASE 24 — BROWSER AUTOMATION

Jangan prioritaskan browser automation sebelum Agent Core stabil.

Buat abstraction terlebih dahulu:

BrowserController

navigate()
click()
type()
scroll()
screenshot()
extract()
submit()

Implementasi Gecko/browser engine dapat ditambahkan kemudian.

Browser juga harus melalui Permission Layer untuk external actions.

---

PHASE 25 — WHATSAPP MEDIA

Setelah text messaging stabil, baru tambahkan:

image
video
audio
document

Kemudian hubungkan dengan multimodal subagent.

Jangan mengubah gateway yang sudah bekerja hanya untuk menambahkan fitur ini jika belum diperlukan.

---

PRIORITAS IMPLEMENTASI

Jangan mengerjakan semua phase sekaligus.

Urutan wajib:

0. Audit existing project
        ↓
1. Agent Core
        ↓
2. Model Provider
        ↓
3. Agent Loop
        ↓
4. Retry + Fallback
        ↓
5. Session Storage
        ↓
6. Tool System
        ↓
7. Workspace Isolation
        ↓
8. Terminal
        ↓
9. Permission / Approval
        ↓
10. Search
        ↓
11. Memory
        ↓
12. Learning
        ↓
13. Context Manager
        ↓
14. Subagents
        ↓
15. Council
        ↓
16. Multimodal Delegation
        ↓
17. Observability
        ↓
18. Dashboard
        ↓
19. Task System
        ↓
20. Scheduler
        ↓
21. MCP
        ↓
22. Knowledge UI
        ↓
23. Browser
        ↓
24. WhatsApp Media

---

PHASE PERTAMA YANG HARUS KAMU KERJAKAN SEKARANG

Jangan langsung mengimplementasikan seluruh roadmap.

Mulai dengan PHASE 0 — AUDIT.

Setelah audit, tampilkan:

1. Struktur project saat ini.
2. Lokasi WhatsApp Gateway.
3. Public API gateway.
4. Cara menerima incoming WhatsApp message.
5. Cara mengirim outgoing WhatsApp message.
6. Cara authentication/session bekerja.
7. Bagaimana Kotlin berkomunikasi dengan Go.
8. Existing database/storage.
9. Existing dependency.
10. Existing agent/chat implementation jika ada.
11. Bagian mana yang dapat digunakan kembali.
12. Bagian mana yang perlu dibuat.
13. Risiko teknis yang ditemukan.
14. Rencana implementasi Phase 1.

Jangan mengubah source code pada audit kecuali benar-benar diperlukan untuk inspeksi.

Setelah audit selesai, berhenti dan laporkan hasil audit terlebih dahulu sebelum melakukan perubahan besar.

---

DEFINITION OF DONE — PHASE 1

Setelah Phase 1 selesai, minimal harus dapat:

WhatsApp message
        ↓
existing WhatsApp Gateway
        ↓
Agent Core
        ↓
configured LLM provider
        ↓
Agent response
        ↓
existing WhatsApp Gateway
        ↓
WhatsApp

Dan:

- conversation/session tersimpan
- error tidak membuat Agent berhenti diam-diam
- logging dasar tersedia
- provider tidak hardcoded ke Agent Core
- gateway lama tetap berfungsi
- project dapat build
- fitur existing tidak rusak

---

ATURAN VERIFIKASI

Setiap phase wajib diverifikasi sebelum lanjut.

Gunakan:

AUDIT
→ IMPLEMENT
→ BUILD
→ TEST
→ VERIFY
→ REPORT
→ NEXT PHASE

Jika build gagal:

STOP
→ diagnose
→ fix
→ build ulang
→ verify

Jangan melanjutkan ke fitur berikutnya jika fitur fundamental sebelumnya rusak.

Jangan mengatakan "implemented successfully" hanya karena source code sudah ditulis.

Fitur dianggap selesai hanya jika benar-benar dapat diverifikasi.

---

PRINSIP ARSITEKTUR

Gunakan prinsip:

Transport ≠ Agent Core
Provider ≠ Agent
Agent ≠ Tool
Tool ≠ Permission
Memory ≠ Session
Task ≠ Scheduler
Subagent ≠ Council

Setiap bagian harus memiliki boundary yang jelas sehingga dapat diganti atau dikembangkan tanpa rewrite keseluruhan aplikasi.

WhatsApp Gateway yang sekarang sudah berhasil harus tetap menjadi transport adapter yang dapat diganti dengan transport lain di masa depan.

Target akhirnya adalah sebuah local-first personal agent runtime yang kebetulan saat ini menggunakan WhatsApp sebagai salah satu interface.

Jangan mengorbankan stabilitas gateway yang sudah bekerja demi fitur yang belum diperlukan.