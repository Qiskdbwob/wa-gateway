PHASE 3 — IMPLEMENT AGENT LOOP

Context

Phase 1 dan Phase 2 sudah selesai dan telah diverifikasi.

Project sekarang memiliki:

- WhatsApp Gateway native yang sudah bekerja
- Agent Core dasar
- Model Provider abstraction
- konfigurasi model/provider
- persistent session/memory dasar
- UI yang sudah selesai di-redesign

Sekarang implementasikan PHASE 3: Agent Loop.

Tujuan

Agent harus mampu menjalankan siklus kerja:

Incoming Message
      ↓
Load Session
      ↓
Build Context
      ↓
Call Model
      ↓
Process Model Response
      ↓
Final Response
      ↓
Persist Response
      ↓
Send Response

Untuk Phase 3 ini fokus pada Agent Loop dasar terlebih dahulu.

JANGAN implementasikan Phase 4+ seperti retry/fallback, subagent, council, scheduler, MCP, browser automation, atau learning system.

---

1. AUDIT EXISTING AGENT CORE

Sebelum coding, periksa implementasi Phase 1–2.

Temukan:

- Agent class/interface
- Agent configuration
- Session model
- Message model
- Model Provider
- Model request/response
- Room DAO/repository
- WhatsApp incoming message callback
- WhatsApp send-message API
- existing UI chat flow

Jangan membuat abstraction kedua jika abstraction yang diperlukan sudah tersedia.

Gunakan existing implementation jika memang sesuai.

---

2. DEFINISIKAN AGENT LOOP

Buat satu komponen utama yang bertanggung jawab menjalankan agent.

Contoh konsep:

AgentLoop
    ↓
processMessage()
    ↓
loadSession()
    ↓
buildContext()
    ↓
modelProvider.generate()
    ↓
processResponse()
    ↓
persist()
    ↓
sendResponse()

Nama class/function boleh disesuaikan dengan architecture existing.

Jangan memaksakan nama tersebut jika project sudah memiliki pola penamaan yang lebih tepat.

---

3. INPUT MESSAGE

Agent Loop harus dapat menerima message dalam bentuk internal yang tidak bergantung langsung pada WhatsApp.

Contoh:

AgentInput

messageId
conversationId
senderId
content
timestamp
channel
metadata

WhatsApp Gateway hanya bertugas mengubah WhatsApp event menjadi "AgentInput".

Dengan demikian:

WhatsApp
   ↓
WhatsApp Adapter
   ↓
AgentInput
   ↓
AgentLoop

Agent Loop jangan mengetahui detail internal "whatsmeow".

---

4. SESSION

Saat menerima message:

1. Cari session berdasarkan conversation identifier.
2. Jika belum ada, buat session.
3. Simpan incoming user message.
4. Load context yang diperlukan.
5. Jalankan model.

Pastikan conversation berbeda tidak tercampur.

Contoh:

WhatsApp Chat A
    ↓
Session A

WhatsApp Chat B
    ↓
Session B

Jangan menggunakan satu global conversation history untuk semua chat.

---

5. CONTEXT BUILDING

Untuk Phase 3 gunakan context sederhana terlebih dahulu:

System Prompt
+
Conversation History
+
Current User Message

Gunakan history dari persistent storage.

Jangan memasukkan seluruh database secara otomatis.

Jangan membuat memory retrieval kompleks pada phase ini.

Context Manager lengkap akan dikembangkan pada phase berikutnya.

---

6. MODEL REQUEST

Agent Loop harus menggunakan abstraction Model Provider yang sudah dibuat pada Phase 2.

Jangan memanggil OpenAI/Gemini/API tertentu langsung dari Agent Loop.

Alurnya harus:

AgentLoop
    ↓
ModelProvider
    ↓
Configured Model
    ↓
API

Agent hanya mengetahui interface provider.

---

7. RESPONSE HANDLING

Model response harus diproses menjadi internal response.

Contoh:

AgentResponse

content
finishReason
usage
model
provider
metadata

Untuk Phase 3 fokus pada text response.

Jika model mengembalikan response kosong atau malformed:

- jangan mengirim pesan kosong ke WhatsApp
- catat error
- tampilkan error yang sesuai
- jangan membuat aplikasi crash

---

8. PERSIST RESPONSE

Setelah mendapatkan response valid:

1. Simpan assistant message ke session.
2. Simpan metadata yang tersedia.
3. Kemudian kirim response ke channel.

Urutan harus menjamin response tidak hilang begitu saja jika terjadi masalah setelah model selesai.

Jika architecture existing memiliki transaction mechanism yang lebih tepat, gunakan itu.

---

9. WHATSAPP ADAPTER

Hubungkan Agent Loop dengan WhatsApp Gateway yang sudah ada.

Incoming:

WhatsApp Gateway
      ↓
Incoming Event
      ↓
WhatsApp Adapter
      ↓
AgentInput
      ↓
AgentLoop

Outgoing:

AgentLoop
      ↓
AgentResponse
      ↓
WhatsApp Adapter
      ↓
Existing Gateway Send API
      ↓
WhatsApp

PENTING:

Jangan rewrite WhatsApp Gateway.

Jangan mengganti "whatsmeow".

Jangan membuat gateway baru.

Gunakan API gateway yang sudah berhasil diuji.

---

10. UI CHAT INTEGRATION

Hubungkan Agent Loop dengan Chat UI hasil redesign.

Saat user mengirim pesan:

User
 ↓
Chat UI
 ↓
AgentLoop

UI harus menampilkan state sederhana:

Thinking...

kemudian:

Agent response

Jika sedang processing, cegah accidental duplicate submission untuk message yang sama.

Jangan membuat fitur edit/resend/retry message pada phase ini kecuali memang sudah ada dari implementation sebelumnya.

---

11. AGENT STATE

Gunakan state machine yang sudah direncanakan.

Untuk Phase 3 minimal:

IDLE
THINKING
COMPLETED
FAILED

Jika architecture sudah mendukung state tambahan, jangan menghapusnya.

Future state:

CALLING_TOOL
WAITING_TOOL
DELEGATING
WAITING_SUB_AGENT
REFLECTING
RETRYING
FALLBACK
WAITING_APPROVAL

Belum perlu mengimplementasikan behavior state tersebut sekarang.

---

12. CONCURRENCY

Perhatikan kemungkinan dua message masuk hampir bersamaan.

Jangan sampai:

Message A
Message B

mengubah session secara race condition.

Pastikan session/message persistence aman.

Jika diperlukan, gunakan queue atau per-session synchronization.

Jangan membuat global lock yang menyebabkan seluruh conversation saling memblokir jika tidak diperlukan.

---

13. ERROR HANDLING PHASE 3

Phase 4 akan memiliki retry/fallback yang lebih lengkap.

Untuk Phase 3 cukup pastikan error tidak membuat Agent Loop diam-diam berhenti.

Minimal:

Model request failed
        ↓
record error
        ↓
set AgentState = FAILED
        ↓
persist failure if appropriate
        ↓
inform user

Contoh pesan user:

Agent mengalami error saat memproses pesan.

Detail teknis tetap masuk log, bukan dikirim mentah ke user.

Jangan melakukan automatic retry kompleks pada phase ini.

---

14. OBSERVABILITY DASAR

Tambahkan log untuk:

MESSAGE_RECEIVED
SESSION_LOADED
MODEL_REQUEST
MODEL_RESPONSE
MESSAGE_PERSISTED
RESPONSE_SENT
AGENT_ERROR

Jika token usage dan latency sudah tersedia dari provider, simpan juga.

Jangan log:

- API key
- authorization header
- credentials
- secret token

---

15. VERIFICATION

Setelah implementasi selesai, lakukan build dan test.

Test 1 — Basic Chat

User:
Halo

Expected:
Agent memberikan response.

Test 2 — Context

User:
Nama saya Ahmad.

User:
Siapa nama saya?

Expected:

Agent dapat menjawab berdasarkan conversation history.

Test 3 — Separate Session

Conversation A:
Saya suka fisika.

Conversation B:
Apa yang saya sukai?

Expected:

Conversation B tidak menggunakan history Conversation A.

Test 4 — Persistence

Send message
↓
Close/restart app
↓
Open conversation

Expected:

Conversation masih tersedia.

Test 5 — WhatsApp

WhatsApp message
↓
Agent Loop
↓
Model
↓
WhatsApp response

Expected:

Pesan masuk WhatsApp menghasilkan response melalui gateway yang sudah ada.

Test 6 — Model Error

Simulasikan kondisi model/provider gagal.

Expected:

- aplikasi tidak crash
- Agent state menjadi FAILED
- error tercatat
- user mendapat pesan error
- Agent tidak hang tanpa status

Test 7 — Empty Response

Jika model memberikan response kosong:

Expected:

- jangan kirim pesan kosong
- record error
- Agent state FAILED

---

16. REGRESSION TEST

Setelah Phase 3 selesai, pastikan fitur Phase 1–2 tetap bekerja:

- WhatsApp connect
- WhatsApp authentication
- WhatsApp send message
- model configuration
- API key configuration
- model selection
- session persistence
- existing UI
- Room database

Jika salah satu rusak, perbaiki sebelum menyatakan Phase 3 selesai.

---

17. DEFINITION OF DONE

Phase 3 dianggap selesai hanya jika:

WhatsApp Message
      ↓
Existing WhatsApp Gateway
      ↓
WhatsApp Adapter
      ↓
AgentInput
      ↓
AgentLoop
      ↓
Session
      ↓
Context
      ↓
Model Provider
      ↓
AgentResponse
      ↓
Persistence
      ↓
WhatsApp Adapter
      ↓
Existing WhatsApp Gateway
      ↓
WhatsApp Response

berjalan end-to-end.

Selain itu:

- build berhasil
- tidak ada crash pada basic flow
- session tidak tercampur
- conversation tetap ada setelah restart
- UI menampilkan processing state
- error tidak membuat loop berhenti diam-diam
- gateway existing tidak direwrite
- tidak ada API key di source code atau log

---

OUTPUT REPORT

Setelah selesai, jangan langsung mengerjakan Phase 4.

Laporkan:

1. File yang dibuat.
2. File yang diubah.
3. Architecture Agent Loop.
4. Bagaimana incoming WhatsApp message masuk ke Agent Loop.
5. Bagaimana response dikirim kembali.
6. Bagaimana session dipisahkan.
7. Bagaimana error ditangani.
8. Hasil build.
9. Hasil setiap test.
10. Masalah yang masih tersisa.

Kemudian STOP.

Jangan lanjut ke Phase 4 sebelum mendapat instruksi berikutnya.