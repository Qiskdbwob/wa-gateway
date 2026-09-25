# WA Gateway — Personal AI Agent via WhatsApp

Aplikasi Android native (Kotlin + Jetpack Compose) yang berfungsi sebagai **agent AI pribadi**
dengan WhatsApp sebagai salah satu channel. Transport WhatsApp menggunakan
[`whatsmeow`](https://github.com/tulir/whatsmeow) (Go) yang di-bind ke Android memakai `gomobile`,
jadi seluruh koneksi WhatsApp berjalan **in-process** — tanpa Node.js, tanpa Termux, tanpa root,
dan tanpa accessibility service.

```
WhatsApp  ⇄  Go gateway (whatsmeow)  ⇄  Kotlin Bridge  ⇄  Agent Loop  ⇄  Model Provider
                                              │                 │
                                        foreground service   Room (sesi, pesan, config)
```

---

## Arsitektur

| Lapisan | Lokasi | Tanggung jawab |
|---|---|---|
| Transport WhatsApp | `go-wagateway/` | `NewClient / Connect / Disconnect / SendText / Logout`, event QR & pesan masuk, session SQLite di app-internal storage |
| Binding | `app/libs/wagateway.aar` (dibuat CI) | Boundary gomobile Go ↔ Kotlin; hanya `String / Boolean / Long` yang melintas |
| Gateway | `com.example.wagateway` | `WaGatewayManager` (StateFlow), `WaGatewayService` (foreground), `WaGatewayViewModel` |
| Agent Core | `com.example.agent` | `AgentLoop` (state machine), `ModelErrorClassifier`, `ModelRouter` (retry + fallback + probe), `ToolRegistry` + `BuiltInTools`, `OpenAiCompatibleProvider`, `EchoTestProvider` |
| Persistensi | `com.example.agent.storage` | Room: `agent_sessions`, `agent_messages`, `agent_configs` + `SecretCipher` (API key) |
| Workspace | `com.example.agent.workspace` | `Workspace` — root per-agent di `filesDir/workspaces/<agentId>` + guard path (anti `..` & symlink escape) |
| Adapter | `WhatsAppAgentBridge` | Menjembatani gateway ⇄ agent loop, memuat/menyimpan konfigurasi |

**Alur pesan**

```
incoming  WhatsApp → Go event → WaGatewayManager → WhatsAppAgentBridge → AgentInput → AgentLoop
outgoing  AgentLoop → AgentResponse → WhatsAppChannelAdapter → WaGatewayManager.sendText → WhatsApp
```

Di dalam satu turn, Agent Loop juga bisa berhenti di tengah jalan untuk memanggil tool:

```
AgentLoop → ModelProvider (tool_calls) → ToolRegistry → Tool → tool role message → ModelProvider → AgentResponse
```

Konversasi dipisah per-chat memakai JID percakapan (`AgentInput.conversationId`), sehingga history
antar kontak tidak pernah tercampur.

---

## Status fitur (jujur)

| Kemampuan | Status |
|---|---|
| QR login, pairing code, connect/disconnect/logout | ✅ |
| Session WhatsApp persisten (SQLite app-internal) | ✅ |
| Kirim & terima pesan teks | ✅ |
| Foreground service + notifikasi | ✅ |
| Agent Loop (session, context, persist, kirim balasan) | ✅ |
| Tool System (Phase 6: registry, tool-call loop, batas iterasi, progress) | ✅ |
| Klasifikasi error, retry + exponential backoff, fallback model, budget anti-infinite-loop | ✅ |
| Model probe (`1 + 1 =`) untuk cek availability & latency | ✅ |
| Provider OpenAI-compatible + Echo fallback offline | ✅ |
| Penyimpanan API key terenkripsi (Android Keystore, AES-256-GCM) | ✅ |
| Keys pool: banyak API key dirotasi saat satu kunci kena limit/quota (401/402/403/429) | ✅ |
| Unified search: satu tool `search` untuk memori, riwayat chat, task, file workspace & daftar tool | ✅ |
| UI: Beranda, Chat, Tugas, Memori, Pengaturan, Developer | ✅ |
| Kesadaran waktu lokal: hari/tanggal/jam perangkat disuntik ke system prompt tiap turn, plus tool `current_time` | ✅ |
| Auto-reply grup | ❌ (sengaja dinonaktifkan, hanya chat pribadi) |
| Kontrol akses kontak: whitelist & blacklist (per nomor, dinormalisasi dari JID) | ✅ |
| Command chat `/help /status /whitelist /blacklist /approve /reject /compact /remember /learning` | ✅ |
| Pesan media masuk: gambar/video (analisis vision), dokumen teks dibaca, audio dicatat | ✅ |
| Kirim media keluar: gambar, dokumen, audio/voice note, video | ✅ (API siap; UI belum memakainya) |
| Tool system: registry, tool-call loop, batas iterasi, retry/fallback pada tool turn | ✅ |
| Workspace isolation + file tools (list/read/write/append/move/copy/delete/mkdir) | ✅ |
| Permission tool (SAFE / AUTO_SAFE / CONFIRM) + approval destruktif via chat & UI | ✅ |
| Memory layer: episodic/knowledge/learning + RAG lexical + context manager | ✅ |
| Auto & manual compact (`/compact`) menjadi memori episodik | ✅ |
| Subagent latar belakang (tidak memblokir balasan agent utama) | ✅ |
| Council 2 debater + moderator, self-reflection (`reflect`) & learning pipeline | ✅ |
| Task/job system: scheduler interval + UI Tugas (Scheduled/Sub-agent) | ✅ |
| Web search & web fetch (read-only, AUTO_SAFE) | ✅ |
| Terminal bawaan: agent menjalankan `curl`/`wget`/skrip bash-python + shell interaktif di app | ✅ (shell perangkat `/system/bin/sh`; perintah destruktif = approval) |
| Browser automation: buka halaman, isi form, submit, screenshot, sesi login tersimpan | ✅ (engine Android WebView di balik `BrowserEngine`; GeckoView dapat ditukar) |
| Serah terima captcha / 2FA ke pengguna (agent menunggu, tidak mengarang hasil) | ✅ |
| Kirim file hasil agent ke chat WhatsApp (screenshot, laporan, unduhan) | ✅ |
| MCP, Linux sandbox/proot, skill markdown universal | ❌ (dokumen referensi ada di `DOC/reference/`) |
| Skills/marketplace, observability lanjutan | ❌ |

Fitur yang belum ada **tidak** ditampilkan sebagai UI palsu — menu yang belum didukung menampilkan
empty state yang menjelaskan statusnya.

---

## Kebutuhan

* Android Studio / Android SDK dengan **compileSdk 36**, **minSdk 24**
* **JDK 17**
* **Gradle 9.3.1** (lihat `gradle/wrapper/gradle-wrapper.properties`)
* Untuk membangun gateway: **Go 1.26+**, **Android NDK 28.0.12674087**, `gomobile`

---

## Build

### 1. Bangun AAR gateway (wajib sekali per perubahan Go)

```bash
cd go-wagateway
go install golang.org/x/mobile/cmd/gobind@latest
go install golang.org/x/mobile/cmd/gomobile@latest
gomobile init
gomobile bind -target=android/arm64,android/amd64,android/arm -androidapi 24 -o ../app/libs/wagateway.aar .
```

Target ABI yang dihasilkan: `arm64-v8a`, `x86_64`, dan `armeabi-v7a` (32-bit ARM).
`app/build.gradle.kts` memakai `abiFilters` untuk ABI yang sama supaya aplikasi tidak pernah
ter-install di perangkat yang tidak punya `libgojni.so`. Dukungan 64-bit tetap ada, jadi syarat
Google Play (wajib menyediakan 64-bit bila menyediakan 32-bit) terpenuhi.

### 2. Build aplikasi

```bash
gradle testDebugUnitTest     # unit test
gradle assembleDebug         # APK debug
```

> `app/libs/wagateway.aar` tidak di-commit. Tanpa file itu, kompilasi Kotlin akan gagal dengan
> `unresolved reference: wagateway`. Detail: `app/libs/README.md`.

### 3. Lewat GitHub Actions

`.github/workflows/build.yml` menjalankan tiga job:

| Job | Trigger | Hasil |
|---|---|---|
| `gateway-aar` | semua push/PR | `wagateway.aar` (artifact) |
| `android` | butuh `gateway-aar` | unit test + `wagateway-debug-apk` |
| `release` | hanya tag `v*` | APK & AAB **bertanda tangan** + GitHub Release |

Workflow dibuat satu file agar artifact AAR bisa dipakai antar-job (menghindari mismatch nama
artifact antar workflow). Gradle di-provision otomatis sesuai versi di `gradlew` properties,
jadi `gradle-wrapper.jar` tidak perlu di-commit.

---

## Cara pakai

1. Buka aplikasi → **Pengaturan → WhatsApp Gateway**.
2. Pilih **QR** atau **Pairing Code**, lalu hubungkan akun WhatsApp Anda. Setelah tertaut, aplikasi
   akan **menyambung ulang otomatis** setiap dibuka — tidak perlu scan QR lagi. Pakai tombol
   **Putuskan Sesi** hanya bila ingin menautkan perangkat/akun lain dari awal.
3. Isi **Base URL**, **API Key**, **Model ID**, dan **System Prompt** di Pengaturan.
4. Aktifkan **Auto-Reply Pesan WhatsApp** di Beranda, atau ngobrol langsung di tab **Chat**.
5. Cek status di Beranda, riwayat di tab **Tugas** & **Memori**.

Provider apa pun yang kompatibel dengan API OpenAI (`POST {baseUrl}/chat/completions`) bisa dipakai —
OpenAI, OpenRouter, LM Studio, Ollama, atau gateway internal. Bila API Key kosong dan Echo fallback
aktif, agent tetap membalas secara lokal tanpa jaringan.

---

## Keamanan & privasi

* API key disimpan **terenkripsi** (`enc:v1:` + AES-256-GCM) dengan kunci tidak-ekspor di Android
  Keystore (`SecretCipher`). Nilai lama berbentuk plaintext tetap dibaca agar upgrade tidak merusak data.
* API key tidak pernah ditulis ke log, dan di UI tertutup secara default (toggle lihat/sembunyikan).
* Session WhatsApp dan seluruh chat tersimpan di **app-internal storage** (`filesDir`), tidak dapat
  diakses aplikasi lain.
* Pesan yang dikirim oleh akun sendiri **tidak** diproses (`Info.IsFromMe`), sehingga agent tidak
  bisa membalas dirinya sendiri.
* Saat ini pesan grup diabaikan untuk mencegah agent mengirim ke grup tanpa konfigurasi.
* **Whitelist mode**: bila diaktifkan (Pengaturan atau `/whitelist on`), hanya nomor yang terdaftar
  yang diproses; pesan lain di-drop sebelum masuk ke model dan dicatat sebagai `CONTACT_BLOCKED`.
  Blacklist selalu menang atas whitelist.
* Tool berkelas `CONFIRM` **tidak** pernah ditawarkan ke model selama approval dimatikan, dan saat
  approval aktif pemanggilannya **tidak langsung dieksekusi** — agent membuat request `appr-xxxxxxxx`
  dan menunggu `/approve <id>` atau `/reject <id>` (kedaluwarsa 24 jam, hanya bisa diputuskan sekali).
  Hanya tool destruktif (saat ini `delete_path`) yang masuk kelas ini; tool baca-tulis lain tetap
  otomatis.
* Agent Loop tidak menyimpan pesan tool ke database; hanya pertanyaan pengguna dan jawaban akhir
  yang masuk riwayat percakapan. Detail teknis tool masuk ke log aktivitas.

---

## Publikasi ke Play Store

1. Buat upload key: `keytool -genkeypair -keystore release.keystore -alias upload ...`
2. Tambahkan repository secrets: `KEYSTORE_BASE64` (`base64 -w0 release.keystore`),
   `STORE_PASSWORD`, `KEY_PASSWORD`, dan `KEY_ALIAS` bila alias bukan `upload`.
3. Bump `versionCode` / `versionName` di `app/build.gradle.kts`.
4. Tag rilis: `git tag v1.0.0 && git push origin v1.0.0` → workflow `release` membuat APK + AAB dan
   mempublikasikannya sebagai GitHub Release. Upload `.aab` ke Play Console.
5. Perlengkapan store: screenshot, deskripsi, privacy policy, dan deklarasi **Data safety**
   (aplikasi menyimpan konten chat secara lokal dan mengirimkan isi chat ke provider model yang Anda
   konfigurasi — ini harus dideklarasikan sebagai data yang dikirim ke pihak ketiga).
6. Catatan: `applicationId` saat ini `com.aistudio.wagateway.qnzr`. Ganti sebelum rilis publik
   pertama bila Anda ingin identitas paket sendiri (harus tetap permanen setelah dipublikasikan).

---

## Catatan perubahan

### Sesi WhatsApp tidak lagi hilang setelah aplikasi ditutup

Sebelumnya, setelah aplikasi dipaksa berhenti, gateway **tidak** menyambung ulang dan hanya
menampilkan pesan ini:

```
Connection Error: failed to get QR channel: GetQRChannel can only be called when there's no user ID in the client's Store
```

Penyebabnya: pada versi `whatsmeow` yang dipin, `Client.IsLoggedIn()` adalah **flag runtime** yang
baru bernilai `true` setelah socket terautentikasi. Saat aplikasi baru dibuka flag itu masih `false`
walaupun sesi tersimpan ada, sehingga alur kode meminta QR baru — padahal store berisi user ID dan
whatsmeow menolak dengan `ErrQRStoreContainsID`.

Perbaikan: keputusan resume/QR kini berdasarkan `Store.ID` di SQLite store (`Client.HasSession()`):

* `Connect()` melanjutkan sesi yang tersimpan; QR hanya diminta bila perangkat belum pernah tertaut.
* Auto-connect saat aplikasi dibuka, tanpa scan QR / kode pairing.
* Tombol **Hubungkan Ulang** dan **Putuskan Sesi** (unlink) — yang terakhir menghapus device dari store
  lalu membangun ulang client, sehingga pairing dari awal bisa dilakukan tanpa restart aplikasi.

### Umpan balik langsung di WhatsApp

* **Centang biru**: pesan masuk di-acknowledge lewat `Client.MarkRead`.
* **Indikator typing**: `Client.SendChatPresence` (composing) menyala selama agent bekerja, di-refresh
  tiap 8 detik karena WhatsApp menurunkan status ini sendiri, lalu dimatikan setelah selesai.
* **Balasan cepat**: bubble `⏳ Sedang berpikir...` dikirim seketika, lalu **diedit** menjadi jawaban
  akhir (`Client.BuildEdit`) sehingga chat tetap satu gelembung. Bila WhatsApp menolak edit (window
  edit 20 menit), otomatis fallback ke pesan baru. Bila agent gagal, bubble yang sama diubah menjadi
  pesan error — tidak ada bubble "sedang berpikir" yang menggantung.
* **Progres nyata**: retry, fallback, dan pemakaian tool dari Agent Loop mengedit bubble yang sama
  (mis. `↻ Mencoba ulang (2/2)...`, `🔧 Menggunakan tool: current_time...`).

---

### Phase 6 — Tool System

* Abstraksi `Tool` (`id`, `name`, `description`, `inputSchema`, `permission`, `execute()`),
  `ToolResult`, dan `ToolCall` menjadi kontrak resmi. `ToolRegistry` menyimpan tool, jadi menambah
  tool berarti **mendaftarkannya** — Agent Loop tidak perlu diubah dan tidak menyebut tool mana pun
  secara langsung.
* Agent Loop sekarang menjalankan siklus penuh: model → `tool_calls` → registry → tool → pesan role
  `tool` → model lagi, sampai jawaban teks final. Siklus dibatasi `agent.maxToolIterations`
  (default 4, di-clamp maksimum 10) dan output tool dipotong 4.000 karakter agar konteks tidak jebol.
* Retry/fallback Phase 4 tetap berlaku untuk panggilan model lanjutan di dalam turn tool. Bila
  endpoint menolak field `tools` (HTTP 400), permintaan yang sama diulang **tanpa** tool alih-alih
  membakar seluruh fallback chain.
* Tool yang tidak terdaftar atau gagal dieksekusi dilaporkan kembali ke model sebagai hasil error
  (`TOOL_ERROR` di log) — turn tidak crash dan tidak pernah menggantung tanpa jawaban.
* State `CALLING_TOOL` / `WAITING_TOOL` dipakai sungguhan: bubble chat menampilkan kegiatan tool
  berjalan, dan **Developer → Tool Registry** memperlihatkan tool terdaftar beserta kelas
  permission-nya.
* Hanya pertanyaan pengguna dan jawaban akhir yang ditulis ke riwayat percakapan; pesan tool bersifat
  internal turn. Detail teknisnya tetap terekam di log aktivitas agent.

### Phase 7 — Workspace isolation & file tools

* Setiap agent punya ruang sendiri di `filesDir/workspaces/<agentId>` — app-internal storage, jadi
  aplikasi lain tidak bisa membacanya.
* Semua path dari model di-resolve lewat `Workspace.resolve()`: path hasil **canonical** wajib masih
  berada di dalam root. `..`, `sub/../../x`, dan symlink yang menunjuk keluar workspace ditolak;
  path absolut seperti `/etc/passwd` dibaca sebagai path relatif di dalam workspace, bukan path
  sistem.
* Delapan file tool: `list_files`, `read_file`, `write_file`, `append_file`, `move_path`,
  `copy_path`, `delete_path`, `make_directory`. Tujuh di antaranya `SAFE` karena sandbox-nya yang
  menjadi permission — jadi tidak ada tool file yang bisa keluar dari workspace walau model
  memintanya. `delete_path` adalah satu-satunya tool destruktif dan karena itu berkelas `CONFIRM`
  (wajib approval, lihat Phase 8).
* Penghapusan direktori yang masih berisi wajib memakai `recursive=true`, dan akar workspace tidak
  bisa dihapus sama sekali.
* Argumen tool dibaca oleh pembaca JSON kecil milik sendiri (`JsonArgs`) yang sadar string, jadi isi
  file yang mirip JSON tidak bisa mengelabui pembacaan argumen.
* Developer → Diagnostics menampilkan path workspace yang sedang dipakai, dan Tool Registry
  menampilkan seluruh tool terdaftar beserta kelas permission-nya.

### Phase 8 — Keamanan kontak, memori, approval, scheduler, subagent & media

**Kontrol akses kontak (prioritas keamanan).** `contact_rules` menyimpan whitelist/blacklist satu
baris per nomor; `normalizePhone()` menyamakan semua bentuk JID (`@s.whatsapp.net`, `@c.us`, device
suffix, `+`, spasi) sebelum dicocokkan. Saat whitelist mode aktif, hanya nomor terdaftar yang
lolos — pengecekan terjadi **sebelum** agent membaca pesan, jadi pesan yang diblokir tidak pernah
masuk history maupun memori. Pesan grup tetap diabaikan. Bisa dikelola dari Pengaturan atau lewat
`/whitelist` dan `/blacklist`.

**Memori jangka panjang.** Tiga lapisan dalam satu tabel `memory_items`: `EPISODIC` (ringkasan
compact & peristiwa penting), `KNOWLEDGE` (fakta yang diminta diingat), `LEARNING` (pelajaran dari
tool `reflect`, berstatus `candidate` → `active` setelah disetujui). Retrieval memakai RAG leksikal
milik sendiri (`MemoryRetriever`: stopwords ID+EN, skor overlap + boost kebaruan maksimum 25%) tanpa
layanan embedding eksternal. `ContextManager` menyuntikkan memori relevan + pelajaran aktif ke
system prompt per-turn (maks ~1.200 karakter per bagian), dan item yang dipakai ditandai
(`useCount`, `lastUsedAt`).

**Compact.** Otomatis saat jumlah pesan sesi melewati `maxContextMessages`, atau manual dengan
`/compact`. Pesan lama diringkas model (fallback: ringkasan ekstraktif bila provider tidak tersedia),
disimpan sebagai memori episodik **dan** pesan `SYSTEM` berawalan `Ringkasan sebelumnya (compact):`,
lalu baris mentahnya dipangkas. Jadi informasi tidak hilang meski konteksnya dipendekkan.

**Approval hanya untuk yang destruktif.** `ToolPermission` sekarang bertingkat: `SAFE` (otomatis),
`AUTO_SAFE` (read-only network: `web_search`, `web_fetch`), `CONFIRM` (destruktif: `delete_path`).
Tool `CONFIRM` tidak pernah ditawarkan ke model saat approval dimatikan; saat aktif, pemanggilan
pertama membuat record `approval_requests` (`appr-xxxxxxxx`, TTL 24 jam) dan model diminta memberi
tahu pengguna. Persetujuan datang lewat `/approve <id>` di chat atau tombol Setujui di Pengaturan —
bukan dialog desktop yang tidak ada di WhatsApp. Satu request hanya bisa diputuskan sekali.

**Scheduler.** `scheduled_tasks` menyimpan ekspresi `interval:<detik>` (minimum 60 detik,
maksimum 30 hari) + prompt yang dijalankan berkala. Ticker 60 detik mengeksekusi task yang jatuh
tempo lewat AgentLoop (jawabannya dikirim ke chat asal), mencatat `COMPLETED`/`FAILED` beserta
hasil/errornya, dan **menentukan jadwal berikutnya sebelum eksekusi** supaya eksekusi lambat tidak
memicu dobel. Tool `schedule_task` membuat task ini dari percakapan.

**Subagent latar belakang.** `delegate_task` membuat record `agent_tasks` dan langsung
mengembalikan id-nya — agent utama **tidak menunggu**, ia menjawab pengguna lebih dulu. Subagent
berjalan di coroutine sendiri dengan persona/model/toolset sendiri; setelah selesai, hasilnya
dikirim ke chat sebagai pesan baru. Status QUEUED/RUNNING/COMPLETED/FAILED terlihat di tab Tugas.

**Council & refleksi.** `council` menjalankan dua sudut pandang (pendukung vs kritikus) lalu satu
moderator yang menyintesis, dibatasi `withTimeout(120s)` dan panjang jawaban supaya tetap wajar di
WhatsApp. `reflect` menyimpan pelajaran sebagai kandidat, bukan langsung dipercaya.

**Media WhatsApp.** Sisi Go mengirim payload protobuf media ke Kotlin (`OnMedia`), lalu bridge
mengunduh + mendekripsi bytes-nya lewat `DownloadMedia`. Gambar/video dianalisis provider vision
(`OpenAiVisionProvider` atau `GeminiVisionProvider` native), dokumen teks dibaca langsung, dan
hasilnya digabung ke prompt percakapan; pengguna mendapat pesan "📎 Media diterima…" lebih dulu.
Pengiriman media keluar (gambar/dokumen/audio/video, termasuk voice note) tersedia di
`WaGatewayManager`.

### Priority 9 — Terminal bawaan (agent bisa curl/wget/bash/python)

* `run_command` menjalankan **satu perintah** lewat `/system/bin/sh` (shell + toybox bawaan
  Android) dengan `cwd` di dalam workspace agent; `terminal_info` melaporkan biner apa yang
  benar-benar ada di perangkat (`sh`, `bash`, `curl`, `wget`, `python3`, `git`, `gh`, `node`, …)
  supaya model tidak mengira ada Linux penuh. `curl`/`wget` tersedia di mayoritas perangkat;
  `python`/`git`/`gh` hanya bila pengguna memasangnya (mis. toolchain Termux).
* **Approval per perintah, bukan per tool.** `ShellPolicy` menilai perintah: `ls`, `cat`, `grep`,
  `curl`, `sh skrip.sh` jalan sendiri; pasang/hapus paket (`apt`/`pip`/`npm`/`pkg`/…), `rm`,
  `sudo`, `kill`, `git push`, dan menulis di luar workspace masuk kelas destruktif → agent parkir
  sebagai `PENDING_APPROVAL:<id>` dan pengguna menjawab `/approve <id>`. Setelah disetujui, bridge
  memanggil `executeApproved` sehingga perintah yang direview itulah yang dijalankan (tidak
  diminta approve dua kali).
* Layar **Terminal** memakai shell persisten dengan protokol marker (`exit code` + `$PWD`
  dikirim lewat baris tersembunyi), output dibatasi 1.500 baris dan dibatch ~80 ms — jadi `cd`
  tetap berlaku antar perintah, sama seperti terminal desktop.
* `ShellPolicy` adalah gerbang kejujuran, bukan sandbox: perintah bisa ditulis dengan cara yang
  tidak dikenali. Workspace tetap menjadi tempat kerja default dan path absolut di luar workspace
  ditolak oleh file tool.

### Priority 10 — Browser automation + serah terima captcha

* Engine: `BrowserEngine` (antarmuka) dengan implementasi `WebViewBrowserEngine` memakai WebView
  Android. GeckoView **belum** dipakai karena menuntut repository Maven Mozilla, toolchain Java 17
  untuk seluruh app, dan ±100 MB native library per ABI di atas gateway Go yang sudah ada;
  antarmukanya sengaja dibuat tipis agar GeckoView/driver lain bisa dipasang tanpa mengubah tool.
* Satu sesi WebView hidup dipakai bersama agent dan pengguna. **Sesi login persisten**: cookie
  disimpan oleh `CookieManager` aplikasi di direktori data privat app, bukan di dalam objek
  WebView — jadi login sekali (oleh `browser_login` maupun manual oleh pengguna di tab Browser)
  tetap berlaku untuk panggilan tool berikutnya, saat WebView dibuat ulang, bahkan setelah
  aplikasi ditutup dan dibuka lagi. Setiap `browser_type` dengan `submit` dan `browser_click`
  memanggil `CookieManager.flush()` supaya cookie login langsung tertulis ke disk. Yang
  mengakhirinya hanya `browser_logout` (atau tombol logout di UI), yang menghapus cookie, cache,
  form data, dan history.
* Alur: `browser_open` → `browser_read` (teks + daftar elemen `agx-N`) → `browser_click`
  / `browser_type` (dengan `submit`) → `browser_scroll` → `browser_screenshot`.
* **Login**: pengguna menyimpan akun per situs di Pengaturan → Browser (password dienkripsi
  `SecretCipher`), `browser_login` mengisi form login secara generik dan melaporkan jujur bila
  formnya tidak dikenali.
* **Captcha/2FA tidak pernah dipalsukan.** `browser_ask_user` (dan `browser_login` saat mendeteksi
  penanda captcha/verifikasi) menampilkan permintaan, mengirim pesan WhatsApp, lalu **menunggu**
  pengguna menekan “Selesai — lanjutkan agent” di tab Browser (timeout 6 menit).
* `browser_screenshot` menyimpan PNG di workspace `output/`, dan `send_file_to_chat` mengirimkannya
  ke chat (gambar sebagai foto, tipe lain sebagai dokumen) — jadi agent bisa memperlihatkan hasil
  kerjanya di WhatsApp.
* Browser automation **mati secara default**; menyalakannya di Pengaturan adalah bentuk persetujuan
  pengguna bahwa agent boleh mengendalikan sesi nyata.

### Keys pool (banyak API key) & unified search

* **Keys pool.** Pengaturan → Model & Provider kini punya field **“Keys Pool (opsional)”**: satu
  kunci per baris, dipakai bergiliran dengan API Key utama. Kunci awalnya dirotasi (round-robin)
  supaya beban tidak selalu jatuh ke kunci pertama, dan bila sebuah kunci gagal karena hal yang
  memang soal kunci — `401`/`402`/`403` (ditolak/tagihan) atau `429` (rate limit/quota) — percobaan
  berikutnya otomatis memakai kunci lain. Kegagalan lain (5xx, timeout, 400) **tidak** menghabiskan
  kunci: itu tetap ditangani retry/fallback Agent Loop seperti sebelumnya. Bila semua kunci habis,
  pesan error menyebut kunci ke berapa yang gagal (`key 2/3`) sehingga penyebabnya jelas di log.
  Kunci tambahan disimpan terenkripsi (`SecretCipher`) seperti kunci utama.
* **Unified search** (`search`, AUTO_SAFE, read-only, tanpa jaringan) mencari sekaligus di riwayat
  chat, memori jangka panjang, task terjadwal & sub-agent, file workspace, dan daftar tool. Ranking
  leksikal yang bisa dijelaskan: frasa yang cocok di judul > frasa di isi > kecocokan kata per kata,
  seri diputus oleh yang terbaru; query satu huruf sengaja tidak menghasilkan apa-apa. Tujuannya
  mengurangi round-trip tool: satu panggilan memberi konteks + id/path yang bisa langsung ditindak-
  lanjuti (`recall_memory`, `read_file`, atau tool terkait).

### Kesadaran waktu lokal (agent tahu "sekarang")

* Di awal setiap turn, bridge menambahkan blok **“Waktu sekarang”** ke system prompt: hari,
  tanggal, jam, offset UTC dan nama zona waktu perangkat (`TimeZone.getDefault()`), di-refresh
  tiap turn sehingga tidak pernah basi. Jadi agent tahu jam berapa sekarang **tanpa** harus
  memanggil tool lebih dulu, dan perhitungan jadwal/pengingat memakai hari yang benar.
* Tool `builtin.current_time` tetap terdaftar dan aktif secara default untuk pertanyaan eksplisit
  yang butuh presisi detik atau zona waktu lain (`timezone_offset_hours`).
* Blok waktu selalu disuntik, termasuk saat memori jangka panjang sedang dimatikan.

## Batasan yang diketahui

* Auto-reply hanya untuk chat pribadi (grup belum didukung).
* Dukungan 32-bit (`armeabi-v7a`) sudah di-build, tetapi hanya bisa dipastikan berjalan pada
  perangkat/emulator ARM 32-bit yang nyata — bukan pada perangkat arm64.
* Media masuk: gambar & video dianalisis lewat provider vision yang Anda konfigurasi; dokumen teks
  dibaca langsung; audio dicatat tetapi belum ditranskripsi (butuh provider STT). Bila API key vision
  belum diisi, agent mengatakannya terus terang alih-alih mengarang isi media.
* Tool bawaan saat ini: `current_time`, `search` (unified search lintas memori/chat/task/file/tool), 8 file tool (workspace-locked, `delete_path` = CONFIRM),
  `web_search`, `web_fetch`, `remember`, `recall_memory`, `delegate_task`, `reflect`, `council`,
  `schedule_task`, `run_command` + `terminal_info` (shell perangkat), `send_file_to_chat`, dan —
  bila browser automation diaktifkan — `browser_open`, `browser_read`, `browser_click`,
  `browser_type`, `browser_scroll`, `browser_screenshot`, `browser_login`, `browser_ask_user`,
  `browser_logout`. MCP, Linux sandbox/proot dan skill markdown belum ada — dokumen referensinya
  sudah disimpan di `DOC/reference/` untuk fase berikutnya.
* Scheduler berjalan dari proses aplikasi (ticker 60 detik). Bila sistem mematikan proses, task baru
  dieksekusi setelah aplikasi dibuka lagi; eksekusi latar penuh (WorkManager/foreground service)
  belum dipakai.
* `read_file` memotong isi pada 16.000 karakter dan memberi tahu model bahwa isinya dipotong;
  pembacaan bertahap (offset/limit) belum ada.
* Terminal memakai shell perangkat: tanpa toolchain tambahan, `python`/`node`/`git`/`gh` tidak
  tersedia. Linux penuh (proot/rootfs) belum ada — lihat `DOC/reference/linux-sandbox/`.
* Browser automation memakai WebView Android (bukan GeckoView) dan bergantung pada layout halaman;
  situs dengan anti-bot agresif bisa gagal — agent akan mengatakannya, bukan mengarang.
* Sesi login browser bertahan di cookie store WebView (disk, privat app) — belum diverifikasi di
  perangkat nyata melintasi restart aplikasi; situs yang sesinya berakhir di sisi server (atau
  memakai token yang tidak disimpan sebagai cookie) tetap akan meminta login lagi. `browser_logout`
  menghapus sesi dengan sengaja.
* Shell persisten di layar Terminal hidup selama proses aplikasi hidup; belum ada
  keepalive/foreground service khusus terminal (gateway service yang menjaga proses).
* `applicationId` masih memakai nilai bawaan template.
* `.env.example` masih berisi sisa template AI Studio dan tidak dipakai oleh build ini.

## Roadmap berikutnya

Sudah selesai pada iterasi ini: kontrol akses kontak, command chat, approval destruktif, memori
jangka panjang + compact, subagent latar belakang, council & refleksi, scheduler, web tools, dan
media WhatsApp masuk/keluar.

Urutan yang disarankan berikutnya: Observability lanjutan (kartu token & latensi di Beranda) →
Knowledge UI (skill markdown) → MCP connector → sandbox Linux/proot (kalau perlu `apt`/`pip`
nyata di perangkat).

Dokumen rencana lengkap ada di `DOC/`. Dossier referensi untuk sandbox Linux, terminal bawaan, dan
browser automation (transfer spec lengkap, bukan sekadar ringkasan) ada di `DOC/reference/`.
