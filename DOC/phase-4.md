PHASE 4 — RETRY, ERROR CLASSIFICATION & MODEL FALLBACK

Context

Phase 1, Phase 2, UI Redesign, dan Phase 3 sudah selesai.

Phase 3 sekarang sudah memiliki Agent Loop:

Message
→ Session
→ Context
→ Model Provider
→ Response
→ Persistence
→ Channel Response

Sekarang tingkatkan reliability Agent Loop.

Tujuan utama:

«Agent tidak berhenti diam-diam ketika model/provider mengalami error yang masih dapat dipulihkan.»

JANGAN mengimplementasikan Phase 5 atau fitur lain seperti Tool System, Memory, Subagent, Council, Scheduler, MCP, atau Browser.

Fokus hanya pada reliability Agent Loop dan model routing.

---

1. AUDIT PHASE 3

Sebelum coding, audit implementasi Agent Loop yang sekarang.

Cari:

- AgentLoop
- ModelProvider
- Model request/response
- exception/error handling
- AgentState
- session persistence
- provider configuration
- model configuration
- logging
- existing model selection

Jangan membuat sistem retry/fallback kedua jika infrastructure yang sesuai sudah ada.

Gunakan architecture existing.

---

2. ERROR CLASSIFICATION

Buat klasifikasi error.

Minimal:

TRANSIENT
RATE_LIMIT
TIMEOUT
PROVIDER_UNAVAILABLE
CONTEXT_OVERFLOW
INVALID_REQUEST
AUTH_ERROR
TOOL_ERROR
EMPTY_RESPONSE
UNKNOWN

Gunakan error classification sebagai dasar keputusan berikutnya.

Contoh:

Model request
     ↓
Error
     ↓
Classify
     ↓
Retry / Fallback / Stop

Jangan melakukan fallback untuk semua jenis error.

---

3. RETRY POLICY

Buat retry policy yang terkontrol.

Contoh konsep:

maxAttempts
backoff
retryableErrors

Error yang umumnya dapat dipertimbangkan untuk retry:

TRANSIENT
TIMEOUT
PROVIDER_UNAVAILABLE
RATE_LIMIT

Error seperti:

AUTH_ERROR
INVALID_REQUEST

jangan di-retry tanpa batas.

Jangan membuat infinite retry loop.

Gunakan exponential backoff atau mekanisme backoff yang sesuai dengan architecture project.

Jika provider memberikan informasi retry-after, manfaatkan jika tersedia.

---

4. RETRY STATE

Tambahkan atau gunakan existing state:

RETRYING

Contoh:

THINKING
   ↓
MODEL ERROR
   ↓
CLASSIFY
   ↓
RETRYING
   ↓
MODEL REQUEST

Logging harus menunjukkan:

attempt
maxAttempts
errorType
provider
model

---

5. FALLBACK MODEL

Setelah retry yang sesuai gagal, Agent dapat berpindah ke fallback model.

Contoh:

Primary:
providerA/modelA

Fallback:
providerB/modelB

Fallback:
providerC/modelC

Jangan hardcode provider tertentu.

Gunakan konfigurasi.

Architecture yang diinginkan:

Agent
 ↓
Model Router
 ↓
Fallback Chain
 ↓
Model Provider

Jika Model Router belum ada sebagai component terpisah, buat abstraction yang sederhana dan dapat dikembangkan nanti.

---

6. FALLBACK CHAIN

Buat konfigurasi fallback chain.

Contoh:

Fallback Chain:

1. gemini/model-a
2. openai/model-b
3. provider-custom/model-c

Setiap model harus memiliki:

provider
modelId
priority/order
enabled

Jangan menggunakan ranking atau kualitas model secara otomatis.

Urutan fallback harus berasal dari konfigurasi user.

---

7. WHEN TO FALLBACK

Contoh:

Primary request
      ↓
TRANSIENT
      ↓
Retry
      ↓
Still fails
      ↓
Fallback

Sedangkan:

AUTH_ERROR
      ↓
Do not blindly retry
      ↓
Move to fallback only if policy allows

Dan:

INVALID_REQUEST
      ↓
Do not repeatedly send identical invalid request

Tujuannya adalah menghindari:

same broken request
→ retry
→ same broken request
→ retry
→ same broken request

---

8. CONTEXT OVERFLOW

Jika model menolak request karena context terlalu besar:

CONTEXT_OVERFLOW
      ↓
do not blindly retry identical request

Untuk Phase 4, lakukan salah satu mekanisme yang paling aman berdasarkan architecture existing:

reduce context
atau
return controlled error
atau
fallback ke model dengan context window yang lebih besar

Context compaction lengkap akan dibuat pada Phase 13.

Jangan implementasikan full memory compaction sekarang.

---

9. EMPTY RESPONSE

Jika model request berhasil tetapi response kosong:

EMPTY_RESPONSE

Jangan kirim pesan kosong ke user.

Gunakan policy:

retry jika masih dalam retry budget
→ fallback jika diperlukan
→ controlled failure

---

10. NO INFINITE LOOPS

Ini WAJIB.

Pastikan kombinasi:

retry
+
fallback
+
Agent Loop

tidak dapat menghasilkan infinite loop.

Contoh:

Primary
 → retry
 → retry
 → fallback
 → retry
 → fallback
 → ...

harus memiliki batas yang jelas.

Gunakan execution-level budget, misalnya:

max total model attempts

bukan hanya max retry per provider.

---

11. FAILURE RESULT

Jika semua attempt gagal:

AgentState = FAILED

Simpan:

failure type
provider
model
attempt count
last error
timestamp

User menerima pesan yang sederhana.

Contoh:

Agent tidak dapat memproses pesan saat ini.
Silakan coba lagi nanti.

Jangan mengirim API error mentah, stack trace, atau credentials kepada user.

---

12. FALLBACK OBSERVABILITY

Tambahkan event:

MODEL_REQUEST
MODEL_ERROR
RETRY_STARTED
RETRY_COMPLETED
FALLBACK_STARTED
FALLBACK_MODEL_SELECTED
MODEL_SUCCESS
AGENT_FAILURE

Metadata:

provider
model
attempt
errorType
latency

Jangan log:

- API key
- authorization header
- secret
- credential

---

13. USER CONFIGURATION

Jika UI/architecture memungkinkan, siapkan configuration model fallback.

Minimal:

Primary Model
Fallback Models
Max Attempts
Retry Enabled

Namun jangan membuat UI besar hanya untuk Phase 4 jika architecture existing belum membutuhkan.

Configuration dapat sementara berada di existing settings/configuration layer.

---

14. MODEL PROBE

Jika project sudah memiliki model probe dari Phase 2, jangan membuat sistem probe kedua.

Jika belum ada, siapkan abstraction sederhana untuk:

probeModel()

Probe:

1 + 1 =

Catat:

available
latency
error
timestamp

PENTING:

Probe ini hanya mengukur availability/latency.

Jangan menggunakannya untuk menyimpulkan kualitas model.

---

15. CONCURRENCY

Pastikan retry/fallback satu request tidak membuat request lain pada conversation yang sama rusak.

Contoh:

Message A
 → retrying

Message B
 → jangan menyebabkan session corruption

Gunakan synchronization/queue sesuai architecture existing.

Jangan menggunakan global lock jika tidak diperlukan.

---

16. VERIFICATION TESTS

Test 1 — Normal Success

Primary model berhasil.

Expected:

1 model request
→ response

Tidak ada retry/fallback.

---

Test 2 — Timeout

Simulasikan timeout.

Expected:

request
→ TIMEOUT
→ retry
→ success

Jika retry gagal:

retry
→ fallback
→ success

---

Test 3 — Rate Limit

Simulasikan RATE_LIMIT.

Expected:

RATE_LIMIT
→ retry policy
→ fallback jika diperlukan

Tidak infinite loop.

---

Test 4 — Provider Unavailable

Primary provider tidak tersedia.

Expected:

Primary
→ classify PROVIDER_UNAVAILABLE
→ retry sesuai policy
→ fallback
→ fallback model response

---

Test 5 — Authentication Error

Primary API key invalid.

Expected:

AUTH_ERROR
→ jangan melakukan retry tanpa batas
→ controlled fallback/failure sesuai policy

---

Test 6 — Invalid Request

Simulasikan invalid request.

Expected:

INVALID_REQUEST
→ tidak melakukan infinite retry
→ controlled failure atau fallback sesuai policy

---

Test 7 — Empty Response

Model mengembalikan response kosong.

Expected:

EMPTY_RESPONSE
→ retry jika budget tersedia
→ fallback jika diperlukan
→ tidak mengirim pesan kosong

---

Test 8 — All Models Fail

Semua model gagal.

Expected:

Primary
→ retry
→ fallback
→ retry
→ final failure

Kemudian:

AgentState = FAILED

Tidak ada infinite loop.

---

Test 9 — Context Overflow

Simulasikan context overflow.

Expected:

Agent tidak mengirim ulang request identik berkali-kali.

---

Test 10 — WhatsApp End-to-End

WhatsApp
→ Agent Loop
→ Primary Model
→ forced failure
→ Fallback Model
→ Agent response
→ WhatsApp

Expected:

User tetap menerima response dari fallback model jika fallback berhasil.

---

17. REGRESSION TEST

Pastikan Phase 1–3 tetap bekerja:

- WhatsApp authentication
- WhatsApp connection
- WhatsApp send
- incoming message
- Agent Loop
- Session
- Conversation history
- Model Provider
- UI Chat
- Room persistence

Jangan merusak gateway yang sudah bekerja.

---

18. DEFINITION OF DONE

Phase 4 selesai jika:

- error dapat diklasifikasikan
- retry memiliki batas
- fallback chain dapat dikonfigurasi
- retry tidak infinite
- fallback tidak infinite
- provider/model dapat berpindah
- authentication error tidak di-retry secara membabi buta
- invalid request tidak di-loop
- empty response ditangani
- context overflow tidak menghasilkan infinite retry
- failure tersimpan
- logging tersedia
- WhatsApp end-to-end tetap bekerja
- build berhasil
- regression test berhasil

---

STOP CONDITION

Setelah Phase 4 selesai:

STOP.

Jangan implementasikan:

- Tool System
- Terminal
- Workspace
- Memory
- Learning
- Subagent
- Council
- Scheduler
- MCP
- Browser

Laporkan hasil implementation dan test terlebih dahulu.