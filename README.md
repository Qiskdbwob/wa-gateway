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
| Agent Core | `com.example.agent` | `AgentLoop` (state machine), `ModelErrorClassifier`, `ModelRouter` (retry + fallback + probe), `OpenAiCompatibleProvider`, `EchoTestProvider` |
| Persistensi | `com.example.agent.storage` | Room: `agent_sessions`, `agent_messages`, `agent_configs` + `SecretCipher` (API key) |
| Adapter | `WhatsAppAgentBridge` | Menjembatani gateway ⇄ agent loop, memuat/menyimpan konfigurasi |

**Alur pesan**

```
incoming  WhatsApp → Go event → WaGatewayManager → WhatsAppAgentBridge → AgentInput → AgentLoop
outgoing  AgentLoop → AgentResponse → WhatsAppChannelAdapter → WaGatewayManager.sendText → WhatsApp
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
| Klasifikasi error, retry + exponential backoff, fallback model, budget anti-infinite-loop | ✅ |
| Model probe (`1 + 1 =`) untuk cek availability & latency | ✅ |
| Provider OpenAI-compatible + Echo fallback offline | ✅ |
| Penyimpanan API key terenkripsi (Android Keystore, AES-256-GCM) | ✅ |
| UI: Beranda, Chat, Tugas, Memori, Pengaturan, Developer | ✅ |
| Auto-reply grup | ❌ (sengaja dinonaktifkan, hanya chat pribadi) |
| Pesan media (gambar/video/audio/dokumen) | ❌ |
| Tool system, workspace isolation, terminal, permission/approval | ❌ |
| Memory layer (episodic/knowledge/learning), search, context manager | ❌ |
| Subagent, council, multimodal delegation | ❌ |
| Task/job system, scheduler, MCP, knowledge UI, browser automation | ❌ |

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
* **Progres nyata**: retry & fallback dari Agent Loop mengedit bubble yang sama (mis.
  `↻ Mencoba ulang (2/2)...`). Tool call akan memakai saluran progres yang sama pada Phase 6.

---

## Batasan yang diketahui

* Auto-reply hanya untuk chat pribadi (grup belum didukung).
* Dukungan 32-bit (`armeabi-v7a`) sudah di-build, tetapi hanya bisa dipastikan berjalan pada
  perangkat/emulator ARM 32-bit yang nyata — bukan pada perangkat arm64.
* Hanya pesan teks; media diabaikan.
* Belum ada tool system/terminal/memory layer — lihat tabel status di atas. Saluran progres untuk
  tool call sudah tersedia (`AgentLoop.processInput(onProgress)`), tinggal dipakai saat Phase 6.
* `applicationId` masih memakai nilai bawaan template.
* `.env.example` masih berisi sisa template AI Studio dan tidak dipakai oleh build ini.

## Roadmap berikutnya

Urutan yang disarankan (mengikuti `DOC/context-2.md`): Tool System → Workspace isolation → Terminal →
Permission/Approval → Search → Memory layer → Learning → Context Manager → Subagent → Council →
Multimodal → Observability → Task/Scheduler → MCP → Knowledge UI → Browser → WhatsApp media.

Dokumen rencana lengkap ada di `DOC/`.
