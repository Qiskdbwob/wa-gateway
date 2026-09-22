MVP TASK BRIEF — Native WhatsApp Gateway Android

Tujuan

Buat MVP aplikasi Android native yang fungsi utamanya adalah menjadi WhatsApp Gateway lokal.

MVP ini belum menjadi AI agent. Fokus utama hanya membuktikan bahwa aplikasi Android dapat:

1. Membuat session WhatsApp.
2. Menampilkan QR login.
3. Terhubung ke WhatsApp menggunakan "whatsmeow".
4. Mempertahankan session setelah aplikasi ditutup/dibuka kembali.
5. Menerima pesan teks.
6. Mengirim pesan teks.
7. Menampilkan status koneksi.

Gunakan Android native Kotlin + Jetpack Compose.

Untuk WhatsApp protocol gunakan "whatsmeow" (Go).

Gunakan "gomobile bind" untuk membuat Go package menjadi Android ".aar", kemudian import ".aar" tersebut ke project Kotlin.

Jangan menggunakan Baileys.
Jangan menggunakan Node.js runtime.
Jangan menggunakan local socket.
Jangan menggunakan Termux.
Jangan menggunakan root.
Jangan menggunakan accessibility service.

Arsitektur harus menggunakan komunikasi in-process Kotlin ↔ Go melalui gomobile binding.

---

REFERENSI ARSITEKTUR

Gunakan struktur berikut sebagai target arsitektur:

app/
Kotlin Android code
└── wagateway/
├── WaGatewayManager.kt
└── WaEventListener.kt

go-wagateway/
├── go.mod
├── client.go
├── listener.go
└── store.go

Build Go package menjadi:

app/libs/wagateway.aar

Kemudian Kotlin menggunakan:

implementation(files("libs/wagateway.aar"))

Session WhatsApp disimpan pada SQLite di app-specific storage.

Gunakan pure-Go SQLite driver jika diperlukan agar tidak membutuhkan CGO.

---

ATURAN PALING PENTING: JANGAN MENGARANG API

"whatsmeow" adalah dependency eksternal yang API-nya dapat berubah.

Sebelum menulis wrapper Go:

1. Tentukan versi "whatsmeow" yang benar-benar digunakan.
2. Ambil dependency tersebut melalui Go module.
3. Periksa API aktual dependency tersebut.
4. Periksa signature method yang benar-benar tersedia.
5. Periksa event type yang benar-benar tersedia.
6. Periksa cara login QR yang benar-benar digunakan oleh versi tersebut.
7. Periksa cara mengirim pesan yang benar-benar digunakan oleh versi tersebut.
8. Periksa cara menangani event pesan masuk yang benar-benar digunakan oleh versi tersebut.

Jangan mengarang nama struct, method, event, package, atau signature.

Jika API berbeda dengan contoh spesifikasi, sesuaikan implementasi dengan API aktual dependency.

Komentari bagian yang harus disesuaikan berdasarkan versi dependency.

Jangan membuat fake implementation hanya agar project berhasil compile.

Jika sebuah fitur belum dapat diimplementasikan karena API belum diverifikasi, berhenti pada fitur tersebut dan jelaskan dependency/API yang belum terverifikasi.

---

TAHAP 1 — PROJECT FOUNDATION

Buat project Android native:

- Kotlin
- Jetpack Compose
- Gradle Kotlin DSL
- minSdk 24 atau lebih tinggi
- struktur project sederhana dan mudah diaudit

Buat module/package Go terpisah:

go-wagateway/

Gunakan Go modules.

Tambahkan dependency:

- whatsmeow
- pure-Go SQLite driver yang kompatibel dengan kebutuhan whatsmeow

Jangan menambahkan dependency yang tidak diperlukan.

---

TAHAP 2 — GO WHATSAPP WRAPPER

Buat wrapper sederhana yang menjadi boundary antara Go dan Kotlin.

Target interface konseptual:

Client

- NewClient(...)
- Connect()
- Disconnect()
- IsLoggedIn()
- SendText(...)
- SetTyping(...) jika API aktual mendukung
- MarkRead(...) jika API aktual mendukung
- Logout() jika API aktual mendukung

Event callback sederhana:

EventListener

- OnMessage(fromJID, text, timestamp)
- OnConnectionStatus(status)
- OnQRCode(code)

Jika API aktual whatsmeow menggunakan mekanisme berbeda, jangan memaksakan interface di atas.

Adaptasikan API aktual ke interface sederhana yang aman untuk gomobile.

Jangan expose struct kompleks whatsmeow ke Kotlin.

Boundary Go → Kotlin hanya menggunakan tipe sederhana seperti:

- String
- Boolean
- Int
- Long
- byte array jika benar-benar diperlukan

---

TAHAP 3 — GOMOBILE AAR

Buat proses build yang menghasilkan:

app/libs/wagateway.aar

Gunakan:

gomobile bind

Target Android.

Pastikan:

1. Go package berhasil dikompilasi.
2. gomobile binding berhasil.
3. ".aar" benar-benar dihasilkan.
4. Android project dapat mengimpor ".aar".
5. Kotlin dapat mengakses class hasil binding.

Jangan membuat placeholder ".aar".

Jika environment AI Studio tidak dapat menjalankan gomobile/NDK atau tidak dapat menghasilkan AAR secara nyata, jangan berpura-pura bahwa build berhasil.

Sebaliknya, dokumentasikan blocker dengan jelas dan buat semua source code tetap siap dibuild di CI/local environment.

---

TAHAP 4 — KOTLIN BRIDGE

Buat:

WaGatewayManager.kt

Tanggung jawab:

- membuat client Go
- connect
- disconnect
- mengirim pesan
- menerima callback
- meneruskan status koneksi ke UI
- meneruskan QR ke UI

Buat:

WaEventListener.kt

untuk menerima callback dari Go.

Jangan memasukkan logic AI ke dalam class ini.

Gateway harus menjadi komponen transport saja.

---

TAHAP 5 — QR LOGIN UI

Buat layar sederhana:

WhatsApp Gateway

Status:

Disconnected
Connecting
Waiting for QR
Connected
Logged out
Error

Jika QR tersedia:

tampilkan QR code.

User harus dapat:

- Connect
- Disconnect

Ketika WhatsApp belum memiliki session:

Connect
→ WhatsApp connection dibuat
→ QR diterima
→ QR ditampilkan
→ user scan QR menggunakan WhatsApp
→ connection menjadi Connected

Setelah connected:

tampilkan status:

Connected

Jika session masih tersimpan pada aplikasi:

Connect
→ gunakan session yang tersimpan
→ jangan meminta QR lagi kecuali session sudah logout/expired.

---

TAHAP 6 — TEST CHAT

Tambahkan layar test sederhana.

Field:

Target WhatsApp JID / nomor

Message

Button:

Send

Setelah Send ditekan:

Kotlin
→ WaGatewayManager
→ Go wrapper
→ whatsmeow
→ WhatsApp

Tidak perlu membuat UI chat lengkap.

Tujuan fitur ini hanya membuktikan bahwa gateway benar-benar dapat mengirim pesan.

---

TAHAP 7 — INCOMING MESSAGE

Ketika WhatsApp menerima pesan teks:

whatsmeow
→ Go event handler
→ EventListener
→ Kotlin
→ UI

Tampilkan daftar pesan sederhana:

Sender
Message
Timestamp

Tidak perlu database chat lengkap pada MVP.

Tidak perlu AI response.

Tidak perlu Agent Core.

---

TAHAP 8 — SESSION STORAGE

Session credential WhatsApp harus disimpan pada app-specific storage.

Jangan menggunakan:

- public storage
- Downloads
- shared external storage

Gunakan storage internal aplikasi.

Credential/session tidak boleh dapat diakses aplikasi lain secara normal.

---

TAHAP 9 — ANDROID LIFECYCLE

Jika koneksi dasar sudah berhasil, tambahkan lifecycle handling.

Target akhirnya:

WhatsApp connection dapat tetap berjalan ketika aplikasi berada di background menggunakan Android Foreground Service.

Tetapi jangan membuat Foreground Service kompleks sebelum koneksi dasar berhasil.

Urutan:

1. QR login bekerja.
2. Connect bekerja.
3. Send bekerja.
4. Receive bekerja.
5. Session persistence bekerja.
6. Baru implementasikan Foreground Service.

Jika Foreground Service menyebabkan masalah build atau mengganggu MVP utama, pisahkan sebagai tahap berikutnya.

---

FITUR YANG JANGAN DIBUAT PADA MVP

Jangan implementasikan:

- AI agent
- Gemini API
- Agent Core
- multi-agent
- tools
- memory
- scheduler
- media
- voice
- calls
- buttons
- lists
- polls
- carousel
- pairing code
- typing simulation
- human-like behavior
- automatic read delay
- whitelist
- group management
- broadcast
- web dashboard

Semua fitur tersebut berada di luar MVP.

Fokus hanya:

QR → connect → send text → receive text → session persistence.

---

VERIFICATION REQUIREMENTS

Setelah setiap tahap penting, lakukan verifikasi nyata.

Jangan hanya mengatakan:

"Implemented successfully."

Buktikan dengan build/test yang benar-benar dijalankan.

Minimal:

1. Gradle project dapat di-build.
2. Go module dapat di-build.
3. gomobile binding dapat dibuat jika environment mendukung.
4. ".aar" dapat di-import Kotlin.
5. Android APK dapat dibuat.
6. Tidak ada unresolved reference terhadap API whatsmeow.
7. Tidak ada fake/mock implementation yang dianggap sebagai WhatsApp connection nyata.

Jika environment tidak mampu melakukan salah satu tahap, nyatakan secara eksplisit:

NOT VERIFIED

dan jelaskan apa yang sebenarnya berhasil dan apa yang belum.

---

ERROR HANDLING

Jangan membuat error menyebabkan aplikasi crash.

Status error harus diteruskan ke UI.

Contoh:

Connection failed
QR generation failed
Session initialization failed
Send failed
Receive handler failed
Logout detected

Gunakan retry/backoff untuk reconnect jika sudah masuk tahap lifecycle.

Jangan melakukan reconnect loop tanpa delay.

---

DEVELOPMENT STRATEGY

Jangan mencoba membuat seluruh aplikasi sekaligus.

Kerjakan secara bertahap:

PHASE A
Project + Go module + dependency verification.

PHASE B
whatsmeow wrapper.

PHASE C
gomobile AAR.

PHASE D
Kotlin integration.

PHASE E
QR login.

PHASE F
Send text.

PHASE G
Receive text.

PHASE H
Session persistence.

PHASE I
Foreground Service.

Setelah setiap phase, build dan verify sebelum melanjutkan.

Jika sebuah phase gagal, perbaiki phase tersebut terlebih dahulu.

Jangan melanjutkan dengan workaround palsu.

---

FINAL SUCCESS CRITERIA

MVP dianggap berhasil hanya jika pada perangkat Android nyata:

1. App dibuka.
2. User menekan Connect.
3. QR WhatsApp muncul.
4. User scan QR.
5. Status berubah menjadi Connected.
6. User memasukkan nomor/JID target.
7. User memasukkan pesan.
8. User menekan Send.
9. Pesan benar-benar diterima oleh akun WhatsApp target.
10. Ketika target membalas, pesan masuk muncul di aplikasi.
11. App dapat ditutup dan dibuka kembali.
12. Session masih tersedia tanpa login QR ulang, selama session WhatsApp masih valid.

Jika poin 9 atau 10 belum terbukti pada perangkat nyata, jangan menyatakan MVP selesai.

---

ATURAN KERJA AGENT

Sebelum melakukan perubahan besar:

- inspeksi project terlebih dahulu
- identifikasi file yang relevan
- gunakan dependency/API aktual
- jangan mengarang API
- jangan mengganti arsitektur tanpa alasan
- jangan menambahkan fitur di luar scope
- jangan menghapus konfigurasi yang tidak berkaitan
- jangan menyembunyikan build error

Jika menemukan konflik antara task ini dan API/library aktual:

1. prioritaskan API aktual yang dapat diverifikasi;
2. pertahankan tujuan arsitektur;
3. jelaskan perbedaannya;
4. jangan membuat API palsu agar terlihat selesai.

Tujuan MVP bukan banyak fitur.

Tujuan MVP adalah membuktikan secara nyata:

Android Native
→ gomobile
→ Go
→ whatsmeow
→ WhatsApp
→ pesan masuk/keluar


Ada alasan saya membuat prompt ini cukup “keras”. Spec yang kamu upload sendiri menyebut signature `SendText()` di contoh masih placeholder dan API event `whatsmeow` harus dicek terhadap versi yang benar-benar digunakan. Jadi kalau kita langsung menyuruh Gemini menyalin contoh itu, justru peluang halusinasinya tinggi.

Satu hal lagi: **AI Studio bisa membuat source Android-nya, tetapi bagian `gomobile bind` + NDK adalah titik yang perlu kita waspadai.** Dokumentasi Google memang mengonfirmasi Android native Kotlin/Compose dan import GitHub, tetapi itu tidak berarti setiap native toolchain Go/NDK pasti tersedia dan berjalan sempurna di environment Build.

Jadi target paling aman adalah membuat AI Studio **membuktikan dulu apakah environment-nya sanggup menghasilkan `wagateway.aar`**. Kalau ternyata tidak, source project-nya tetap bisa kita ambil/sync ke GitHub lalu build AAR lewat GitHub Actions.