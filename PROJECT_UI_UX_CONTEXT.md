# PROJECT_UI_UX_CONTEXT.md

> Dokumen ini adalah hasil audit repository (bukan hasil desain). Tujuannya memberi AI/agent lain
> konteks yang cukup untuk memahami aplikasi **sebelum** memberi rekomendasi desain aplikasi,
> layout, UI, dan UX.
>
> Dokumen ini **tidak berisi rekomendasi desain**. Tidak ada source code yang diubah oleh audit ini.

## 0. Cara membaca dokumen ini

Tiga tingkat kepastian dipakai secara konsisten:

| Label | Arti |
|---|---|
| **Fakta** | Dapat ditunjukkan langsung dari file/kelas/perilaku di repository. |
| **Interpretasi** | Kesimpulan fungsional/alur dari fakta; masuk akal tetapi tidak dinyatakan eksplisit sebagai "desain" di kode. |
| **Unknown / perlu dikonfirmasi** | Tidak dapat dipastikan dari repository. Tidak diisi dengan asumsi. |

Sumber audit (semua dibaca): `README.md`, seluruh `DOC/*.md`, `metadata.json`,
`app/build.gradle.kts`, `gradle/libs.versions.toml`, `app/src/main/AndroidManifest.xml`,
seluruh 10 layar Compose di `app/src/main/java/com/example/ui/`, `WaGatewayViewModel`,
`WaGatewayManager`, `WhatsAppAgentBridge`, `AgentLoop`, entity + DAO Room, tools, scheduler,
browser, terminal, MCP, dan unit test di `app/src/test/`.

Catatan penting soal dokumentasi: `README.md` dan `DOC/list-fitur.md` **tidak sepenuhnya
konsisten satu sama lain** (contoh: `DOC/list-fitur.md` bagian 10 masih menulis “Sandbox Linux
penuh (proot) dan MCP ❌” padahal MCP sudah diimplementasikan dan ada UI-nya di Pengaturan).
Bila dokumen dan kode berbeda, **kode dianggap sumber kebenaran** dalam dokumen ini.

---

## 1. Project Overview

**Nama project** *(Fakta)*
- `metadata.json`: `"name": "WA Gateway"`.
- `README.md` (judul): “WA Gateway — Personal AI Agent via WhatsApp”.
- `app/src/main/res/values/strings.xml`: `app_name = "WA Gateway"`.

**Tujuan aplikasi** *(Fakta, dari README + DOC/redesign.md + kode)*
Aplikasi Android native yang berfungsi sebagai **agent AI pribadi** dengan **WhatsApp sebagai
salah satu channel** (bukan satu-satunya: ada channel “chat langsung” di dalam aplikasi).
Agent dapat memanggil tool (file, shell, browser, web, memori, sub-agent, MCP), menyimpan memori
jangka panjang, menjalankan tugas terjadwal, dan mengirim balasan ke WhatsApp.

**Masalah yang ingin diselesaikan** *(Interpretasi dari README + `DOC/list_fitur_app.md`)*
- Menjalankan agent AI pribadi **on-device** tanpa Node.js, Termux, root, atau accessibility
  service (WhatsApp via `whatsmeow` yang di-bind dengan `gomobile`).
- Memberi agent “tangan dan kaki” (tool eksekusi) plus memori persisten, sehingga bisa bekerja
  di luar sekadar membalas chat.
- Menjaga keamanan: whitelist/blacklist kontak, approval untuk aksi destruktif, API key
  terenkripsi.

**Jenis aplikasi** *(Fakta)*
- Aplikasi Android native, single-Activity, UI penuh Jetpack Compose (Material 3).
- Tidak ada web/mobile tambahan; tidak ada backend server milik project (backend = proses lokal
  di perangkat + provider LLM eksternal yang dikonfigurasi pengguna).

**Platform** *(Fakta — `app/build.gradle.kts`, `AndroidManifest.xml`)*
- `minSdk = 24`, `targetSdk = 36`, `compileSdk = 36` (`minorApiLevel = 1`).
- `applicationId = "com.aistudio.wagateway.qnzr"` (masih nilai template; README menandai ini).
- ABI: `arm64-v8a`, `armeabi-v7a`, `x86_64` (`ndk.abiFilters`).
- `enableEdgeToEdge()` dipakai di `MainActivity`; `android:windowSoftInputMode="adjustResize"`.
- Permission: `INTERNET`, `ACCESS_NETWORK_STATE`, `FOREGROUND_SERVICE`,
  `FOREGROUND_SERVICE_DATA_SYNC`, `POST_NOTIFICATIONS`, `WAKE_LOCK`.

**Teknologi utama** *(Fakta — `gradle/libs.versions.toml` + `app/build.gradle.kts`)*
- Kotlin 2.2.10, Jetpack Compose (BOM `2024.09.00`), Material 3, `material-icons-extended`.
- Room 2.7.0 (KSP), WorkManager 2.10.0, OkHttp 4.10.0 (HTTP call ke provider LLM), ZXing 3.5.3
  (render QR), Kotlin Coroutines.
- Go + `whatsmeow` dibangun sebagai AAR (`app/libs/wagateway.aar`, tidak di-commit) → dipakai lewat
  JNI/gomobile. Build AAR dilakukan di CI (`gateway-aar.yml`).
- Gradle 9.3.1, AGP 9.1.1. `compileOptions` memakai `JavaVersion.VERSION_11` (README menyebut
  JDK 17 — **selisih dokumen vs build file**, lihat bagian Confidence).
- **Tidak dipakai meskipun ada di version catalog**: `navigation-compose`, Coil, Retrofit, Moshi,
  DataStore, Firebase, CameraX, Play Services. Tidak ada `navigation-compose` di dependencies →
  navigasi tidak memakai NavHost.
- **Tidak ada design system eksternal**: tidak ada shadcn/UI-kit; komponen dibuat sendiri dari
  Material 3.

**Arsitektur utama** *(Fakta, dari README + struktur package)*

```
WhatsApp ⇄ Go gateway (whatsmeow) ⇄ Kotlin Bridge (WaGatewayManager) ⇄ Agent Loop ⇄ Model Provider
                                            │                              │
                                    foreground service            Room (sessions, messages, config)
```

Lapisan (nama package sebenarnya):
| Lapisan | Lokasi |
|---|---|
| Transport WhatsApp (Go) | `go-wagateway/` → `wagateway.aar` |
| Binding + gateway Kotlin | `com.example.wagateway` (`WaGatewayManager`, `WaGatewayService`, `WaGatewayViewModel`) |
| Agent core | `com.example.agent` (`loop`, `router`, `provider`, `tool`, `memory`, `scheduler`, `browser`, `terminal`, `skills`, `mcp`, `search`, `subagent`, `approval`, `chat`) |
| Persistensi | `com.example.agent.storage` (Room: 9 tabel) |
| Workspace (sandbox file) | `com.example.agent.workspace` |
| UI | `com.example.ui` (`MainAppScreen`, `navigation`, `theme`, `components`, 9 layar) |

---

## 2. Core User Purpose

*(Interpretasi dari fitur yang ada; bahasa sengaja konkret)*

Pengguna sebenarnya ingin:

1. **Menautkan akun WhatsApp-nya sekali** ke aplikasi (QR atau pairing code), lalu membiarkan
   aplikasi tetap tersambung.
2. **Menjadikan agent sebagai “orang yang menjawab chat”-nya**: pesan WhatsApp yang masuk dari
   nomor yang diizinkan otomatis dijawab oleh model LLM, dengan memori, tool, dan kemampuan
   bertindak (cari web, baca/tulis file, jalankan shell, buka browser).
3. **Mengobrol langsung dengan agent-nya** di dalam aplikasi (tanpa lewat WhatsApp), sebagai
   workspace utama untuk memberi tugas.
4. **Mengatur apa yang boleh dilakukan agent**: siapa yang boleh bicara (whitelist/blacklist),
   aksi destruktif mana yang butuh approval, tool mana yang aktif (terminal, browser).
5. **Melihat dan mengendalikan pekerjaan agent**: apa yang sedang dikerjakan, apa yang gagal,
   apa yang dijadwalkan, apa yang dipelajari, apa yang disimpan di memori.
6. **Mengontrol biaya/kualitas model**: base URL, API key (termasuk beberapa key yang dirotasi),
   model ID, prompt/persona, dan model vision terpisah untuk media.

Kalimat paling ringkas: **“satu agent AI pribadi yang hidup di HP, bisa dihubungi lewat WhatsApp,
dan bisa diberi tugas dari dalam aplikasi.”**

---

## 3. Users

**Jenis pengguna yang dapat diidentifikasi** *(Fakta: dari fitur, teks UI, dan asumsi teknis yang
harus dipenuhi pengguna)*

| Tipe | Bukti di repository | Kebutuhan utama di UI |
|---|---|---|
| **Pemilik perangkat / operator tunggal** (*single user*) | Tidak ada mekanisme login, tidak ada multi-akun, tidak ada tabel user. Semua data lokal. `AgentConfigEntity` hanya punya satu baris `default_config`. | Identitas tunggal; UI “dashboard milik saya”. |
| **Pengguna teknis (power user / developer)** | Layar Developer & Debug, Tool Registry, raw log, probe model, metrik token/latensi, terminal, konfigurasi MCP, base URL manual, keys pool. `DOC/redesign.md` menyebut “developer debugging console”. | Akses cepat ke diagnostik, tapi tidak boleh mendominasi layar utama. |
| **Penulis pesan WhatsApp (pihak ketiga)** | Whitelist/blacklist per nomor, `/help`, `/status`, `/approve` dikirim dari WhatsApp; agent membalas di WhatsApp. | Tidak pernah melihat UI aplikasi. Interaksi mereka adalah **teks chat**, bukan layar. |
| **Anak-agent / sub-agent** | `SubAgentSpec` (nama, persona, model, tools), tabel `agent_tasks`. | Bukan pengguna manusia; muncul sebagai baris status “Sub-agent”. |

**Yang tidak ditemukan** *(Fakta negatif — jangan mengarang persona dari sini)*
- Tidak ada tim/organisasi, role, atau permission berbasis akun.
- Tidak ada onboarding/tutorial, tidak ada layar profil pengguna, tidak ada avatar pengguna.
- Tidak ada lokalisasi (hanya `app_name` di `strings.xml`; seluruh label UI hardcoded di Kotlin).
  Bahasa UI mayoritas Bahasa Indonesia, tetapi **tidak konsisten** (lihat bagian 14).
- Persona umur/pekerjaan pengguna: **Unknown / perlu dikonfirmasi**.

---

## 4. Feature Inventory

Status: **Implemented** = ada kode + ada jalur UI atau jalur chat yang nyata;
**Partially implemented** = berjalan tetapi ada bagian yang belum diekspos/tidak lengkap;
**Planned / TODO** = hanya dokumen referensi/roadmap, tidak ada implementasi UI.

### 4.1 Dialog agent & channel WhatsApp

| Fitur | Tujuan | Akses | Input | Output | State terkait | Dependensi | Status |
|---|---|---|---|---|---|---|---|
| Chat langsung dengan agent | Percakapan utama pengguna ↔ agent di dalam aplikasi | Tab **Chat** | Teks (max 4 baris), tap chip saran | Bubble user/agent, indikator “sedang berpikir”, salin teks | `selectedSessionId`, `selectedSessionMessages`, `chatInputText`, `isTestingAgent`, `agentState`, `agentCurrentActivity` | `WhatsAppAgentBridge.directChat()`, Room, provider LLM | Implemented |
| Sesi percakapan per-channel | Memisahkan history per lawan chat | Tab **Chat** (chip horizontal) | Tap chip, tombol “+” sesi baru, menu ⋮ | Ganti sesi, bersihkan riwayat, hapus sesi | `sessions` (Room, `ORDER BY updatedAt DESC`), `selectedSessionId` | Room `agent_sessions` | Implemented |
| Auto-reply WhatsApp | Agent membalas chat WhatsApp masuk | Home (switch) + Pengaturan | Switch on/off | Balasan WhatsApp: ack `⏳` → diedit jadi jawaban akhir, typing indicator, centang biru | `isAgentAutoReply` | `WaGatewayManager`, `AgentLoop`, `WhatsAppChannelAdapter` | Implemented |
| QR / pairing code login | Menautkan perangkat WhatsApp | Home → kartu WhatsApp → **Gateway** | Scan QR / nomor telepon | QR image, kode 8 karakter (tap untuk salin), status koneksi | `qrCode`, `pairingCode`, `pairingMode`, `pairingPhone`, `isLoggedIn`, `isConnected`, `connectionStatus` | Go `connect()/PairPhone()`, `QrCodeUtil` | Implemented |
| Auto-reconnect sesi tersimpan | Tidak perlu scan QR tiap buka app | Otomatis saat app dibuka | — | Log “Sesi WhatsApp tersimpan ditemukan — menyambung ulang otomatis” | `isLoggedIn` (`Client.HasSession()`) | `WaGatewayManager.initClient()` | Implemented |
| Putuskan sesi (unlink) | Menautkan ulang dari awal | Gateway | Tap “Putuskan Sesi” | Sesi dihapus dari store, client dibangun ulang | `resetSession()` | Go `resetSession()` | Implemented |
| Foreground service + notifikasi | Koneksi tetap hidup saat app ditutup | Gateway (switch) | Switch on/off | Notifikasi ongoing berisi status | `isServiceRunning` | `WaGatewayService`, permission `POST_NOTIFICATIONS` (API 33+) | Implemented |
| Kirim pesan WhatsApp manual (test) | Uji kirim tanpa lewat agent | Gateway | Nomor tujuan + teks | Feedback sukses/gagal | `targetPhone`, `messageText`, `isSending`, `sendFeedback` | `sendText()` | Implemented (sengaja ditaruh di Gateway, bukan Home) |
| Log gateway mentah | Diagnostik transport | Gateway | — | Daftar baris log monospace | `logs` | `WaGatewayManager.addLog` | Implemented |
| Whitelist/blacklist kontak | Membatasi siapa yang dilayani agent | Pengaturan → Keamanan & Akses; juga chat `/whitelist`, `/blacklist` | Nomor + label | Daftar rule, pesan feedback | `whitelistMode`, `whitelistContacts`, `blacklistContacts`, `contactNumberInput`, `contactLabelInput` | `ContactAccessRepository`, tabel `contact_rules` | Implemented |
| Command chat (`/help /status /whitelist /blacklist /approve /reject /compact /remember /learning /terminal /browser`) | Kontrol agent dari WhatsApp | Chat WhatsApp (bukan UI aplikasi) | Perintah teks | Balasan teks perintah | `ChatCommandHandler` | `ApprovalCoordinator`, `MemoryRepository`, `CompactManager`, `SchedulerEngine` | Implemented |

### 4.2 Konfigurasi model & provider

| Fitur | Tujuan | Akses | Input | Output | State | Dependensi | Status |
|---|---|---|---|---|---|---|---|
| Base URL + API Key + Model ID | Menunjuk ke provider apa pun yang OpenAI-compatible | Pengaturan → **Model & Provider** | Teks (API key bertoggle lihat/sembunyikan) | Konfigurasi aktif di Home & Developer | `agentBaseUrl`, `agentApiKey`, `agentModelId` | `AgentConfigEntity`, `OpenAiCompatibleProvider` | Implemented (persist hanya saat tombol Simpan ditekan) |
| Keys Pool | Rotasi beberapa API key saat rate limit/quota | Pengaturan → Model & Provider | Satu key per baris (multiline) | Info “Keys pool aktif: N kunci” | `agentApiKeyPool`, `apiKeyPoolSize` | `ProviderKeyPool`, `SecretCipher` | Implemented |
| Echo fallback offline | Agent tetap membalas tanpa jaringan/key | Pengaturan (switch) + Home (label) | Switch | Balasan dari engine lokal | `useEchoFallback` | `EchoTestProvider`, `ModelRouter` | Implemented |
| System Prompt / persona | Mengatur gaya & aturan agent | Pengaturan → **Agent Persona & Prompt** | Textarea 3–6 baris | Prompt aktif (juga ditampilkan read-only di Memori → Knowledge) | `agentSystemPrompt` | Room config | Implemented |
| Simpan konfigurasi ke DB | Persistensi setelan | Pengaturan (satu tombol “Simpan Konfigurasi ke Database”, berada di kartu Persona) | Tap | Feedback banner | `sendFeedback` | `persistConfig()` | Implemented (lihat masalah UX #14.6) |
| Vision (media) base URL/key/model + toggle Gemini native | Menganalisis gambar/video yang masuk | Pengaturan → **Vision (Analisis Media)** | 3 field + switch + tombol simpan sendiri | Konfigurasi vision | `visionBaseUrl`, `visionApiKey`, `visionModelId`, `visionGeminiNative` | `OpenAiVisionProvider` / `GeminiVisionProvider` | Implemented |
| Probe model (“1 + 1 =”) | Cek ketersediaan & latensi model | Pengaturan → Developer & Diagnostik → **Developer & Debug** | Tap | Hasil ✅/❌ + ms | `probeResult`, `isProbing` | `ModelRouter.probeModel` | Implemented |
| Metrik model (latensi, token, sukses/gagal, 50 panggilan terakhir) | Observabilitas biaya/performa | Developer & Debug | — | Ringkasan + 5 baris terakhir | `modelMetrics` | `AgentLoop.ModelCallMetric` | Implemented |
| Fallback chain multi-model / multi-provider combo | Rantai fallback yang bisa diatur pengguna | — | — | — | `ModelRouter.setTargets` (di `updateModelRouter()` hard-coded: primary + echo) | — | **Partially** — tidak ada UI; hanya primary + Echo. `DOC/list-fitur.md` menandai 🟡 |
| Provider Anthropic native | Alternatif provider | — | — | — | — | — | **Planned/TODO** (dinyatakan ❌ di DOC, tidak ada kode) |

### 4.3 Tool & kemampuan agent (backend yang memengaruhi UI)

Seluruh tool di bawah terdaftar di `ToolRegistry` dan **ditampilkan sebagai daftar read-only** di
Developer → Tool Registry (nama + deskripsi + kelas permission). Tidak ada UI untuk
mengaktifkan/mematikan tool individual.

*(Fakta — nama tool & permission dari kode)*

| Grup | Tool | Permission | Aktif bila |
|---|---|---|---|
| Waktu | `current_time` | SAFE | selalu |
| File (workspace-locked) | `list_files`, `read_file`, `write_file`, `append_file`, `move_path`, `copy_path`, `make_directory` | SAFE | selalu (butuh `Workspace`) |
| File destruktif | `delete_path` | CONFIRM | selalu terdaftar, hanya ditawarkan bila approval aktif |
| Web | `web_search`, `web_fetch` | AUTO_SAFE | selalu |
| Memori | `remember`, `recall_memory` | SAFE | selalu |
| Unified search | `search` (scope: `all/memory/chat/tasks/files/tools`) | AUTO_SAFE | selalu (offline, tanpa jaringan) |
| Skill markdown | `list_skills`, `read_skill`, `save_skill` | SAFE | selalu |
| Multi-agent | `delegate_task` | SAFE | selalu |
| Refleksi & debat | `reflect`, `council` | SAFE | selalu |
| Scheduler | `schedule_task` | SAFE | selalu |
| Terminal | `run_command`, `terminal_info` | AUTO_SAFE + approval per-perintah (`ShellPolicy`) | toggle Pengaturan → Terminal (default **on**) |
| Media keluar | `send_file_to_chat` | AUTO_SAFE | selalu |
| Browser | `browser_open/read/click/type/scroll/screenshot/login/ask_user/logout` | AUTO_SAFE | toggle Pengaturan → Browser (default **off**) |
| MCP | `mcp__<server>__<tool>` | (ikut server) | saat server MCP aktif |
| Chat media (backend) | `WaGatewayManager.sendImage/sendDocument/sendAudio` | — | **UI belum memakai** |
| **Belum ada** | Linux sandbox penuh (proot/rootfs); transkripsi audio (STT); Resources/Prompts MCP | — | **Planned/TODO** (dossier di `DOC/reference/`) |

### 4.4 Memori, learning, skill, task

| Fitur | Akses UI | Aksi yang tersedia | Status |
|---|---|---|---|
| Sessions (riwayat percakapan) | Memori → tab **Sessions** | Buka Chat (pindah tab + pilih sesi), Bersihkan riwayat (dialog konfirmasi), Hapus sesi (dialog konfirmasi) | Implemented |
| Episodic memory | Memori → **Episodic Memory** | Hapus per item | Implemented (tidak bisa ditambah manual) |
| Knowledge memory | Memori → **Knowledge** | Tambah fakta + penghitung karakter (cap lunak 4.000), Simpan, Hapus; daftar fakta; System Prompt read-only | Implemented |
| Learning | Memori → **Learning** | Aktifkan (promote candidate→active), Tolak, Ikon hapus (= reject juga) | Implemented |
| Skills | Memori → **Skills** | Hanya daftar (nama, deskripsi, path); `LaunchedEffect(Unit) { refreshSkills() }` saat tab dibuka | **Partially** (tidak bisa dibuka/diedit/dihapus dari UI) |
| Autonomy runtime status | Memori → Skills (bagian bawah) | Read-only status: WhatsApp Gateway, Room, Model Provider, Echo Fallback | Implemented |
| Tugas terjadwal (cron `interval:<detik>`) | Tugas → filter **Scheduled** | Jalankan sekarang, Aktifkan/Nonaktifkan, Hapus | Implemented (pembuatan hanya dari chat/tool `schedule_task`, tidak ada form di UI) |
| Sub-agent latar belakang | Tugas (baris `Sub-agent: <nama>`) | Hanya lihat detail (bottom sheet) | **Partially** (tidak ada cancel meski `STATUS_CANCELLED` dideklarasikan) |
| Refleksi otomatis terjadwal | Pengaturan → Memori Jangka Panjang | Toggle, interval jam (−/+), “Jalankan refleksi sekarang” | Implemented |
| Auto compact | Pengaturan → Memori Jangka Panjang | Toggle + batas konteks (−/+ per 10 pesan) | Implemented |
| MCP connector | Pengaturan → **MCP Connector** | Tambah server (nama/URL/header), aktif/nonaktif, hapus, “Muat Ulang Tool” | Implemented |

### 4.5 Terminal, browser, media

| Fitur | Akses UI | Aksi | Status |
|---|---|---|---|
| Terminal interaktif di aplikasi | Pengaturan → Terminal → **Buka Terminal** (sub-screen) | Input perintah, kirim, bersihkan, hentikan shell, pindai toolchain, 2 chip perintah cepat (`pwd && ls -la`, `curl -I`) | Implemented |
| Terminal untuk agent | Toggle di Pengaturan (default on) | Agent menjalankan `curl/wget/bash/python` dengan approval per perintah | Implemented |
| Browser automation | Pengaturan → Browser Automation (toggle default **off**, User-Agent kustom, akun per situs) + sub-screen **Browser** | Tampilkan sesi WebView live, refresh, logout/bersihkan sesi | Implemented |
| Serah terima captcha/2FA | Banner kuning “🙋 Agent menunggu Anda” di layar Browser | “Selesai — lanjutkan agent”, “Batalkan” (timeout 6 menit) | Implemented |
| Media masuk (gambar/video/dokumen/audio) | Tidak ada layar media; teks “📎 Media diterima…” masuk ke percakapan WhatsApp | — | **Partially** (audio belum ditranskripsi; tidak ada UI daftar media) |
| Media keluar dari UI | — | — | **Planned/Partially** — API siap (`sendImage/sendDocument/sendAudio`), layar Chat belum punya tombol lampiran |

### 4.6 Developer / observability

| Fitur | Akses | Isi | Status |
|---|---|---|---|
| Developer & Debug | Pengaturan (kartu) → sub-screen | Ringkasan diagnostik (state loop, model, echo, WhatsApp, storage driver, path workspace), Interactive Test Console, Tool Registry, raw log aktivitas (kartu 240 dp) | Implemented |
| Interactive Test Console | Developer | Test Prompt + tombol “Uji Agent Loop Sekarang” + hasil + salin | Implemented |
| Raw log agent | Developer + Home (3 baris terakhir) | `agentLogs` (maks 100 baris terakhir) | Implemented |

---

## 5. Screen Inventory

*(Fakta — nama composable & file sebenarnya. Semua layar ada di
`app/src/main/java/com/example/ui/`)*

### 5.1 `MainAppScreen` — shell navigasi
- **File**: `ui/MainAppScreen.kt`.
- **Tujuan**: menyediakan `NavigationBar` 5 tab + perpindahan ke 4 sub-screen; menangani tombol back sistem.
- **Elemen**: `Scaffold`, `NavigationBar`, `NavigationBarItem`, `Box` dengan `imePadding()`.
- **State**: `currentTab` (`MainTab`), `currentSubScreen` (`SubScreen`) — **`remember`, bukan
  `rememberSaveable`**.
- **Catatan**: saat sub-screen aktif, `Scaffold` + bottom bar **tidak** dirender sama sekali
  (layar penuh menggantikan seluruh shell).

### 5.2 `HomeScreen` — “Beranda”
- **File**: `ui/home/HomeScreen.kt` (598 baris).
- **Tujuan**: dashboard agent ringkas.
- **Entry point**: tab Beranda (default saat app dibuka). **Exit**: Chat / Tugas / Pengaturan / Gateway.
- **Elemen utama (urut)**: header “Personal AI Agent” + avatar bulat (ikon SmartToy, clickable →
  Chat) → kartu **Agent Core** (badge status + switch “Auto-Reply Pesan WhatsApp”) → kartu
  **WhatsApp Gateway** (badge koneksi, clickable → Gateway) → kartu **Model AI** (clickable →
  Pengaturan) → kartu **Keamanan Agent** (teks whitelist + ringkasan “Sub-agent aktif / Task
  terjadwal / Menunggu approval”, clickable → Pengaturan) → section **Task Aktif** (aksi “Lihat
  Semua” → Tugas) → section **Aktivitas Terkini** (aksi “Mulai Chat” → Chat, maks 3 log).
- **Data**: `agentState`, `isAgentAutoReply`, `isConnected`, `connectionStatus`, `agentModelId`,
  `agentApiKey`, `useEchoFallback`, `agentLogs`, `sessions`, `whitelistMode`, `pendingApprovals`,
  `subAgentTasks`, `scheduledTasks`.
- **Empty state**: “Belum ada task aktif” + “Belum ada aktivitas tercatat”.
- **Loading state**: tidak ada (semua dari StateFlow lokal).
- **Error state**: tidak ada di layar ini (hanya badge status).
- **Permission state**: tidak ada.
- **Sudah diimplementasikan**: ya.

### 5.3 `ChatScreen` — “Chat”
- **File**: `ui/chat/ChatScreen.kt` (553 baris).
- **Tujuan**: percakapan utama dengan agent + pemilih sesi.
- **Entry point**: tab Chat; dari Home (avatar & “Mulai Chat”); dari Memori → Sessions → “Buka Chat”.
- **Struktur**: (1) bar sesi (`Surface`): judul “Percakapan”, tombol “+” sesi baru, menu ⋮
  (Bersihkan Riwayat / Hapus Sesi Ini), `LazyRow` `FilterChip` termasuk chip tetap
  **“Personal Agent”** (artinya `selectedSessionId == null`); (2) daftar pesan `LazyColumn` +
  `ChatMessageBubble`; (3) `AgentThinkingBubble` saat agent sibuk; (4) bar input `OutlinedTextField`
  + tombol kirim bulat 46 dp.
- **Empty state**: `EmptyStateCard` “Belum ada percakapan” + 3 `SuggestionChip` yang **langsung
  mengirim** pertanyaan bila ditekan.
- **Loading state**: spinner kecil di tombol kirim + bubble “Agent sedang berpikir…” (opacity
  berdenyut) saat `isTestingAgent || agentState.isBusy`.
- **Error state**: error dari `directChat` muncul sebagai banner feedback di **Pengaturan**
  (`sendFeedback`), bukan di layar Chat (lihat 14.5).
- **Catatan penting**: layar ini juga menampilkan sesi WhatsApp (karena sesi dipisah per
  `conversationId`, dan JID WhatsApp dipakai sebagai judul chip).
- **Sudah diimplementasikan**: ya.

### 5.4 `TasksScreen` — “Tugas”
- **File**: `ui/tasks/TasksScreen.kt` (692 baris).
- **Tujuan**: memantau pekerjaan agent (live + riwayat + sub-agent + terjadwal + error).
- **Entry point**: tab Tugas; dari Home section “Task Aktif”.
- **Elemen**: header “Pekerjaan Agent” → baris `FilterChip` filter (`Semua, Running, Waiting,
  Scheduled, Completed, Failed`) → kartu kontrol task terjadwal (hanya bila filter = Scheduled)
  → `LazyColumn` kartu task → **`ModalBottomSheet`** detail task.
- **Data**: `agentState`, `agentLogs`, `agentLastError`, `agentModelId`, `sessions`,
  `subAgentTasks`, `scheduledTasks`.
- **Penting**: daftar task **disintesis di UI** (`AgentTaskRecord` didefinisikan di file ini) dari
  state lain. Tidak ada tabel “task” generik; tidak ada data dummy.
- **Empty state**: berbeda per filter (teks spesifik, mis. “Belum ada task terjadwal.” dengan saran
  membuat jadwal lewat chat).
- **Loading/Error state**: tidak ada indikator loading; error ditampilkan sebagai entri
  “Eksekusi gagal” + blok “Pesan Error” di sheet.
- **Sudah diimplementasikan**: ya.

### 5.5 `MemoryScreen` — “Memori”
- **File**: `ui/memory/MemoryScreen.kt` (741 baris).
- **Tujuan**: manajemen memori & persistensi.
- **Entry point**: tab Memori.
- **Elemen**: header “Memory & Persistence” → kartu ringkasan “SQLite Room Database” (jumlah sesi &
  total pesan) → `ScrollableTabRow` 5 kategori (**Sessions, Episodic Memory, Knowledge, Skills,
  Learning**) → konten per tab (masing-masing `LazyColumn` kecuali Knowledge).
- **Knowledge tab**: kartu input (dipin di atas) + penghitung karakter (cap 4.000, merah bila
  lewat) + tombol “Simpan Memori”; di bawahnya System Prompt read-only + daftar fakta.
- **Learning tab**: kartu per item dengan badge tipe/status + “Aktifkan”/“Tolak” untuk kandidat.
- **Empty state**: satu per tab (mis. “Belum ada memori episodik”, “Belum ada skill markdown”).
- **Dialog**: `AlertDialog` “Hapus Sesi Percakapan?” dan “Bersihkan Riwayat Pesan?”.
- **Sudah diimplementasikan**: ya.

### 5.6 `SettingsScreen` — “Pengaturan”
- **File**: `ui/settings/SettingsScreen.kt` (1209 baris).
- **Tujuan**: seluruh konfigurasi.
- **Entry point**: tab Pengaturan; dari Home (kartu Model AI & Keamanan Agent).
- **Elemen**: satu `Column` `verticalScroll` + banner feedback (`sendFeedback`, dapat ditutup “×”) +
  10 section `SectionHeader`: **Model & Provider**, **Agent Persona & Prompt**, **Saluran
  Terhubung** (tile → Gateway), **Keamanan & Akses**, **Memori Jangka Panjang**, **Vision
  (Analisis Media)**, **Terminal (Tool Agent)**, **Browser Automation**, **MCP Connector**,
  **Developer & Diagnostik** (tile → Developer).
- **Keamanan & Akses** juga memuat daftar **pending approval** dengan tombol “Setujui”/“Tolak”
  dan daftar rule whitelist/blacklist dengan tombol “×”.
- **Browser Automation** memuat daftar akun situs tersimpan, form nama situs/URL login/username/
  password (toggle visibility), tombol “Tambah / Perbarui Akun”, User-Agent, tombol “Buka Browser”.
- **Sudah diimplementasikan**: ya (satu halaman sangat panjang; lihat 14.6).

### 5.7 `WhatsAppGatewayScreen` — sub-screen “Gateway”
- **File**: `ui/gateway/WhatsAppGatewayScreen.kt` (571 baris). `Scaffold` + `TopAppBar` (back +
  badge koneksi).
- **Isi**: (1) Koneksi & Background Service (status tertaut, tombol “Hubungkan (Ulang)” /
  “Putuskan”, “Putuskan Sesi” bila tertaut, switch Foreground Service dengan permintaan
  permission notifikasi API 33+); (2) **Tautkan Perangkat WhatsApp** — hanya tampil bila
  `!isLoggedIn` — `TabRow` QR Code / Pairing Code (QR 220 dp di atas latar putih; mode kode:
  field nomor + tombol minta kode + kartu kode monospace yang bisa di-tap untuk salin);
  (3) Kirim Pesan WhatsApp (Test Manual); (4) Log WhatsApp Gateway (kartu gelap 200 dp).
- **Empty state**: “Tekan 'Hubungkan' di atas untuk memuat QR Code”, “Belum ada log gateway.”.
- **Error state**: via `sendFeedback` (+ status teks dari Go, mis. “Connection Error: …”).
- **Sudah diimplementasikan**: ya.

### 5.8 `DeveloperDebugScreen` — sub-screen “Developer & Debug”
- **File**: `ui/debug/DeveloperDebugScreen.kt` (477 baris).
- **Isi**: kartu diagnostik (badge state, model, Echo, WhatsApp, Storage driver “Room SQLite
  (agent_database)”, path workspace monospace) → **Interactive Test Console** (Probe Model,
  ringkasan metrik + 5 baris, Test Prompt, tombol Uji, hasil + salin) → **Tool Registry** (daftar
  tool + permission berwarna) → **Log Aktivitas Agent Loop** (kartu gelap 240 dp, `LazyColumn`,
  11 sp monospace).
- **Sudah diimplementasikan**: ya.

### 5.9 `TerminalScreen` — sub-screen “Terminal”
- **File**: `ui/terminal/TerminalScreen.kt` (252 baris).
- **Isi**: `TopAppBar` (subjudul “Menjalankan perintah…” / “Siap — exit terakhir: N”, aksi Pindai
  toolchain / Bersihkan / Hentikan shell) → kartu `cwd` + daftar biner tersedia/tidak tersedia →
  layar output gelap (placeholder “Ketik perintah untuk memulai.”) → baris input + tombol kirim →
  chip perintah cepat + penghitung baris.
- **State**: `terminalState` (`TerminalState`: lines, cwd, busy, started, lastExitCode, lastCommand),
  `terminalCapabilities`, `terminalInput`.
- **Empty state**: ya. **Loading**: spinner kecil saat `state.busy`.
- **Sudah diimplementasikan**: ya.

### 5.10 `BrowserScreen` — sub-screen “Browser Agent”
- **File**: `ui/browser/BrowserScreen.kt` (208 baris).
- **Isi**: `TopAppBar` (subjudul “Automation aktif/nonaktif”, aksi Refresh, Logout/Bersihkan sesi) →
  banner kuning `🙋 Agent menunggu Anda` (bila ada `browserPendingUserAction`) dengan tombol
  “Selesai — lanjutkan agent” / “Batalkan” → area WebView (`AndroidView` + `FrameLayout`) → catatan
  “Sesi ini dipakai bersama oleh agent…”.
- **Empty state**: “Belum ada halaman yang dibuka… (atau pakai /browser <url> di chat)”.
- **Tidak ada**: address bar, tombol back/forward, tombol “Login manual” (padahal pesan error
  `browser_login` menyarankan tombol itu — lihat 14.9).
- **Sudah diimplementasikan**: ya.

### 5.11 Overlay/dialog/bottom sheet yang benar-benar ada
| Surface | File | Pemicu | Aksi |
|---|---|---|---|
| `ModalBottomSheet` “Detail Task” | `TasksScreen.kt` | Tap kartu task | Tutup (×); menampilkan judul, badge status, prompt, Agent/Model/Waktu, chips “Tools Terlibat”, blok Hasil, blok Error |
| `AlertDialog` “Hapus Sesi Percakapan?” | `MemoryScreen.kt` | Ikon hapus di tab Sessions | Hapus (merah) / Batal |
| `AlertDialog` “Bersihkan Riwayat Pesan?” | `MemoryScreen.kt` | Ikon sapu di tab Sessions | Bersihkan / Batal |
| `DropdownMenu` opsi sesi | `ChatScreen.kt` | Ikon ⋮ di bar sesi | Bersihkan Riwayat / Hapus Sesi Ini (**tanpa konfirmasi**) |
| Banner feedback | `SettingsScreen.kt`, `WhatsAppGatewayScreen.kt` | Setelah aksi | Tutup “×” |
| Banner user action | `BrowserScreen.kt` | `browser_ask_user` / captcha | Selesai / Batalkan |
| Notifikasi foreground | `WaGatewayService` | Switch service | Buka app / “Stop” |

---

## 6. Navigation Map

*(Fakta — tidak ada NavHost/route string; navigasi adalah state di `MainAppScreen`)*

```
MainActivity (satu Activity)
└── MainAppScreen  [currentTab + currentSubScreen]
    │
    ├── Bottom NavigationBar (5 tab, hanya tampil di level tab)
    │   ├── Beranda      → HomeScreen
    │   │     ├── avatar ikon → Chat           (tab)
    │   │     ├── kartu WhatsApp → Gateway     (sub-screen)
    │   │     ├── kartu Model AI → Pengaturan  (tab)
    │   │     ├── kartu Keamanan Agent → Pengaturan (tab)
    │   │     ├── "Lihat Semua" (Task Aktif) → Tugas (tab)
    │   │     └── "Mulai Chat" → Chat (tab)
    │   ├── Chat         → ChatScreen
    │   │     └── menu sesi (DropdownMenu) → Bersihkan Riwayat / Hapus Sesi (in-place)
    │   ├── Tugas        → TasksScreen
    │   │     └── kartu task → ModalBottomSheet "Detail Task"
    │   ├── Memori       → MemoryScreen
    │   │     ├── Sessions → "Buka Chat" → Chat (tab) + selectSession(id)
    │   │     ├── Sessions → ikon sapu → AlertDialog konfirmasi
    │   │     └── Sessions → ikon hapus → AlertDialog konfirmasi
    │   └── Pengaturan   → SettingsScreen
    │         ├── "Saluran Terhubung" → Gateway (sub-screen)
    │         ├── "Buka Terminal" → Terminal (sub-screen)
    │         ├── "Buka Browser" → Browser (sub-screen)
    │         └── "Developer & Debug Tools" → Developer & Debug (sub-screen)
    │
    └── Sub-screen full-screen (bottom bar TIDAK dirender)
        ├── SubScreen.Gateway         → WhatsAppGatewayScreen
        ├── SubScreen.DeveloperDebug  → DeveloperDebugScreen
        ├── SubScreen.Terminal        → TerminalScreen
        └── SubScreen.Browser         → BrowserScreen
        (SubScreen.TaskDetail — dideklarasikan di NavScreen.kt, TIDAK PERNAH dipakai/dead code)
```

**Perilaku back sistem** *(Fakta — `BackHandler` di `MainAppScreen`)*
1. Bila `currentSubScreen != None` → kembali ke `None` (tab yang sama tetap aktif).
2. Bila `currentTab != HOME` → kembali ke Beranda.
3. Selain itu → back default (keluar aplikasi).

**Tabel navigasi**

| From | Action | To | Condition |
|---|---|---|---|
| Level tab | tap item NavigationBar | tab tujuan | selalu |
| Beranda | tap avatar SmartToy | Chat | selalu |
| Beranda | tap kartu WhatsApp Gateway | Gateway (sub) | selalu |
| Beranda | tap kartu Model AI / Keamanan Agent | Pengaturan | selalu |
| Beranda | “Lihat Semua” / “Mulai Chat” | Tugas / Chat | selalu |
| Beranda | toggle Auto-Reply | — | persist langsung |
| Chat | tap chip sesi / “Personal Agent” | — | `selectedSessionId` berubah |
| Chat | “+” | sesi `chat-<6 digit terakhir epoch>` + terpilih | selalu |
| Chat | ⋮ → Bersihkan/Hapus | — | hanya bila `selectedSessionId != null`; tanpa konfirmasi |
| Chat | tap chip saran (empty state) | — | langsung mengirim pesan |
| Tugas | tap kartu | Detail Task (bottom sheet) | selalu |
| Tugas | filter “Scheduled” | kontrol task terjadwal muncul | `scheduledTasks.isNotEmpty()` |
| Memori | tap tab kategori | konten kategori | selalu |
| Memori | “Buka Chat” | Chat + sesi terpilih | selalu |
| Pengaturan | tile Saluran/Terminal/Browser/Developer | masing-masing sub-screen | selalu |
| Sub-screen | tombol back / back sistem | `SubScreen.None` (kembali ke tab asal) | selalu |
| Sub-screen Browser | banner “Selesai — lanjutkan agent” | — | ada `pendingUserAction` |
| Sistem | back | Beranda lalu keluar | sesuai urutan di atas |

**Yang tidak ada** *(Fakta negatif)*
- Tidak ada deep link / URL route / intent filter selain launcher.
- Tidak ada notifikasi yang membuka layar tertentu (notifikasi hanya membuka `MainActivity`).
- Tidak ada state navigasi yang tersimpan (`rememberSaveable` tidak dipakai) → tab & sub-screen
  kembali ke Beranda `None` setelah proses mati/rotasi.
- Tidak ada breadcrumb/judul halaman pada tab utama (header berada di dalam konten yang ikut
  ter-scroll; hanya sub-screen yang punya `TopAppBar`).

---

## 7. User Flows

Format: Goal / Steps / Important states / Potential failure points / UI implications.
Semua langkah di bawah benar-benar didukung kode saat ini (tidak ada flow rekaan).

### Flow: Menautkan WhatsApp pertama kali

**Goal**: menghubungkan akun WhatsApp ke aplikasi agar agent bisa menerima & mengirim pesan.

**Steps**
1. Buka aplikasi → tab **Beranda**.
2. Tap kartu **WhatsApp Gateway** (atau Pengaturan → Saluran Terhubung).
3. Tap **Hubungkan** (tombol hijau WhatsApp).
4. Pilih metode: tab **QR Code** (scan dari WhatsApp → Perangkat Tertaut) atau tab **Pairing Code**
   (isi nomor dengan kode negara → “Minta Kode Pairing 8 Karakter” → tap kartu kode untuk salin →
   masukkan di WhatsApp).
5. Setelah tertaut, badge berubah menjadi “Connected” dan kartu QR tidak lagi tampil
   (`isLoggedIn == true` menyembunyikan seluruh section “Tautkan Perangkat”).
6. Opsional: nyalakan switch **Foreground Service** → bila API ≥ 33, dialog permission notifikasi
   muncul; bila diberi, service dijalankan dan `connect()` dipanggil.

**Important states**: `isLoggedIn`, `isConnected`, `connectionStatus` (teks dari Go: “Waiting for
QR”, “Connected”, “QR Timeout”, “Error: …”), `qrCode`, `pairingCode`, `pairingMode`, `pairingPhone`,
`isRequestingCode`, `isServiceRunning`.

**Potential failure points**
- Nomor tanpa kode negara → Go menolak (`phone number must include international country code`).
- Sesi sudah tertaut → `PairPhone` menolak dengan pesan yang harus dibaca pengguna.
- QR timeout / error jaringan.
- Permission notifikasi ditolak: switch tetap terlihat “menyala” di UI model
  (`_isServiceRunning` di-set sebelum permission diberikan pada jalur non-Tiramisu) — perlu
  verifikasi visual.

**UI implications**: butuh state “sedang menghubungkan”, error yang bisa dibaca (saat ini status
Go ditampilkan apa adanya, termasuk pesan berbahasa Inggris), dan penanda jelas kapan QR perlu
dimuat ulang.

### Flow: Mengobrol dengan agent di dalam aplikasi

**Goal**: memberi tugas/mengobrol dengan agent tanpa lewat WhatsApp.

**Steps**
1. Tab **Chat** → (opsional) pilih chip sesi atau tekan “+” untuk sesi baru.
2. Tulis pesan; tombol kirim aktif hanya bila teks tidak kosong dan agent tidak sibuk.
3. Pesan user muncul sebagai bubble kanan (`primaryContainer`), agent kiri
   (`surfaceVariant`) dengan ikon SmartToy.
4. Selama proses, bubble “Agent sedang berpikir…” berdenyut + baris aktivitas konkret
   (mis. “Menjalankan tool web_search…”).
5. Jawaban muncul di bubble agent; pesan di-scroll otomatis (`animateScrollToItem`).

**Important states**: `chatInputText`, `isTestingAgent`, `agentState` (+ `isBusy`), `agentCurrentActivity`,
`selectedSessionId`, `selectedSessionMessages`, `sessions`.

**Potential failure points**
- Tidak ada tombol stop/cancel saat agent sibuk; pengguna hanya bisa menunggu.
- Error turn tidak tampil di layar Chat (hanya banner di Pengaturan) → pesan gagal bisa terlihat
  seperti “tidak terjadi apa-apa”.
- Bila `agentState.isBusy`, tombol kirim menampilkan spinner tanpa teks penjelasan penyebab.

**UI implications**: layar chat butuh status yang lebih eksplisit, riwayat error turn, dan kontrol
“hentikan”.

### Flow: Auto-reply pesan WhatsApp masuk (end-to-end)

**Goal**: pesan WhatsApp dari kontak yang diizinkan dijawab otomatis oleh agent.

**Steps (semua di luar UI, tapi efeknya terlihat di UI)**
1. Go menerima event → `WaGatewayManager.onMessage` (pesan sendiri `IsFromMe` diabaikan; pesan grup
   diabaikan).
2. Bridge `handleIncomingMessage` (hanya bila `isAgentAutoReply == true`).
3. Cek whitelist/blacklist (`contactId` = digit nomor hasil normalisasi JID). Bila diblokir →
   dicatat `CONTACT_BLOCKED`, tidak dibalas.
4. Bila teks diawali `/` → `ChatCommandHandler` menangani dan membalas langsung (tidak ke model).
5. Selain itu: agent mengirim bubble `⏳ Sedang berpikir...`, menyalakan typing indicator
   (di-refresh tiap 8 detik), menyusun system prompt (persona + waktu lokal + memori RAG + skill
   index), menjalankan `AgentLoop.processInput` (mungkin dengan beberapa putaran tool), lalu
   **mengedit** bubble ack menjadi jawaban akhir.
6. Bila auto-compact aktif dan `messageCount > maxContextMessages` → ringkas menjadi memori
   episodik.

**Important states**: `isAgentAutoReply`, `whitelistMode`, `agentState`, `useEchoFallback`,
`longTermMemoryEnabled`, `autoCompactEnabled`, `maxContextMessages`, `pendingApprovals`.

**Potential failure points**
- Approval diminta (tool `CONFIRM`) → bubble berhenti di “menunggu persetujuan”; pengguna harus
  membalas `/approve <id>` atau menekan tombol di Pengaturan.
- Semua model gagal → bubble ack diedit menjadi pesan error (tidak menggantung).
- Sesi WhatsApp terputus di tengah turn.

**UI implications**: aplikasi harus memperlihatkan (a) bahwa auto-reply sedang ON, (b) bahwa ada
pesan/persetujuan yang menunggu tindakan pengguna di WhatsApp, dan (c) status koneksi — ketiganya
kini tersebar di Home, Pengaturan, dan Gateway.

### Flow: Menyetujui atau menolak aksi destruktif

**Goal**: memberi/menolak izin untuk aksi yang bisa merusak (`delete_path`, perintah shell tertentu).

**Steps**
1. Agent memanggil tool kelas `CONFIRM` (mis. `delete_path`) atau perintah shell destruktif.
2. `ApprovalCoordinator.createRequest()` membuat record `appr-xxxxxxxx` (TTL 24 jam).
3. Pengguna melihat: di WhatsApp, agent meminta `/approve <id>`; di aplikasi, kartu “Menunggu
   persetujuan (N)” muncul di Pengaturan → Keamanan & Akses (dan penghitung di Home → Keamanan Agent).
4. Tekan **Setujui** atau **Tolak** → tool dieksekusi/dibatalkan, hasil dikirim ke chat.

**Important states**: `approvalEnabled`, `pendingApprovals`.
**Potential failure points**: switch approval off ⇒ tool `CONFIRM` tidak pernah ditawarkan ke model
(perilaku bisa membingungkan bila pengguna merasa “agent tidak bisa menghapus”); request kedaluwarsa
24 jam; approval hanya bisa diputuskan sekali. Nilai switch ini sendiri tidak bertahan setelah
restart proses (14.1 #31).
**UI implications**: daftar approval sekarang hanya menampilkan `toolName`, `id`, dan 160 karakter
pertama argumen (JSON mentah) — sulit dipahami non-teknis.

### Flow: Menjalankan perintah di Terminal

**Goal**: menjalankan shell di perangkat (oleh pengguna maupun agent).

**Steps**
1. Pengaturan → Terminal → **Buka Terminal**.
2. Saat masuk, aplikasi memindai toolchain (`refreshTerminalCapabilities`) dan menampilkan
   `cwd`, daftar biner tersedia/tidak tersedia.
3. Ketik perintah → tombol kirim (disabled bila kosong/sibuk). Output muncul di area gelap;
   `cd` bertahan antar perintah; exit code tampil di header; output dibatasi 1.500 baris.
4. Aksi: Pindai toolchain, Bersihkan, Hentikan shell.

**Important states**: `TerminalState(lines, cwd, busy, started, lastExitCode, lastCommand)`,
`terminalCapabilities`, `terminalEnabled`.
**Potential failure points**: shell gagal start (baris “# gagal memulai shell”); perintah berikutnya
ditolak saat masih sibuk; fungsi tidak tersedia (python/git/gh sering tidak ada).
**UI implications**: UI perlu membedakan output perintah, error, dan pesan sistem (sekarang semuanya
teks monospace dengan prefix `#` atau `$`).

### Flow: Menyerahkan captcha/2FA ke pengguna (browser automation)

**Goal**: agent berhenti dengan jujur saat butuh manusia, lalu melanjutkan setelah manusia selesai.

**Steps**
1. Prasyarat: Browser automation **diaktifkan** di Pengaturan (default off).
2. Agent menjalankan `browser_login` / `browser_ask_user`; engine mendeteksi penanda captcha/2FA.
3. `requestUserHelp` membuat `UserActionRequest`, mengirim pesan WhatsApp, dan menunggu.
4. Pengguna membuka Pengaturan → Browser → **Buka Browser**; banner kuning muncul.
5. Pengguna menyelesaikan langkah manual di WebView, menekan **“Selesai — lanjutkan agent”**
   (atau “Batalkan”).
6. Agent melanjutkan; timeout 6 menit bila tidak ada respons.

**Important states**: `browserEnabled`, `browserPendingUserAction`, `browserSites`, session WebView
(cookie di disk), `browserUserAgentInput`.
**Potential failure points**: timeout; WebView renderer dimatikan sistem (ditangani: buat ulang &
muat ulang URL, maks 3× — *belum diuji di perangkat*); situs anti-bot; sesi berakhir di sisi server.
**UI implications**: banner harus tetap terlihat saat pengguna menggulir/mengganti halaman; tidak ada
indikator sisa waktu (padahal ada timeout 6 menit).

### Flow: Meninjau dan menyimpan memori

**Goal**: menambah/menyimpan fakta, meninjau ringkasan, menilai pelajaran.

**Steps**
1. Tab **Memori** → tab **Knowledge** → tulis fakta → tombol “Simpan Memori” (disabled bila kosong;
   penghitung karakter merah bila > 4.000).
2. Tab **Episodic Memory** untuk ringkasan hasil auto-compact; tab **Learning** untuk kandidat
   pelajaran dari `reflect` (Aktifkan/Tolak).
3. Tab **Skills** untuk daftar prosedur markdown yang ada di workspace.

**Important states**: `newMemoryInput`, `episodicMemories`, `knowledgeMemories`, `learnings`,
`skills`, `longTermMemoryEnabled`, `autoCompactEnabled`, `maxContextMessages`.
**Potential failure points**: cap karakter hanya peringatan visual (tidak memblokir simpan);
learning “Tolak” dan ikon hapus adalah aksi yang sama.
**UI implications**: pengguna perlu tahu bahwa memori ikut masuk ke setiap balasan (saat ini hanya
dijelaskan lewat teks kecil di kartu input).

### Flow: Mengelola tugas terjadwal & sub-agent

**Goal**: melihat apa yang berjalan/terjadwal dan mengendalikannya.

**Steps**
1. Tab **Tugas**.
2. Filter **Scheduled** → muncul kartu kontrol per task (Jalankan / Nonaktifkan / Hapus) di atas
   daftar.
3. Filter lain untuk melihat sub-agent (Running/Completed/Failed), riwayat percakapan (Completed),
   task live (Running/Waiting), dan kegagalan terakhir (Failed).
4. Tap kartu → bottom sheet detail (prompt, agent, model, waktu, tools, hasil/error).

**Important states**: `scheduledTasks`, `subAgentTasks`, `agentState`, `agentLastError`, `sessions`.
**Potential failure points**: pembuatan task hanya dari chat/tool (tidak ada form di UI) → pengguna
non-teknis bisa terhenti; sub-agent tidak bisa dibatalkan; “Completed” pada filter berarti
“percakapan tersimpan”, bukan “task selesai”.
**UI implications**: istilah “Task” kini bercampur antara percakapan, sub-agent, jadwal, dan error
satu kali — perlu kejelasan konsep di UI.

### Flow: Diagnosa & uji model

**Goal**: memastikan model/provider berfungsi.

**Steps**
1. Pengaturan → Developer & Diagnostik → Developer & Debug.
2. Tap **Probe Model (1 + 1 =)** → hasil ✅/❌ + latensi; panel metrik menampilkan ringkasan
   (N panggilan, rata-rata ms, total token) dan 5 panggilan terakhir.
3. Tulis Test Prompt → “Uji Agent Loop Sekarang” → hasil + tombol salin.
4. Baca Tool Registry dan raw log.

**Important states**: `probeResult`, `isProbing`, `modelMetrics`, `agentTools`, `agentLogs`,
`agentWorkspacePath`, `testPromptText`, `testResponseText`, `isTestingAgent`.
**Potential failure points**: `testAgentLoop()` memanggil `saveAgentSettings()` lebih dulu (mengubah
konfigurasi tersimpan sebagai efek samping).
**UI implications**: layar ini paling padat teks teknis; cocok untuk power user, bukan untuk alur
harian.

---

## 8. UI Component Inventory

*(Fakta — dari `ui/components/CommonComponents.kt` dan pemakaian di layar)*

| Kategori | Komponen | Lokasi | Fungsi |
|---|---|---|---|
| Status indicator | `AgentStatusBadge(state)` | Home, Developer | Chip + titik berwarna untuk 12 nilai `AgentState` (IDLE, THINKING, CALLING_TOOL, WAITING_TOOL, DELEGATING, WAITING_SUB_AGENT, REFLECTING, RETRYING, FALLBACK, WAITING_APPROVAL, COMPLETED, FAILED). Label **berbahasa Inggris & hardcoded** |
| Status indicator | `ConnectionStatusBadge(isConnected, statusText)` | Home, Pengaturan, Gateway top bar | Chip hijau “Connected” atau teks status apa adanya |
| Header/in-page | `SectionHeader(title, icon?, actionLabel?, onActionClick?)` | Home, Pengaturan, Gateway, Developer | Judul section + ikon + aksi teks opsional (“Lihat Semua”, “Mulai Chat”) |
| Card | `CompactCard` | Terdefinisi; dipakai terbatas | Kartu dasar 14 dp + border halus + padding 14 dp |
| Empty state | `EmptyStateCard(icon, title, subtitle, actionLabel?)` | Chat, Tugas, Memori | Ikon dalam lingkaran + judul + subtitle (+ tombol opsional; saat ini tidak ada pemakai yang mengisi action) |
| Card lain | `Card` Material 3 langsung | Semua layar | **Pola berulang**: `RoundedCornerShape(12–16.dp)` + `outlinedCardBorder().copy(SolidColor(alpha))` + elevation 0.5–1 dp |
| Chat bubble | `ChatMessageBubble` | Chat | Bubble user (kanan, primaryContainer) vs agent (kiri, surfaceVariant), sudut 16/4 dp, lebar maks 280 dp, waktu `HH:mm` 10 sp + ikon salin 12 dp |
| Chat bubble | `AgentThinkingBubble` | Chat | Bubble sementara dengan spinner + baris aktivitas + animasi alpha berdenyut (`rememberInfiniteTransition`) |
| Chips | `FilterChip` | Chat (sesi), Tugas (filter) | Selection state, warna `primaryContainer` |
| Chips | `SuggestionChip` | Chat (empty state) | Saran pertanyaan; tap = langsung kirim |
| Chip status | `TaskStatusBadge` (lokal di TasksScreen) | Tugas, sheet detail | Chip Running/Waiting/Scheduled/Completed/Failed (warna khusus) |
| Lists | `LazyColumn` / `LazyRow` | Chat, Tugas, Memori, Developer, Terminal | Daftar utama; key dipakai pada task/session/memory item |
| Tabs | `TabRow` (Gateway: QR/Code), `ScrollableTabRow` (Memori: 5 kategori) | Gateway, Memori | Navigasi dalam layar |
| Forms | `OutlinedTextField` | Pengaturan, Gateway, Chat, Memori, Developer | Satu-satunya pola input; `singleLine` untuk field pendek, `minLines` untuk prompt/pesan |
| Switch | `Switch` | Home, Pengaturan, Gateway, MCP list | Auto-reply, Echo, whitelist, approval, memory, compact, auto-reflect, terminal, browser, Gemini vision, service, MCP server |
| Dialog | `AlertDialog` | Memori | Konfirmasi destruktif (hapus sesi, bersihkan riwayat) |
| Bottom sheet | `ModalBottomSheet` | Tugas | Detail task |
| Menu | `DropdownMenu` + `DropdownMenuItem` | Chat | Opsi sesi (2 item) |
| Top bar | `TopAppBar` | 4 sub-screen + Gateway | Judul 2 baris (judul + subjudul) + back arrow; Gateway menambahkan badge di `actions` |
| Navigation | `NavigationBar` + `NavigationBarItem` | Shell | 5 tab, ikon filled saat terpilih + outlined saat tidak, `testTag("nav_tab_<nama>")` |
| Loading | `CircularProgressIndicator` | Chat, Gateway, Developer, Terminal | Ukuran 14–20 dp inline di tombol/bubble |
| Error | — | — | **Tidak ada komponen error khusus.** Error = teks di banner feedback, badge merah, atau blok merah di sheet detail |
| Progress/waktu | — | — | **Tidak ada** progress bar, skeleton, atau relative time (“5 menit lalu”); semua waktu absolut `HH:mm`/`dd MMM, HH:mm` |
| Terminal/log | Kartu gelap `Color(0xFF0F172A)` + teks monospace 11 sp | Gateway logs, Developer logs, Terminal | Konsol read-only (2 kartu) + 1 konsol interaktif |
| QR | `Image` dari bitmap ZXing | Gateway | QR 220 dp di latar putih, border, padding 8 dp |
| WebView | `AndroidView` + `FrameLayout` | Browser | Host WebView milik engine |

**Tidak ditemukan sama sekali** *(Fakta negatif — grep pada `app/src/main/java/com/example/ui/`)*
`BottomSheetScaffold`, `FloatingActionButton`, `Snackbar`/`SnackbarHost`, `SearchBar`/field pencarian,
`LazyVerticalGrid`, pull-to-refresh, `SwipeToDismiss`, `pointerInput`/gesture kustom,
`AnimatedContent`/`Crossfade`, `rememberSaveable`, `BasicTextField`, swipeable, draggable.
`AnimatedVisibility` diimpor di `ChatScreen.kt` dan `WhatsAppGatewayScreen.kt` tetapi **tidak pernah
dipakai** (import mati).

---

## 9. Interaction Model

*(Fakta — semua interaksi di bawah diverifikasi ada di kode)*

| Interaksi | Ada? | Bukti / lokasi |
|---|---|---|
| Tap | Ya | Seluruh aksi: tab nav, kartu clickable (Home WhatsApp/Model/Keamanan, tile Pengaturan), ikon salin, chip, tombol, switch, ikon hapus “×”, kartu kode pairing (salin ke clipboard), QR |
| Long press | **Tidak** | Tidak ada `onLongClick`/`combinedClickable` di seluruh paket `ui/` |
| Swipe | **Tidak** | Tidak ada `SwipeToDismiss`/`swipeable`/`draggable` |
| Drag | **Tidak** | Tidak ada `pointerInput`/`detectDragGestures` |
| Scroll | Ya | `verticalScroll` di Home & Pengaturan; `LazyColumn`/`LazyRow` di Chat, Tugas, Memori, Developer, Terminal, Gateway(log) |
| Auto-scroll | Ya | Chat: `animateScrollToItem(messages.size - 1)` saat jumlah pesan/`isBusy` berubah. Terminal: auto-scroll ke baris terakhir saat `lines.size` berubah |
| Edit (data) | Ya | Field provider/model/prompt/vision/browser/MCP; catatan: edit hanya tersimpan setelah menekan tombol simpan (kecuali switch yang persist langsung) |
| Delete | Ya | Hapus sesi (dialog), bersihkan riwayat (dialog), hapus memori (ikon), hapus task terjadwal (teks merah), hapus server MCP (×), hapus akun situs (×), hapus rule kontak (×) |
| Selection | Ya | Tab nav, chip sesi, tab kategori Memori, filter Tugas, mode pairing (TabRow) |
| Search | **Tidak** | Tidak ada UI pencarian di seluruh aplikasi. Tool `search` hanya untuk agent |
| Filtering | Ya | Filter Tugas (6 chip); **tidak ada** filter/sort di Chat & Memori |
| Sorting | **Tidak** | Urutan tetap: sesi `updatedAt DESC`; memori tanpa ORDER BY eksplisit di UI (mengikuti DAO `getByTypeFlow`) |
| Navigation | Ya | Tap tab, tap kartu, tombol back di `TopAppBar`, back sistem (`BackHandler`) |
| Gestures | Tidak ada khusus | Hanya scroll bawaan Compose |
| Keyboard/IME | Ya | `ImeAction.Send` di field chat (`onSend` → kirim bila tidak sibuk); `KeyboardType.Phone` di field nomor; `imePadding()` di shell, Chat, Pengaturan, Gateway, Developer, Terminal |
| Confirmation actions | **Sebagian** | Konfirmasi ada untuk: hapus/bersihkan sesi (Memori), tidak ada konfirmasi untuk: hapus sesi & bersihkan riwayat dari menu Chat, hapus task terjadwal, hapus server MCP, hapus akun situs, hapus rule kontak, “Putuskan Sesi” WhatsApp (unlink), “Bersihkan sesi browser”, “Hentikan shell” |
| Undo / retry | **Tidak** | Tidak ada undo; tidak ada tombol retry manual (retry hanya otomatis di `AgentLoop`) |
| Copy | Ya | Salin teks bubble (ikon 12 dp), salin hasil test console, salin kode pairing (tap kartu) |
| Cancel | **Sebagian** | Batalkan user-action browser, Bersihkan/Hentikan shell, Tutup dialog; **tidak ada** cancel untuk turn agent yang sedang berjalan |
| Permission request | Ya | Hanya `POST_NOTIFICATIONS` (API ≥ 33) via `rememberLauncherForActivityResult` di Gateway |

**Perilaku feedback** *(Fakta)*
- Setelan yang berhasil/gagal dilaporkan lewat **satu** `sendFeedback` di `WaGatewayViewModel`, yang
  bannernya hanya dirender di `SettingsScreen` dan `WhatsAppGatewayScreen`. Semua aksi dari layar
  lain yang memicu feedback (mis. hapus memori, tambah kontak, atur MCP, hapus situs browser)
  **tidak menampilkan apa pun di layar tempat aksi terjadi**.
- Bahasa pesan feedback bercampur: “Pengaturan Agent berhasil disimpan ke Room Database!”,
  “Memori tersimpan.” (ID) vs “Please enter target phone number”, “Message sent successfully!”,
  “Send error: …” (EN).

---

## 10. Data and State Model

### 10.1 Database (Room) — `agent_database.db`, versi 5

*(Fakta — `AgentDatabase.kt`, entity, DAO; ada migrasi manual 1→5)*

| Tabel | Entity | Isi utama | Dipakai UI di |
|---|---|---|---|
| `agent_sessions` | `AgentSessionEntity` | sessionId, agentId, channel, **conversationId (unique)**, title, lastMessagePreview, messageCount, createdAt, updatedAt | Chat (chip sesi), Memori → Sessions, Tugas (riwayat) |
| `agent_messages` | `AgentMessageEntity` | id, sessionId (FK cascade), role, content, timestamp | Chat (bubble). **Hanya pesan user & jawaban akhir yang disimpan** (pesan tool tidak) |
| `agent_configs` | `AgentConfigEntity` | satu baris `default_config`: autoReply, echoFallback, baseUrl, apiKey (terenkripsi), apiKeys (pool, terenkripsi), modelId, systemPrompt, whitelistMode, longTermMemory*, autoCompact, maxContextMessages, vision*, agentName, autoReflect*, terminalEnabled, browserEnabled, browserSites (terenkripsi), browserUserAgent; **tanpa kolom `approvalEnabled`** (lihat 14.1 #31) | Pengaturan, Home, Developer |
| `contact_rules` | `ContactRuleEntity` | id, contactId (unique, digit ternormalisasi), mode `ALLOWED/BLOCKED/PENDING`, label | Pengaturan → Keamanan & Akses |
| `memory_items` | `MemoryItemEntity` | type `EPISODIC/KNOWLEDGE/LEARNING`, content, keywords, status `active/candidate/obsolete`, conversationId, source, useCount, lastUsedAt | Memori (3 tab) |
| `approval_requests` | `ApprovalRequestEntity` | id `appr-xxxxxxxx`, conversationId, toolName, arguments (JSON mentah), status, requestedAt, TTL 24 jam | Pengaturan (pending) + Home (hitungan) |
| `scheduled_tasks` | `ScheduledTaskEntity` | name, schedule `interval:<detik>` (min 60 s, maks 30 hari), prompt, conversationId, enabled, nextRunAt, lastStatus/lastResult/lastError | Tugas (filter Scheduled) |
| `agent_tasks` | `AgentTaskEntity` | sub-agent: name, task, agentName, modelId, systemPrompt, toolsEnabled, status `QUEUED/RUNNING/COMPLETED/FAILED/CANCELLED`, result, error | Tugas (baris Sub-agent) |
| `mcp_servers` | `McpServerEntity` | name (unique), url, headers (terenkripsi), enabled | Pengaturan → MCP Connector |

Catatan: **tidak ada** tabel untuk skills (file markdown di workspace), log aktivitas agent
(in-memory, maks 100 baris), atau task generik (di-sintesis di UI).

### 10.2 State holder utama — `WaGatewayViewModel`

*(Fakta — ±50 `StateFlow`; layar hanya membaca lewat `collectAsState()`; tidak ada ViewModel kedua)*

Kelompok state:
- **Gateway**: `connectionStatus`, `qrCode`, `pairingCode`, `isLoggedIn`, `isConnected`,
  `isServiceRunning`, `logs`, `messages` (**tidak dipakai UI**), `pairingPhone`, `pairingMode`,
  `isRequestingCode`, `targetPhone`, `messageText`, `isSending`, `sendFeedback`.
- **Agent**: `isAgentAutoReply`, `agentState`, `agentLastError`, `agentLogs`, `agentCurrentActivity`,
  `agentTools`, `agentWorkspacePath`, `modelMetrics`, `probeResult`, `isProbing`.
- **Config (dapat diedit langsung)**: `agentBaseUrl`, `agentApiKey`, `agentApiKeyPool`,
  `agentModelId`, `agentSystemPrompt`, `useEchoFallback`, `visionBaseUrl/ApiKey/ModelId/GeminiNative`.
- **Keamanan**: `whitelistMode`, `whitelistContacts`, `blacklistContacts`, `contactNumberInput`,
  `contactLabelInput`, `approvalEnabled` (runtime; tidak dipersistenkan — 14.1 #31), `pendingApprovals`.
- **Memori**: `longTermMemoryEnabled`, `autoCompactEnabled`, `maxContextMessages`,
  `episodicMemories`, `knowledgeMemories`, `learnings`, `skills`, `newMemoryInput`.
- **Tugas**: `subAgentTasks`, `scheduledTasks`.
- **Chat**: `sessions`, `selectedSessionId`, `selectedSessionMessages`, `chatInputText`,
  `testPromptText`, `testResponseText`, `isTestingAgent`.
- **Terminal**: `terminalEnabled`, `terminalState`, `terminalCapabilities`, `terminalInput`.
- **Browser**: `browserEnabled`, `browserSites`, `browserPendingUserAction`, `browserSiteInput`,
  `browserLoginUrlInput`, `browserUsernameInput`, `browserPasswordInput`, `browserUserAgentInput`.
- **MCP**: `mcpServers`, `mcpStatuses`, `mcpNameInput`, `mcpUrlInput`, `mcpHeadersInput`.
- **Auto-reflect**: `autoReflectEnabled`, `autoReflectIntervalHours`.

### 10.3 Hubungan data → state → UI

```
Room DAO (Flow) ──► Repository/Manager (StateFlow) ──► WaGatewayViewModel ──► Composable
      ▲                                                                            │
      └──────────────── aksi pengguna (mutasi / save) ◄─────────────────────────────┘

Alur turn pesan:
UI (Chat/WhatsApp) → bridge.directChat() ho / handleIncomingMessage()
   → AgentLoop.processInput() → ModelRouter → ModelProvider (OkHttp)
   → [opsional] ToolRegistry → Tool.execute()
   → persist pesan ke Room → StateFlow berubah → UI recompose
```

### 10.4 Jenis state yang ada (dan yang tidak)

| Jenis | Ada? | Detail |
|---|---|---|
| Entity/model | Ya | `AgentSession`, `AgentMessage`, `AgentInput`, `AgentResponse`, `Tool`, `ToolDefinition`, `ToolCall`, `ToolResult`, `ModelTarget`, `ModelProbeResult`, `SubAgentSpec`, `TaskResult`, `SiteCredential`, `BrowserSnapshot/Element/ActionResult`, `Skill`, `WaMessage`, `WaMediaMessage` |
| Persistent state | Ya | 9 tabel Room + cookie WebView + file workspace (`filesDir/workspaces/default-agent`) + `skills/*.md` |
| Session (runtime) state | Ya | `TerminalState` (in-memory), `agentLogs` (maks 100), `modelMetrics` (maks 50), `sendFeedback`, `logger` chat, `approvalEnabled` (runtime; tidak dipersistenkan — 14.1 #31) |
| Loading state | Sebagian | `isSending`, `isRequestingCode`, `isTestingAgent`, `state.busy` (terminal), `isProbing`. **Tidak ada** loading untuk query Room (langsung tampil `emptyList()` awal → bisa berkedip empty state) |
| Error state | Sebagian | `agentLastError`, `sendFeedback`, `probeResult`, badge `FAILED`, blok error di detail task. **Tidak ada** error UI untuk kegagalan Room/gateway init selain teks di log |
| Empty state | Ya | Semua daftar punya `EmptyStateCard` atau teks kosong (Chat, Tugas per filter, Memori per tab, Skills, Browser, Terminal, log gateway/developer) |
| Selection state | Ya | tab, chip sesi, filter tugas, tab memori, mode pairing |
| Authentication state | Ya (bukan akun) | “tertaut/belum tertaut” WhatsApp (`isLoggedIn` dari `Store.ID`), `isConnected`, `isServiceRunning`. Tidak ada login pengguna |
| Network state | Terbatas | Hanya `connectionStatus` (dari Go) + kegagalan HTTP yang muncul sebagai error turn. **Tidak ada** indikator online/offline, tidak ada retry manual |
| Unsaved-changes state | **Tidak** | Field konfigurasi bisa berubah tanpa disimpan dan tanpa penanda; beberapa aksi lain (switch, chat) menyimpan otomatis → perilaku persistensi tidak seragam |
| Saved-tab/back state | **Tidak** | `remember` (bukan `rememberSaveable`) |

---

## 11. Backend / Service Dependencies

*(Fakta + dampaknya ke UX. Tidak ada klaim performa tanpa dasar; angka yang disebut berasal dari
kode/dokumen.)*

| Dependency | Bentuk | Dampak UX |
|---|---|---|
| **Go gateway + whatsmeow (JNI/gomobile)** | Library native `libgojni.so` di dalam AAR; event masuk via callback `WaEventListener` | App **gagal total** bila AAR tidak ada (`UnresolvedLinkError`/`UnsatisfiedLinkError`); hanya ABI arm64-v8a/armeabi-v7a/x86_64. Login memerlukan interaksi eksternal (WhatsApp di HP lain) → butuh instruksi jelas & timeout state (QR timeout, pairing timeout 25 s di Go) |
| **Model Provider (HTTP, OpenAI-compatible)** | OkHttp `POST {baseUrl}/chat/completions` | Respons bisa lambat/tidak menentu → UI wajib punya loading + aktivitas per tahap (sudah ada `AgentCurrentActivity`), error yang bisa dibaca, dan mekanisme fallback (retry 2× per model, budget total 5 percobaan, exponential backoff 100 ms ×1.5) |
| **Vision provider** (opsional; OpenAI-compatible atau Gemini native) | HTTP | Bila key vision kosong, agent mengaku tidak bisa menganalisis media (perilaku jujur, terdokumentasi) → UI perlu menjelaskan konsekuensi mematikan ini |
| **Keys pool** | Rotasi key saat 401/402/403/429 | 5xx/timeout **tidak** menghabiskan key; pesan error menyebut “key 2/3” → string ini muncul di UI/log |
| **Room SQLite lokal** | 9 tabel, in-app storage | Semua layar daftar bergantung pada Flow Room; tidak ada cloud sync; tidak ada ekspor/impor di UI. Back-up sistem diizinkan (`allowBackup=true`) |
| **WhatsApp foreground service** | `WaGatewayService` (`dataSync`), WAKE_LOCK, notifikasi `IMPORTANCE_LOW` | Menjaga proses hidup saat app ditutup; butuh permission notifikasi API 33+; notifikasi berbahasa Inggris |
| **WorkManager** | `SchedulerWorker`, periodik **15 menit** (minimum WorkManager) | Task terjadwal tidak presisi ke detik saat proses mati; saat app hidup ticker 60 detik → perbedaan perilaku yang perlu dijelaskan di UI |
| **WebView Android** | Engine `WebViewBrowserEngine` di balik interface `BrowserEngine` | Membutuhkan proses renderer; `onRenderProcessGone` menangani pemulihan (maks 3×, *belum diuji di perangkat*). Cookie pihak ketiga diaktifkan → login OAuth bekerja, tapi bisa memengaruhi privasi |
| **Shell perangkat** (`/system/bin/sh`) | `ShellRunner`, `TerminalManager`, `ShellPolicy` | Perintah non-tersedia (python/git/gh/node) sering tidak ada → UI wajib menampilkan hasil pemindaian toolchain (sudah ada) |
| **MCP server (HTTP/SSE)** | `McpClient` JSON-RPC Streamable HTTP | Menambah tool `mcp__<server>__<tool>`; kegagalan koneksi harus terlihat (UI punya `mcpStatuses` yang belum tentu terisi) |
| **Android Keystore** | `SecretCipher` (AES-256-GCM, prefix `enc:v1:`) | API key, keys pool, header MCP, dan password situs disimpan terenkripsi; nilai lama plaintext tetap dibaca. Password tidak pernah ditampilkan (kecuali toggle mata) |
| **Notifikasi sistem** | 1 channel LOW, id 1001 | Aksi “Stop” di notifikasi menghentikan service |
| **Provider eksternal lain** | Tidak ada | Tidak ada analytics, crash reporting, Firebase, push, payments, atau backend milik project |

---

## 12. Technical Constraints

*(Fakta dari build config & kode — batasan yang harus dihormati desain baru)*

1. **Platform**: Android minimum **API 24**, target **API 36**, edge-to-edge, `adjustResize`.
   Efek: perilaku sistem bar transparan, inset harus dihormati.
2. **Single Activity + Compose tanpa Navigation library**: navigasi = dua variabel state
   (`currentTab`, `currentSubScreen`). Tidak ada route, argumen navigasi, deep link, atau
   `SavedStateHandle`. Menambah layar berarti menambah `SubScreen` (atau `MainTab`) + cabang `if`.
3. **Tidak ada state yang dipertahankan** (`remember`, bukan `rememberSaveable`) → rotasi/proses
   mati mengembalikan ke Beranda dan menghapus input yang belum disimpan
   (`chatInputText`, `newMemoryInput`, field Pengaturan).
4. **Bahasa UI hardcoded di Kotlin**, hanya `app_name` di `strings.xml` → tidak ada lokalisasi,
   tidak ada RTL copy meskipun `supportsRtl="true"`. Menambah bahasa = refactor besar.
5. **Design token minimum**: hanya `Color.kt` + `Theme.kt` (M3 ColorScheme) & `Type.kt`
   (hanya `bodyLarge` di-override). **Tidak ada token spacing/shape/elevation**; nilai dp literals
   berulang. Tidak ada `dimens.xml`.
6. **Dark/light hanya mengikuti sistem** (`isSystemInDarkTheme()`); `dynamicColor` ada parameternya
   tapi **default `false` dan tidak ada UI** untuk mengaktifkannya → warna brand (emerald +
   WhatsApp green) menjaga konsistensi.
7. **Tema XML**: `Theme.DeviceDefault.NoActionBar` → komponen AppCompat/views klasik di luar Compose
   (kecuali WebView) tidak distyling.
8. **Tidak ada chart/media library**: Coil, Retrofit, Moshi, Firebase, CameraX ada di catalog
   tetapi **tidak dipasang**. Efek langsung: aplikasi **tidak bisa menampilkan gambar** (avatar,
   thumbnail media, screenshot agent) — screenshot hanya dikirim ke WhatsApp lewat
   `send_file_to_chat`, bukan ditampilkan di app.
9. **Layout hanya untuk ponsel portrait**: semua ukuran dp tetap (padding 16 dp, bubble maks 280 dp),
   tidak ada `WindowSizeClass`, tidak ada grid, tidak ada layout dua kolom. Dukungan tablet/lipat
   **Unknown / perlu dikonfirmasi** (tidak ada konfigurasi/penanganan khusus).
10. **Keamanan yang membatasi UI**: API key & password default tersembunyi (toggle mata);
    approval hanya untuk tool destruktif dan hanya lewat chat/daftar pending; tidak ada konfirmasi
    untuk aksi lain (lihat bagian 9).
11. **Keterbatasan native/AAR**: aplikasi hanya terpasang di ABI yang punya `libgojni.so`; tanpa AAR
    kompilasi Kotlin gagal (`unresolved reference: wagateway`).
12. **Build & performa**: tiga varian (`debug`, `optimized` dengan R8, `release`). README menyatakan
    APK `optimized` **belum dijalankan di perangkat**; `release` masih `isMinifyEnabled = false`.
    Ukuran APK didominasi 3 salinan `libgojni.so` (±57 MB di disk per dokumen). Waktu start dingin
    Compose belum dioptimasi (baseline profile = backlog) → **first-frame/冷 start belum terukur**.
13. **Scheduler tidak presisi saat proses mati** (WorkManager minimum 15 menit).
14. **Terminal bergantung toolchain perangkat**; sandbox Linux penuh (proot) belum ada.
15. **Browser memakai WebView, bukan GeckoView** (keputusan sadar; interface `BrowserEngine` siap
    ditukar) → rendering/anti-bot bisa berbeda dari browser desktop.
16. **Media**: audio masuk dicatat tetapi belum ditranskripsi; video besar hanya dianalisis sebagian;
    pengiriman media dari UI belum ada.
17. **Aksesibilitas**: tidak ada uji aksesibilitas di repo; ditemukan tap target jauh di bawah 48 dp
    (ikon salin 12 dp), ikon dekoratif tanpa label yang bermakna, dan teks 10 sp.
18. **Test**: 18 file test JVM/Robolectric (145 tes per dokumen, +1 file androidTest) **tetapi hanya 1 test UI**
    (`GreetingScreenshotTest`) yang merender teks contoh — bukan layar nyata — dan bersifat opt-in
    (`RUN_SCREENSHOT_TESTS=true`). Banyak `testTag` sudah disiapkan untuk pengujian UI.
19. **Dokumentasi vs kode**: `DOC/list-fitur.md` beberapa bagian sudah usang (mis. status MCP), dan
    `README.md` menyebut JDK 17 sementara `app/build.gradle.kts` memakai Java 11.
20. **Persistensi konfigurasi tidak lengkap**: `approvalEnabled` **tidak punya kolom** di
    `AgentConfigEntity`, tidak pernah dibaca saat init bridge, dan tidak ditulis oleh
    `persistConfig()` → switch approval kembali ke default (on) setelah proses baru (14.1 #31).

---

## 13. Existing Design System

Bukan “no design system”, tetapi **design system minimal yang implisit**. *(Fakta — `ui/theme/`)*

**Colors** (`Color.kt` + `Theme.kt`)
| Token | Light | Dark | Pemakaian |
|---|---|---|---|
| `primary` | `AgentEmeraldDark` #059669 | `AgentEmerald` #10B981 | Aksi utama, badge, ikon terpilih |
| `primaryContainer` | #D1FAE5 | `AgentEmeraldDark` | Chip terpilih, banner feedback, tombol “Setujui” |
| `secondary` | #2563EB | #60A5FA | Ikon kartu “Model AI” |
| `tertiary` | `AgentWhatsAppGreen` #25D366 | sama | Koneksi WhatsApp, tombol kirim gateway |
| `background` | #F8FAFC | #0B0F19 | Latar |
| `surface` | #FFFFFF | #131B2A | Kartu |
| `surfaceVariant` | #F1F5F9 | #1C2638 | Bubble agent, area sekunder |
| `outline` | #CBD5E1 | #2D3B52 | Border kartu (alpha 0.15–0.3) |
| Warna ad-hoc | — | — | Status: #F59E0B (warning), #3B82F6 (info), #8B5CF6 (scheduled/waiting), #EC4899 (reflecting), #F97316 (retrying), #6366F1 (delegating), #EF4444 (error), #64748B (fallback), #0F172A (konsol gelap, **sama di light & dark**), #FEF3C7/#92400E (banner handoff browser) |

Catatan: palet M3 lama (`Purple80/40`, dll.) masih ada di `Color.kt` tetapi tidak dipakai.

**Typography** (`Type.kt`): hanya `bodyLarge` (16 sp / line 24 sp / letterSpacing 0.5 sp) di-override;
sisanya M3 default. Ukuran inline khusus di kode: 10 sp (timestamp bubble, teks aktivitas, path
workspace), 11 sp (log monospace). Font: default sistem; `FontFamily.Monospace` untuk log, terminal,
path workspace, id.

**Spacing**: tidak ada token. Nilai literal yang dominan: 4, 6, 8, 10, 12, 14, 16, 20, 24 dp.
Padding layar: 16 dp horizontal, 12 dp vertikal. Jarak antar kartu: 16 dp (`spacedBy`).

**Shapes**: tidak ada token. `RoundedCornerShape` 10 dp (field/tombol), 12/14 dp (kartu), 16 dp
(kartu Agent Core, bubble), 20 dp (badge/chip), 24 dp (field chat), 4/16 dp (sudut bubble chat).

**Borders & elevation**: pola khas aplikasi adalah kartu **tanpa bayangan nyata** (elevation
0.5–1 dp) dengan border 1 px dari warna `outline` ber-alpha 0.18–0.3. `NavigationBar`
`tonalElevation 2 dp`, `Surface` bar 1–2 dp.

**Icons**: `material-icons-extended` (filled + outlined). Ikon tab: Home/Chat/Checklist/Memory/Settings
(filled saat terpilih, outlined saat tidak). Badge status memakai titik berwarna 8 dp, bukan ikon.

**Themes / dark-light**: dua `ColorScheme` lengkap (light & dark) mengikuti sistem; tidak ada toggle
manual; tidak ada dynamic color dari wallpaper (sengaja dimatikan dengan alasan konsistensi brand).

**Animation**:
- **Satu-satunya animasi kustom**: `AgentThinkingBubble` (alpha 0.4→1.0, tween 800 ms,
  `RepeatMode.Reverse`, infinite) — dipakai juga pada indikator “Agent sedang berpikir”.
- `animateScrollToItem` untuk auto-scroll Chat & Terminal.
- Tidak ada transisi antar layar/tab (tanpa `AnimatedContent`/`Crossfade`), tidak ada animasi
  list item, tidak ada ripple kustom.

**Elevation/shadows**: tidak ada shadow token; konsol gelap memakai warna solid, bukan elevasi.

**Jarak dari “design system penuh”**: tidak ada spacing/typography scale terdokumentasi, tidak ada
komponen bertoken (semua kartu ditulis ulang di masing-masing layar dengan nilai yang hampir sama),
tidak ada dark-mode audit, tidak ada aksesibilitas yang dinyatakan.

---

## 14. Existing UI Problems

### 14.1 Confirmed problems (dapat dibuktikan dari implementasi)

| # | Masalah | Bukti |
|---|---|---|
| 1 | **Bahasa UI bercampur ID/EN** — melanggar instruksi `DOC/redesign.md` (“Gunakan satu bahasa UI secara konsisten”) | Label EN: “Ready (Idle)”, “Thinking…”, “Calling Tool”, “Waiting Tool”, “Delegating”, “Sub-Agent…”, “Reflecting”, “Retrying”, “Echo Fallback”, “Approval Req.”, “Done”, “Error” (`AgentStatusBadge`); “Connected/Disconnected” (`ConnectionStatusBadge`); filter Tugas “Running/Waiting/Scheduled/Completed/Failed”; tab Memori “Sessions/Episodic Memory/Knowledge/Skills/Learning”; “Interactive Test Console”, “Tool Registry”, “Storage Driver”, “Developer & Debug Tools”; pesan feedback “Please enter target phone number”, “Message sent successfully!”, “Send error: …”; notifikasi “WhatsApp Gateway Native”, “Status: …”, aksi “Stop” |
| 2 | **Tap target sangat kecil** | Ikon salin di bubble chat: `Modifier.size(12.dp).clickable{}` (`ChatScreen.kt`). Teks 10 sp untuk timestamp & aktivitas |
| 3 | **Aksi destruktif tanpa konfirmasi** | Menu ⋮ di Chat memanggil `clearSessionHistory()`/`deleteSession()` langsung; Tugas: `deleteScheduledTask`; Pengaturan: hapus rule kontak/server MCP/akun situs, “Putuskan Sesi” (unlink WhatsApp), “Bersihkan sesi” browser, “Hentikan shell”. Hanya sesi di Memori yang punya dialog konfirmasi → **inkonsisten** |
| 4 | **Feedback aksi tidak terlihat di layar asal** | `sendFeedback` hanya dirender di `SettingsScreen` + `WhatsAppGatewayScreen`; aksi dari Chat/Memori/Tugas (mis. hapus memori, hapus sesi dari Memori) menulis feedback yang tidak terlihat |
| 5 | **Error turn di Chat tidak ditampilkan di Chat** | `ChatScreen` tidak membaca `sendFeedback`/`agentLastError`; kegagalan `directChat` hanya memunculkan banner di Pengaturan |
| 6 | **Satu tombol simpan untuk beberapa section, tanpa status “belum disimpan”** | Tombol “Simpan Konfigurasi ke Database” berada di kartu *Agent Persona & Prompt* tetapi ikut menyimpan Base URL/API Key/Keys Pool/Model ID dari kartu *Model & Provider*. Tidak ada penanda dirty |
| 7 | **`SubScreen.TaskDetail` dead code** | Dideklarasikan di `ui/navigation/NavScreen.kt`, tidak pernah direferensikan (grep hanya menemukan deklarasi) |
| 8 | **Ikon tab tidak konsisten dengan enum-nya** | `MainTab.HOME` mendeklarasikan ikon `Dashboard`, tetapi `MainAppScreen` membangun daftar tab sendiri memakai `Icons.Filled.Home`/`Outlined.Home` (enum tidak dipakai untuk ikon) |
| 9 | **`import AnimatedVisibility` mati** | Diimpor di `ChatScreen.kt` & `WhatsAppGatewayScreen.kt`, nol pemakaian |
| 10 | **“Tools Terlibat” tidak menampilkan nama tool** | Untuk baris sub-agent, `AgentTaskRecord.tools` diisi `listOf("tools")` atau kosong (`TasksScreen.kt`) |
| 11 | **Ikon hapus pada Learning = Tolak** | `MemoryItemCard(onDelete = { viewModel.rejectLearning(item.id) })` dengan `contentDescription = "Hapus memori"` — aksi destruktif yang sama muncul di dua kontrol berbeda |
| 12 | **Waktu task bisa berubah-ubah** | Entri task live memakai `timeFormat.format(Date())` di dalam blok `remember(...)` yang key-nya termasuk `agentLogs` → waktu rekam ulang setiap log baru |
| 13 | **“Completed” berarti “percakapan punya pesan”** | `sessions.forEach { if (session.messageCount > 0) → TaskFilter.COMPLETED }`; judulnya “Percakapan: <conversationId>” → tab Tugas menduplikasi daftar di Memori → Sessions dengan makna status yang berbeda |
| 14 | **Identitas sesi = JID mentah** | Chip sesi & judul “Percakapan: …” memakai `conversationId` (mis. `62812…@s.whatsapp.net`), dipotong 13 karakter + “..”. Sesi dibuat dengan `title = conversationId`; `AgentSession.title` tidak pernah diubah |
| 15 | **Pesan yang disarankan langsung terkirim** | `SuggestionChip.onClick = { viewModel.sendDirectChatMessage(suggestion) }` — user tidak bisa mengedit saran sebelum dikirim |
| 16 | **Kop teks menyarankan tombol yang tidak ada** | Pesan error `browser_login` menyebut “tombol *Login manual* di tab Browser”, sedangkan `BrowserScreen` tidak punya tombol itu |
| 17 | **Status “aktif” tidak konsisten antar layar** | `HomeScreen` menganggap task aktif hanya untuk `THINKING/CALLING_TOOL/WAITING_APPROVAL`, sementara `AgentState.isBusy` (dipakai Chat) mencakup 9 state termasuk `WAITING_TOOL`, `RETRYING`, `FALLBACK`, `DELEGATING`, `WAITING_SUB_AGENT`, `REFLECTING` |
| 18 | **State UI hilang saat proses mati/rotasi** | `remember { mutableStateOf(...) }` untuk `currentTab`, `currentSubScreen`, `showApiKey`, `selectedFilter`, `selectedTask`, `selectedTab`, dll. Tidak ada `rememberSaveable` |
| 19 | **Daftar Knowledge bukan Lazy** | Tab Knowledge merender seluruh fakta di dalam `Column.verticalScroll` (bukan `LazyColumn`) — semua item di-compose sekaligus |
| 20 | **Mode `PENDING` kontak tidak pernah ditulis** | `ContactAccessRepository.markPending()` tidak punya pemanggil (grep hanya menemukan definisi), meski `ContactRuleEntity.MODE_PENDING` dan dokumen menyebut alur approval kontak |
| 21 | **`WaGatewayManager.messages` & `mediaMessages` tidak dipakai UI** | `viewModel.messages` diekspos tetapi tidak dirender layar mana pun; `mediaMessages` bahkan tidak diekspos ke ViewModel — media masuk tidak punya permukaan UI |
| 22 | **Mode “Personal Agent” vs sesi anonim ambigu** | `sendDirectChatMessage()` memakai `conversationId = "personal-agent"` bila tidak ada sesi terpilih; chip “Personal Agent” hanya merepresentasikan `selectedSessionId == null`, bukan sesi nyata |
| 23 | **Quick-set tanpa jalankan** | Chip perintah cepat Terminal hanya **mengisi** input (`terminalInput.value = ...`), tidak menjalankan perintah |
| 24 | **Konsol gelap memakai warna hardcoded yang sama di light & dark** | `Color(0xFF0F172A)`/`Color(0xFFE2E8F0)`/`Color(0xFF94A3B8)` di Gateway, Developer, Terminal → tidak mengikuti color scheme |
| 25 | **Header tidak ikut scroll untuk tab, sedangkan sub-screen punya TopAppBar** | Tab utama: header berada di dalam konten `verticalScroll` (hilang saat scroll) dan tidak ada app bar; sub-screen: `TopAppBar` dua baris. Pola visual tidak seragam |
| 26 | **Duplikasi baris terjadwal pada satu layar** | Filter Scheduled menampilkan kartu kontrol **dan** baris daftar untuk entitas `scheduled_tasks` yang sama |
| 27 | **Kartu “Keamanan Agent” di Home mengarah ke atas Pengaturan** | Tap-nya membuka Pengaturan dari awal, bukan ke section “Keamanan & Akses” (tidak ada scroll-to-section) |
| 28 | **Tidak ada kontrol hentikan untuk turn agent** | Tidak ada tombol cancel/stop di Chat atau Home meski `AgentLoop` menjalankan siklus tool multi-iterasi (maks 4, clamp 10) dan retry (budget 5 percobaan) |
| 29 | **Tidak ada UI untuk 2 fitur yang „dijanjikan” di teks** | `browser_login` menyebut “Login manual”; `DOC/redesign.md` menuntut empty state “WhatsApp belum terhubung.” dengan action relevan — yang ada hanya teks di kartu Home |
| 30 | **Test UI praktis nol** | Satu-satunya test Compose merender teks contoh dan opt-in → tidak ada jaring pengaman visual untuk 10 layar nyata |
| 31 | **Switch approval tidak bertahan setelah restart** | `approvalEnabled` tidak punya kolom di `AgentConfigEntity`, tidak pernah dibaca saat init `WhatsAppAgentBridge`, dan tidak ditulis oleh `persistConfig()` — nilainya hanya hidup di `MutableStateFlow` runtime dan di-reset ke default (on) tiap proses baru |
| 32 | **State `whitelistEnabled` mati (dead state)** | `_whitelistEnabled` dideklarasikan di `WhatsAppAgentBridge` tanpa pembaca/pemakai — whitelist yang benar-benar berjalan dibaca dari tabel `contact_rules`, sehingga state ini menyesatkan bila diikat ke UI |

### 14.2 Potential problems (perlu verifikasi visual/usability testing)

| # | Dugaan masalah | Alasan dari kode |
|---|---|---|
| 1 | Keyboard menutupi elemen pada layar dengan `weight`/daftar panjang | `imePadding()` dipakai di beberapa tempat; Knowledge tab menggabungkan `heightIn(max = 160.dp)`, `weight(1f)`, dan `verticalScroll` — kombinasi ini perlu diuji di layar kecil |
| 2 | `BrowserScreen` bisa berkedip/melepas WebView | `val webView = viewModel.browserView()` dihitung setiap recomposition + `AndroidView` membuat `FrameLayout` baru; `refreshKey` hanya memaksa wrapper baru |
| 3 | Perubahan state antar-tab terasa “reset” | Karena sesi/daftar berasal dari Flow Room, perpindahan tab normalnya aman, tetapi `remember`-based UI state (filter, tab kategori, sesi terpilih) tidak persisten |
| 4 | Latensi first paint daftar panjang | Knowledge (non-lazy) dan `verticalScroll` Pengaturan (1209 baris) merender seluruh pohon sekaligus |
| 5 | Indikator “Connected” bisa menyesatkan | `ConnectionStatusBadge` menampilkan “Connected” berbasis `isConnected` native; `isLoggedIn` (sesi tersimpan) bisa true saat belum tersambung — dua kondisi ditampilkan berdampingan di kartu berbeda |
| 6 | Duplikasi aksi berisiko salah tap | Di kartu keamanan Pengaturan, input nomor + tombol “Izinkan” dan “Blokir” berdampingan (tombol blokir memakai warna error) |
| 7 | `probeResult`/`testResponse` tanpa penanda waktu | Hasil probe lama bisa terlihat seperti hasil saat ini |
| 8 | QR 220 dp + padding pada layar kecil | Kombinasi `size(220.dp)` dalam `Column` `verticalScroll` dengan padding 16 dp bisa terasa sempit pada layar < 360 dp |
| 9 | Satu state `showApiKey` dipakai bersama untuk dua field sensitif yang jauh terpisah | Toggle “mata” untuk API key (kartu Model & Provider) dan password situs (section Browser) membaca state `showApiKey` yang sama (`SettingsScreen.kt`) → membuka salah satu ikut membuka yang lain |

---

## 15. UX Risks

*(Setiap risiko disertai alasan dari implementasi.)*

| Risiko | Alasan dari implementasi |
|---|---|
| **Kebingungan konsep “task”** | Tab Tugas mencampur 5 sumber berbeda (state live, riwayat percakapan, sub-agent, jadwal, error terakhir) dengan satu vocabulary badge yang sama (`TaskFilter`) |
| **Navigasi berlebihan / tersembunyi** | Konfigurasi penting tersebar: auto-reply di Home; kelola kontak di Pengaturan (perlu scroll jauh); approval di Pengaturan (bukan di Chat/Home); tool terminal & browser di dalam satu halaman scroll panjang. Browser & Terminal hanya dapat dibuka dari dalam Pengaturan |
| **Fungsi tersembunyi (hidden functionality)** | Avatar SmartToy di header Home clickable tanpa label visual; kartu “Keamanan Agent” clickable menuju Pengaturan; mode pairing tersembunyi di balik `TabRow` yang hanya muncul bila belum tertaut; perintah chat (`/help`) adalah **satu-satunya** cara menemukan beberapa fitur (mis. `/compact`, `/remember`, `/learning`, `/terminal`, `/browser`) — tidak tercermin di UI aplikasi |
| **Feedback buruk** | Feedback global hanya tampil di 2 layar (lihat 14.1 #4); tidak ada snackbar/toast; beberapa aksi tampak “tidak terjadi apa-apa” (hapus memori, hapus rule kontak, simpan akun situs — kecuali bila pengguna kebetulan berada di Pengaturan) |
| **Aksi ambigu** | Ikon “×” dipakai untuk: tutup banner, hapus rule kontak, hapus server MCP, hapus akun situs. Ikon hapus (trash) dipakai untuk hapus memori **dan** tolak learning. Ikon `DeleteSweep` (bersihkan) vs `DeleteOutline` (hapus) berdampingan tanpa teks |
| **Risiko kehilangan data** | Hapus/bersihkan dari menu Chat tanpa konfirmasi; “Putuskan Sesi” (unlink WhatsApp) tanpa konfirmasi; “Bersihkan sesi” browser tanpa konfirmasi; tidak ada undo; tidak ada ekspor |
| **Pemulihan error lemah** | Tidak ada retry manual di UI; error turn tidak muncul di Chat; status koneksi hanya berupa teks dari Go (sering berbahasa Inggris, mis. “Connection Error: …”); bila model gagal semua, pesan error diedit ke bubble WhatsApp tetapi di UI aplikasi hanya `agentLastError` yang tampil di Tugas |
| **Kebingungan state** | 12 nilai `AgentState` dipaparkan ke pengguna sebagai badge (beberapa sangat internal: `WAITING_TOOL`, `DELEGATING`, `WAITING_SUB_AGENT`, `FALLBACK`); status “aktif” berbeda antara Home dan Chat (14.1 #17); `isLoggedIn` vs `isConnected` vs `isServiceRunning` ditampilkan di tempat berbeda |
| **Beban kognitif** | Pengaturan = 1 halaman dengan 10 section, ±40 kontrol, tanpa pencarian/pembagian; Developer = 4 blok teknis; Memori = 5 kategori dengan aksi yang mirip |
| **Kesalahan persepsi jumlah pekerjaan** | Satu `AgentState` bisa menghasilkan satu entri task, sementara setiap sesi (termasuk percakapan WhatsApp) muncul sebagai “Completed” → daftar bisa panjang dan tidak terkelola (tanpa paging, tanpa pencarian, tanpa grup) |
| **Ketidakpastian persetujuan** | Pengguna WhatsApp harus mengingat `/approve <id>`; di aplikasi ID ditampilkan sebagai `toolName • appr-xxxxxxxx` + JSON 160 karakter — sulit dinilai risikonya. Tidak ada batas waktu yang terlihat (padahal 24 jam) |
| **Privasi membingungkan** | Beberapa nilai sensitif tersembunyi dengan toggle “mata” (API key utama, vision key, password situs) tetapi **satu state `showApiKey` dipakai bersama** oleh API key & password situs → membuka salah satu ikut membuka yang lain (dan bagian akun situs berada jauh di bawah, di section berbeda) |

---

## 16. Missing UI / Incomplete Areas

Format mengikuti permintaan: **Feature / Backend support / Current UI / Potential UI surface**.
Bagian “Potential UI surface” sengaja dibiarkan **Unknown / perlu desain** (dokumen ini tidak
mendesain).

### 16.1 Backend ada, UI tidak ada

| Feature | Backend support | Current UI | Potential UI surface |
|---|---|---|---|
| Kirim media keluar (gambar, dokumen, audio/voice note, video) | Ya — `WaGatewayManager.sendImage/sendDocument/sendAudio`, tool `send_file_to_chat` | Tidak ada lampiran di Chat; tidak ada tombol kamera/mic | Unknown / perlu desain |
| Media masuk sebagai objek yang bisa dilihat | Ya — `WaMediaMessage` + `mediaMessages` (maks 50) + unduh/dekripsi `downloadMedia` | Hanya pesan teks “📎 Media diterima…” di WhatsApp | Unknown / perlu desain |
| Riwayat pesan transport mentah | Ya — `WaGatewayManager.messages: StateFlow<List<WaMessage>>` (masih diekspos ke VM) | Tidak ada layar yang merender | Unknown / perlu desain |
| Kontak berstatus `PENDING` | Ya — `ContactRuleEntity.MODE_PENDING`, `ContactAccessRepository.markPending()` (tanpa pemanggil) | Tidak ada; hanya whitelist & blacklist | Unknown / perlu desain |
| Riwayat approval (approved/rejected/expired) | Ya — `ApprovalRequestDao` menyimpan semua status + `expireStale()` | Hanya `PENDING` ditampilkan | Unknown / perlu desain |
| Batalkan sub-agent | Sebagian — `AgentTaskEntity.STATUS_CANCELLED` dideklarasikan, `SubAgentManager` tidak punya `cancel()` | Tidak ada tombol | Unknown / perlu desain |
| Isi/kelola skill markdown | Ya — `SkillLibrary.load()`, tool `list_skills/read_skill/save_skill` | Daftar read-only (nama, deskripsi, path) | Unknown / perlu desain |
| Rantai fallback provider/model yang bisa diatur | Ya — `ModelRouter.setTargets/addTarget/removeTarget`, `RetryPolicy` | Hanya toggle Echo Fallback | Unknown / perlu desain |
| Aktif/nonaktif tool individual | Ya — `ToolRegistry.register/unregister` (`applyTerminalTools`, `applyBrowserTools`) | Hanya 2 toggle grup (terminal, browser) + daftar read-only | Unknown / perlu desain |
| Membuat/mengedit task terjadwal dari UI | Ya — `SchedulerEngine.create(...)`, tool `schedule_task`, entity punya `schedule`/`prompt`/`name` | Hanya Jalankan/Nonaktif/Hapus; pembuatan lewat chat/tool | Unknown / perlu desain |
| Edit memori & bulk action | Ya — `MemoryRepository` (save/delete/promote/reject) | Tambah & hapus per item saja | Unknown / perlu desain |
| Cari/filter/sort di dalam aplikasi | Ya — tool `search` (chat/memory/tasks/files/tools) bersifat lokal | Tidak ada UI pencarian sama sekali | Unknown / perlu desain |
| Ganti nama sesi & label kontak yang tampil | Ya — `AgentSession.title`, `AgentSessionEntity.title`, `ContactRuleEntity.label` | `title` tidak pernah diubah; label hanya diisi di form kontak | Unknown / perlu desain |
| Mode gelap/terang manual | Ya — `MyApplicationTheme(darkTheme = ...)`, `dynamicColor` | Mengikuti sistem saja | Unknown / perlu desain |
| Multi-agent (beberapa agent dengan persona/workspace berbeda) | Sebagian — `agentId`, `Workspace.of(base, agentId)`, `AgentTaskEntity.agentName`; aplikasi memakai `"default-agent"` | Tidak ada UI multi-agent | Unknown / perlu desain |
| Ekspor/impor/backup data lokal | Tidak ada UI; `allowBackup=true` (back-up sistem) | Tidak ada | Unknown / perlu desain |
| Pengaturan notifikasi | Ya — 1 channel `IMPORTANCE_LOW` | Tidak ada | Unknown / perlu desain |

### 16.2 Fitur yang dinyatakan belum ada (dan sengaja tidak dipalsukan)

*(Fakta dari README + `DOC/list-fitur.md` + isi `DOC/reference/`)*
- **Sandbox Linux penuh (proot/rootfs)** — dossier `DOC/reference/linux-sandbox/` (README,
  architecture, behavior, implementation, sandbox) + rencana di `DOC/riset-optimasi.md` §3.1.
- **Transkripsi audio (STT)** — audio diterima tapi tidak ditranskripsi.
- **MCP Resources/Prompts/Sampling** — hanya Tools.
- **Provider Anthropic native** — tidak ada.
- **Format skill universal (hermes/openclaw)** — hanya format markdown milik sendiri.
- **GeckoView** — ditolak secara sadar (rasional di `DOC/riset-optimasi.md` §6); interface
  `BrowserEngine` sudah siap ditukar.
- **Baseline profile / start dingin** — backlog.
- **Pemulihan renderer WebView & APK `optimized`** — jalur kodenya ada, **belum pernah dipicu/diuji
  di perangkat** menurut README & `DOC/riset-optimasi.md`.
- Tidak ada **TODO/FIXME** di source Kotlin (`grep` hanya menemukan satu kecocokan palsu pada string
  format tanggal) → “pekerjaan yang belum selesai” di project ini dinyatakan lewat dokumen, bukan
  komentar kode.

---

## 17. Design-Relevant Architecture

*(Hanya bagian yang memengaruhi UI/UX.)*

```
[Pengguna]
   │ tap / teks
   ▼
[Composable layar]  (collectAsState dari StateFlow)
   │ memanggil fungsi ViewModel
   ▼
WaGatewayViewModel  (≈50 StateFlow; satu-satunya state holder UI)
   │
   ├──► WhatsAppAgentBridge ──► AgentLoop ──► ModelRouter ──► ModelProvider (OkHttp)
   │        │                       │                          ▲
   │        │                       └── ToolRegistry ──► Tool (file/web/terminal/browser/MCP/…)
   │        ├──► Room (9 tabel) via Repository/DAO (Flow) ──────┘
   │        └──► SchedulerEngine / SubAgentManager / CompactManager / MemoryRepository
   │
   └──► WaGatewayManager (singleton, `WaEventListener` dari Go/JNI)
            ├── go / wagateway (whatsmeow)  → kirim & terima WhatsApp
            └── WaGatewayService (foreground, notifikasi)

Alur masuk WhatsApp (tanpa sentuhan UI):
JNI event → WaGatewayManager.onMessage → bridge.handleIncomingMessage
   → cek auto-reply → cek whitelist/blacklist → ChatCommandHandler (bila "/") ATAU AgentLoop
   → Room tersimpan → Flow berubah → layar Chat/Memori/Tugas ikut ter-update
```

Yang perlu diketahui designer:
1. **UI bukan sumber kebenaran data.** Hampir semua layar merender Flow Room langsung; menghapus
   state UI tidak menghapus data, tetapi menghapus data dari UI berdampak permanen (tidak ada undo).
2. **Dua kanal input yang setara**: WhatsApp dan Chat internal sama-sama menulis ke tabel sesi yang
   sama (`conversationId` berbeda) → layar Chat melihat keduanya. Ini alasan teknis mengapa Chat
   memakai chip sesi, bukan hanya satu percakapan.
3. **Worker latar belakang mengubah UI tanpa aksi pengguna**: scheduler (60 s / 15 menit), sub-agent,
   hasil refleksi otomatis, dan auto-reply WhatsApp semuanya dapat memunculkan/merubah baris di
   Tugas, Memori, Home, dan Chat.
4. **Konfigurasi adalah input langsung ke perilaku**: base URL/key/model/prompt/whitelist/approval
   memengaruhi apa yang agent lakukan di WhatsApp (kanal yang tidak terlihat). UI harus membuat
   konsekuensi ini dapat dipahami.
5. **Data yang tidak boleh ditampilkan sembarangan**: API key, keys pool, password situs, header
   MCP, isi pesan/percakapan. Semua berada di perangkat (Room + filesDir + cookie jar).
6. **Tidak ada renderer media**: aplikasi tidak dapat menampilkan file/gambar di dalam UI saat ini
   (library image loader tidak terpasang); setiap desain yang membutuhkan thumbnail harus
   memperhitungkan penambahan dependensi.
7. **Semua teks UI ada di kode Kotlin**, jadi setiap perubahan copy menyentuh file layar masing-masing.

---

## 18. Design Constraints vs Design Opportunities

### 18.1 Constraints (tidak boleh diabaikan desain baru)

1. **Struktur navigasi existing**: 5 tab (`MainTab`) + 4 sub-screen (`SubScreen`) dengan bottom bar
   yang disembunyikan pada sub-screen, back-handling khusus. Menambah layar = menambah cabang state;
   menghilangkan pola ini berarti refactor navigasi.
2. **Tidak ada Navigation component & tidak ada state tersimpan** → desain tidak boleh mengandalkan
   deep link, argumen route, atau state yang bertahan setelah proses mati (kecuali ditambahkan).
3. **Bahasa & copy hardcoded**: lokalisasi penuh akan menjadi pekerjaan besar, bukan perubahan label.
4. **Komponen & token yang ada**: Material 3, palet `Color.kt` (emerald + WhatsApp green) — warna ini
   dipakai konsisten sebagai identitas produk (WhatsApp hijau khusus untuk hal yang terkait WhatsApp).
5. **Aturan keamanan**: API key/password tersembunyi secara default; approval hanya untuk tool
   `CONFIRM` dan dijawab lewat chat atau daftar pending; whitelist cek **sebelum** pesan dibaca.
6. **Perilaku agent tidak boleh diubah oleh desain**: 12 state `AgentState`, urutan
   ack→edit bubble, typing indicator tiap 8 detik, TTL approval 24 jam, batas tool 4 iterasi,
   budget retry 5 percobaan, interval minimum scheduler 60 detik, WorkManager minimum 15 menit.
7. **Tidak ada media rendering** (Coil tidak terpasang) dan **tidak ada komponen chart**.
8. **Keterbatasan platform**: minSdk 24, edge-to-edge, targetSdk 36, permission hanya
   `POST_NOTIFICATIONS`, ABI terbatas.
9. **Terminal berbasis shell perangkat** dan **browser berbasis WebView** — desain tidak boleh
   menjanjikan lingkungan Linux penuh atau rendering seperti desktop.
10. **Satu pengguna, satu perangkat, satu akun WhatsApp** — tidak ada konsep tim/akun bersama.
11. **Empty state harus jujur**: README & `DOC/redesign.md` menegaskan larangan menampilkan UI palsu
    atau data dummy untuk fitur yang belum ada (sandbox, STT, Resources MCP, dsb.).
12. **Persistensi switch tidak seragam**: sebagian switch menyimpan otomatis ke Room, tetapi switch approval tidak dipersistenkan sama sekali (14.1 #31) — desain tidak boleh mengasumsikan semua switch adalah sumber kebenaran yang bertahan restart.

### 18.2 Opportunities (secara teknis dimungkinkan, **bukan keputusan desain**)

1. **Konsistensi bahasa & istilah**: seluruh string ada di kode; menyatukan ke satu bahasa (ID)
   dengan istilah teknis EN adalah perubahan terlokalisasi.
2. **Sistem token**: bentuk `SectionHeader`/`CompactCard`/`EmptyStateCard` sudah ada; pola kartu
   berulang (radius + border alpha) dapat diekstraksi menjadi token/komponen bersama.
3. **Permukaan untuk media**: `WaMediaMessage`, `mediaMessages`, dan tiga method pengiriman media
   sudah ada di backend; UI belum memakainya (butuh juga image loader).
4. **Pencarian dalam aplikasi**: tool `search` beserta `SearchSources` (chat, memory, tasks, files,
   tools) sudah ada dan lokal; UI belum memanfaatkannya.
5. **Konfigurasi lanjutan**: `ModelRouter` sudah mendukung banyak target; `ToolRegistry` mendukung
   register/unregister per tool → UI pengaturan lanjutan dimungkinkan.
6. **Konsistensi aksi destruktif**: hanya butuh menambah dialog/pola konfirmasi pada titik-titik yang
   belum punya (14.1 #3).
7. **Feedback lokal**: `sendFeedback` bisa dirender di layar mana pun, atau ditambah pola lain
   (snackbar/banner per layar) — komponennya belum ada sehingga perlu dibuat.
8. **Kontrol pembatalan**: `AgentState.isBusy` + coroutine `viewModelScope` tersedia; kemampuan
   membatalkan turn secara teknis belum ada (Unknown seberapa besar perubahan yang diperlukan).
9. **State persisten UI**: `rememberSaveable`/`SavedStateHandle` belum dipakai sama sekali → tab,
   filter, dan input yang belum disimpan bisa dipertahankan.
10. **Aksesibilitas**: `testTag` sudah tersebar (memudahkan pengujian), kontras warna sebagian besar
    berbasis token M3, tetapi ukuran tap target & label ikon perlu diperbaiki.
11. **Notifikasi & status latar**: satu channel + `connectionStatus` bisa dipakai untuk memberi
    gambaran “agent bekerja di latar” tanpa mengubah isi notifikasi.
12. **Onboarding/penjelasan perintah chat**: `/help` sudah mengembalikan daftar perintah; UI belum
    pernah menampilkan daftar itu di dalam aplikasi.

---

## 19. Important Files

| File | Purpose | Why relevant to UI/UX |
|---|---|---|
| `app/src/main/java/com/example/ui/MainAppScreen.kt` | Shell + 5 tab + sub-screen + `BackHandler` | Satu-satunya definisi arsitektur navigasi; semua entry point tab/CTA ada di sini (termasuk daftar label & ikon tab yang sebenarnya dipakai) |
| `app/src/main/java/com/example/ui/navigation/NavScreen.kt` | `MainTab` (title + ikon) & `SubScreen` | Vocabulary layar; memuat `SubScreen.TaskDetail` yang tidak dipakai |
| `app/src/main/java/com/example/ui/home/HomeScreen.kt` | Dashboard Beranda | Semua hierarchy informasi level atas & CTA utama |
| `app/src/main/java/com/example/ui/chat/ChatScreen.kt` | Chat + sesi + bubble + input | Layar interaksi utama; berisi empty state, bubble, indikator sibuk, dan menu sesi |
| `app/src/main/java/com/example/ui/tasks/TasksScreen.kt` | Tugas + filter + bottom sheet | Menunjukkan bagaimana “task” disintesis di UI (`AgentTaskRecord`) dan makna setiap filter |
| `app/src/main/java/com/example/ui/memory/MemoryScreen.kt` | 5 kategori memori + dialog konfirmasi | Kepadatan data, aksi per item, dan dua `AlertDialog` konfirmasi |
| `app/src/main/java/com/example/ui/settings/SettingsScreen.kt` | 10 section konfigurasi (1209 baris) | Semua kontrol konfigurasi, pola form, dan titik paling padat dalam aplikasi |
| `app/src/main/java/com/example/ui/gateway/WhatsAppGatewayScreen.kt` | Onboarding WhatsApp (QR/pairing), service, test kirim, log | Satu-satunya flow “setup” yang nyata + semua state koneksi |
| `app/src/main/java/com/example/ui/debug/DeveloperDebugScreen.kt` | Diagnostik, console, Tool Registry, raw log | Fitur power-user yang harus tetap ada tapi tidak mendominasi |
| `app/src/main/java/com/example/ui/terminal/TerminalScreen.kt` | Shell interaktif | Konsol interaktif; pola log/terminal |
| `app/src/main/java/com/example/ui/browser/BrowserScreen.kt` | WebView + banner serah terima captcha/2FA | Satu-satunya handoff manusia–agent yang tampil di UI |
| `app/src/main/java/com/example/ui/components/CommonComponents.kt` | `AgentStatusBadge`, `ConnectionStatusBadge`, `SectionHeader`, `CompactCard`, `EmptyStateCard` | Inti “design system” saat ini; sumber label status hardcoded |
| `app/src/main/java/com/example/ui/theme/{Color,Theme,Type}.kt` | Palet, ColorScheme, typography | Semua token warna/tipografi yang ada |
| `app/src/main/java/com/example/wagateway/WaGatewayViewModel.kt` | State holder tunggal (±50 StateFlow) | Peta lengkap state yang bisa dipakai UI + semua aksi yang tersedia |
| `app/src/main/java/com/example/wagateway/WaGatewayManager.kt` | Transport WhatsApp (singleton) | State koneksi/QR/pairing, `messages`/`mediaMessages`, kirim & edit pesan, typing, mark-read |
| `app/src/main/java/com/example/agent/bridge/WhatsAppAgentBridge.kt` | Orkestrasi agent ↔ WhatsApp | Perilaku nyata: auto-reply, ack→edit bubble, whitelist, command chat, media, sub-agent, scheduler, tool registry |
| `app/src/main/java/com/example/agent/loop/AgentLoop.kt` | State machine + `AgentState`/`isBusy` + log aktivitas | Sumber semua status yang tampil di UI (badge, bubble, panel Developer) |
| `app/src/main/java/com/example/agent/chat/ChatCommandHandler.kt` | Perintah chat `/help /status /whitelist /approve …` | Satu-satunya “UI alternatif” untuk beberapa fitur; menentukan bahwa sejumlah kemampuan tidak punya layar |
| `app/src/main/java/com/example/agent/storage/db/AgentDatabase.kt` | Skema + migrasi 1→5 | Data apa yang benar-benar bertahan dan apa artinya untuk desain |
| `app/src/main/java/com/example/agent/storage/entity/*.kt` | 9 entity | Atribut yang bisa ditampilkan/diedit (termasuk yang belum dipakai UI: `title`, `STATUS_CANCELLED`, mode `PENDING`) |
| `app/src/main/java/com/example/agent/tool/ToolRegistry.kt` + `tool/*.kt` | Daftar tool & permission | Menentukan kemampuan yang bisa “dijanjikan” UI (dan yang belum ada) |
| `app/src/main/java/com/example/agent/scheduler/SchedulerEngine.kt` + `scheduler/SchedulerWork.kt` | Penjadwalan 60 s + WorkManager 15 menit | Menentukan akurasi klaim “terjadwal” di UI |
| `app/src/main/java/com/example/agent/browser/BrowserAutomationManager.kt` | Handoff captcha/2FA (`UserActionRequest`, timeout 6 menit) | Menentukan kebutuhan UX pada layar Browser |
| `app/src/main/java/com/example/agent/terminal/TerminalManager.kt` | `TerminalState` + marker exit code/cwd | Bentuk data yang harus dirender layar Terminal |
| `app/build.gradle.kts` | minSdk/targetSdk, ABI, build types, dependency aktual | Batasan platform + daftar library yang benar-benar tersedia (tanpa image loader/navigation) |
| `app/src/main/AndroidManifest.xml` | Activity tunggal, service foreground, permission | Batasan permission & lifecycle |
| `DOC/redesign.md` | Brief redesign UI yang pernah dipakai (aturan: bahasa ID, hierarchy, larangan UI palsu) | Menjelaskan niat desain yang sudah ada & kriteria yang diharapkan dari UI |
| `DOC/list-fitur.md` | Audit status fitur (paling dekat dengan kode, meski ada bagian usang) | Peta fitur ✅/🟡/❌ yang berguna saat memutuskan apa yang boleh ditampilkan |
| `README.md` | Ringkasan arsitektur, cara pakai, batasan yang diketahui | Konteks produk & daftar batasan jujur |
| `DOC/reference/{browser-automation.md, terminal/builtin-terminal.md, linux-sandbox/*}` | Dossier referensi fitur (termasuk yang belum ada) | Memisahkan yang sudah ada dari yang masih spesifikasi |
| `app/src/test/**` (18 file, `FakeDaos.kt`, `GreetingScreenshotTest.kt`) | Unit test & satu test UI (+1 file androidTest) | Menunjukkan perilaku yang dianggap kontrak; `testTag` di UI untuk pengujian |

---

## 20. AI Design Context

Jawaban langsung untuk 11 pertanyaan yang diminta.

1. **What is this app?**
   Aplikasi Android native (Kotlin + Jetpack Compose, Material 3, minSdk 24) bernama **WA Gateway —
   Personal AI Agent via WhatsApp**. Agent AI pribadi yang hidup di perangkat; WhatsApp adalah salah
   satu channel (via Go/whatsmeow in-process) dan aplikasi menyediakan chat langsung, tool, memori,
   scheduler, terminal, dan browser automation.

2. **What is the user trying to accomplish?**
   Menautkan WhatsApp sekali, membuat agent membalas chat yang masuk secara otomatis (dengan memori
   dan tool), memberi tugas dari chat internal aplikasi, mengatur siapa yang boleh bicara dan aksi
   apa yang butuh izin, serta memantau/mengendalikan pekerjaan agent (live, terjadwal, sub-agent,
   memory, skill) dan biaya/kualitas model.

3. **What are the primary workflows?**
   (a) Menautkan WhatsApp (QR/pairing) → (b) Chat langsung dengan agent → (c) Auto-reply pesan
   WhatsApp masuk → (d) Approval aksi destruktif → (e) Meninjau & menyimpan memori/pelajaran →
   (f) Memantau & mengendalikan tugas terjadwal/sub-agent → (g) Menjalankan perintah di Terminal →
   (h) Serah terima captcha/2FA di Browser → (i) Diagnosa model di Developer.

4. **What screens currently exist?**
   Shell `MainAppScreen`; 5 tab: `HomeScreen` (Beranda), `ChatScreen`, `TasksScreen`, `MemoryScreen`,
   `SettingsScreen`; 4 sub-screen: `WhatsAppGatewayScreen`, `DeveloperDebugScreen`, `TerminalScreen`,
   `BrowserScreen`. Overlay: `ModalBottomSheet` detail task, 2 `AlertDialog` konfirmasi,
   `DropdownMenu` opsi sesi, banner feedback & banner handoff browser, notifikasi foreground.

5. **How are screens connected?**
   Lewat state di `MainAppScreen` (`currentTab` + `currentSubScreen`), bukan route/NavHost. Bottom
   navigation 5 tab; sub-screen menggantikan seluruh shell (bottom bar hilang) dan punya `TopAppBar`
   dengan tombol back. Cross-link nyata: Home → Chat/Tugas/Pengaturan/Gateway; Memori → Sessions →
   “Buka Chat”; Pengaturan → Gateway/Terminal/Browser/Developer. Back sistem: sub-screen → level tab,
   lalu Beranda, lalu keluar.

6. **What data/state drives the UI?**
   Room (`agent_sessions`, `agent_messages`, `agent_configs`, `contact_rules`, `memory_items`,
   `approval_requests`, `scheduled_tasks`, `agent_tasks`, `mcp_servers`) dibaca lewat Flow ke
   `WaGatewayViewModel` (±50 StateFlow) → Compose. State non-DB: `TerminalState` (in-memory),
   `agentLogs` (maks 100), `modelMetrics` (maks 50), `sendFeedback`, `qrCode`/`pairingCode`/
   `connectionStatus`, `isConnected`/`isLoggedIn`/`isServiceRunning`, `browserPendingUserAction`,
   `mcpStatuses`, `terminalCapabilities`, dan input form yang belum disimpan.

7. **What interactions are important?**
   Tap (segala aksi), scroll/auto-scroll, teks & keyboard (IME action Send), switch untuk
   fitur berisiko, chip untuk pilihan/filter, dialog konfirmasi (hanya di Memori), bottom sheet
   detail (Tugas), tap untuk salin (bubble, hasil test, kode pairing), dan interaksi manusia pada
   WebView saat browser automation meminta captcha/2FA. **Tidak ada** long-press, swipe, drag,
   pull-to-refresh, undo, atau kontrol pembatalan turn.

8. **What technical constraints affect UI/UX?**
   minSdk 24/target 36, edge-to-edge; single Activity tanpa Navigation library dan tanpa state
   tersimpan; semua string hardcoded (tidak ada lokalisasi); token hanya warna & satu typography;
   dark/light mengikuti sistem (tanpa toggle); tidak ada image loader (media tidak bisa ditampilkan);
   layout hanya portrait ponsel; ABI terbatas + `libgojni.so` wajib; scheduler 60 s/15 menit;
   terminal = shell perangkat; browser = WebView; APK `optimized` belum diuji di perangkat;
   satu test UI (opt-in); switch approval tidak dipersistenkan (14.1 #31).

9. **What UI is already implemented?**
   Dashboard Beranda (status agent, switch auto-reply, kartu WhatsApp/Model/Keamanan, task aktif,
   aktivitas terkini), Chat lengkap dengan multi-sesi & indikator berpikir, Tugas dengan 6 filter +
   kontrol task terjadwal + detail bottom sheet, Memori 5 kategori dengan input fakta &
   approve/reject learning, Pengaturan 10 section lengkap (provider, persona, saluran, keamanan &
   approval, memori, vision, terminal, browser, MCP, developer), Gateway (QR/pairing/service/test/
   log), Developer (diagnostik, console, tool registry, raw log, probe, metrik), Terminal interaktif,
   Browser dengan banner handoff.

10. **What UI is missing or incomplete?**
    Lampiran media keluar & tampilan media masuk; daftar riwayat media/pesan transport; pencarian di
    aplikasi; editor rantai fallback model; toggle tool individual; pembuatan/edit task terjadwal
    dari UI; cancel sub-agent; riwayat approval; pengelolaan/isi skill; edit memori; rename sesi;
    mode gelap manual; multi-agent; ekspor/backup; pengaturan notifikasi; kontrol hentikan turn;
    konfirmasi untuk sebagian besar aksi destruktif; feedback lokal di layar selain Pengaturan/
    Gateway (lihat bagian 14 & 16).

11. **What parts require special UX consideration?**
    (a) Menyatukan konsep “task” yang kini bercampur 5 sumber; (b) membuat approval & status
    “menunggu pengguna” mudah ditemukan (kini di Pengaturan, sedangkan aksinya di WhatsApp);
    (c) dua kanal input (WhatsApp vs chat internal) dengan identitas sesi berupa JID mentah;
    (d) aksi destruktif tanpa undo/konfirmasi konsisten; (e) Pengaturan 10 section yang padat;
    (f) lapisan teknis 12 `AgentState` & notifikasi English-vs-Indonesia; (g) penjelasan batasan
    (toolchain perangkat, media tanpa transkripsi, browser WebView, latar belakang 15 menit) tanpa
    menyesatkan; (h) layar Terminal/Browser yang memuat konten non-Compose (log monospace, WebView); (i) konfigurasi keamanan yang tidak sepenuhnya bertahan (approval
    ter-reset, 14.1 #31) dan satu state `showApiKey` dipakai bersama (14.2 #9).

---

## 21. Design Handoff Notes

**Design agent should know:**

- Ini **aplikasi Android native Compose**, bukan web/mobile lintas platform: Material 3, minSdk 24,
  edge-to-edge, satu Activity, satu ViewModel (`WaGatewayViewModel`), tanpa NavHost dan tanpa route.
- Produk ini **“agent pribadi” dengan WhatsApp sebagai salah satu kanal**, bukan aplikasi chat biasa
  dan bukan aplikasi developer-tool. Hierarki yang sudah disepakati secara implisit
  (`DOC/redesign.md`): status agent → percakapan → aktivitas/task → model → memori → koneksi
  WhatsApp → developer tools paling akhir.
- **Data sudah nyata, tidak ada dummy.** Jangan mengusulkan layar yang menampilkan data contoh, dan
  jangan menampilkan fitur yang belum ada (sandbox Linux, STT, MCP Resources, Anthropic native,
  GeckoView) sebagai UI palsu — README & `DOC/redesign.md` melarangnya secara eksplisit.
- **Kanvas layar yang harus dihormati**: `HomeScreen`, `ChatScreen`, `TasksScreen`, `MemoryScreen`,
  `SettingsScreen` (tab) dan `WhatsAppGatewayScreen`, `DeveloperDebugScreen`, `TerminalScreen`,
  `BrowserScreen` (sub-screen); overlay yang sudah ada: `ModalBottomSheet` (task detail),
  `AlertDialog` (konfirmasi sesi), `DropdownMenu` (opsi sesi), banner feedback, banner handoff
  browser, notifikasi foreground. Nama layar, `testTag`, dan label yang ada sekarang tercantum di
  bagian 5 dan 8 — gunakan nama itu agar diskusi tidak ambigu.
- **Bahasa UI sekarang bercampur ID/EN**; brief desain yang ada (`DOC/redesign.md`) menetapkan Bahasa
  Indonesia untuk label utama dengan istilah teknis (Agent, Model, Provider, Tool, Session, Task,
  API, MCP) tetap Inggris. Keputusan arah bahasa adalah keputusan desain, bukan fakta.
- **Perilaku yang tidak boleh dipatahkan desain** (bukan sekadar preferensi): auto-reply hanya untuk
  chat pribadi; pesan grup diabaikan; whitelist dicek sebelum pesan diproses; tool `CONFIRM` hanya
  lewat approval dan approval dijawab dari chat/daftar pending (bukan dialog desktop); API
  key/password default tersembunyi; scheduler minimum 60 detik (15 menit saat proses mati);
  batas iterasi tool 4 (maks 10); output tool dipotong 4.000 karakter; memori yang disuntikkan
  ke prompt dibatasi ~1.200 karakter per bagian; handoff captcha/2FA timeout 6 menit; approval
  kedaluwarsa 24 jam.
- **Satu switch tidak jujur terhadap data**: switch approval terlihat seperti setelan persisten, tetapi tidak punya kolom di Room dan kembali ON setelah restart; `_whitelistEnabled` adalah state mati (14.1 #31–#32). Jangan perlakukan semua switch sebagai sumber kebenaran persisten.
- **Batas teknis yang mengubah opsi desain**: tidak ada image loader (tidak bisa menampilkan
  gambar/thumbnail tanpa menambah dependensi), tidak ada chart, tidak ada Notification khusus
  per-fitur, tidak ada deep link, tab/sub-screen tidak persisten setelah proses mati, dan seluruh
  teks ada di kode Kotlin (bukan resource).
- **Perangkat & performa**: ukuran APK didominasi native Go (3 ABI); build `optimized` (R8) sudah
  dibuat CI tetapi **belum dijalankan di perangkat**, dan `release` masih tanpa minifikasi. Start
  dingin Compose belum dioptimasi (baseline profile = backlog). Jangan mengasumsikan anggaran
  performa tanpa verifikasi.
- **Test yang tersedia**: 18 file unit test JVM/Robolectric (+1 file androidTest); hanya satu test Compose (opt-in) yang
  merender teks contoh, jadi **tidak ada jaring pengaman visual**. Banyak `testTag` sudah tersedia
  (`nav_tab_*`, `home_*_card`, `chat_input_field`, `chat_send_button`, `memory_input`,
  `memory_save_button`, `memory_item_*`, `memory_session_*`, `task_item_*`, `scheduled_task_*`,
  `settings_*`, `debug_*`, `terminal_input`, `terminal_run`, `browser_user_action_*`) — berguna bila
  desain baru diuji otomatis.
- **Dokumentasi internal tidak sepenuhnya sinkron dengan kode**; bila ragu, periksa kode
  (`DOC/list-fitur.md` beberapa bagian sudah usang, dan README menyebut JDK 17 sedangkan build file
  memakai Java 11).
- **Yang belum bisa dipastikan dari repository** ada di bagian 22 — jangan mengisi celah itu dengan
  asumsi; mintakan konfirmasi bila hal tersebut menentukan keputusan desain.

---

## 22. Confidence and Unknowns

### 22.1 Confirmed (dapat dibuktikan langsung dari repository)

- Nama, jenis, platform, dan arsitektur aplikasi (Compose, single Activity, Room, Go/whatsmeow AAR,
  minSdk 24 / target 36, ABI terbatas) — `build.gradle.kts`, `AndroidManifest.xml`, `README.md`.
- Daftar 10 layar/permukaan utama beserta file & composable-nya, dan daftar `MainTab`/`SubScreen`.
- Navigasi berbasis state (`currentTab`, `currentSubScreen`) + `BackHandler`; `SubScreen.TaskDetail`
  tidak dipakai; tidak ada NavHost/deep link.
- Peta state: ±50 `StateFlow` di `WaGatewayViewModel`; 9 tabel Room; migrasi 1→5.
- Perilaku agent yang memengaruhi UI: ack `⏳` → edit bubble, typing indicator 8 detik, whitelist
  dicek sebelum pemrosesan, pesan grup diabaikan, auto-reply default **off**, browser default
  **off**, terminal default **on**, echo fallback default **on**, long-term memory & auto-compact
  default **on**, nilai default konfigurasi di bridge (dipakai bila field belum ada di Room):
  `autoReply = false`, `echoFallback = true`, `whitelistMode = false`, `longTermMemory = true`,
  `autoCompact = true`, `maxContextMessages = 30`, `approval = true` (**TIDAK dipersistenkan** —
  tanpa kolom di `AgentConfigEntity`, ter-reset ke `true` tiap restart; lihat 14.1 #31),
  `terminalEnabled = true`, `browserEnabled = false`, `autoReflect = false` (interval 6 jam).
- Alur utama: pairing WhatsApp, chat langsung, auto-reply WhatsApp, approval destruktif, terminal,
  handoff browser, memori & learning, tugas terjadwal & sub-agent, diagnosa model.
- Design system minimal: dua `ColorScheme` (light/dark) dengan palet emerald + WhatsApp green,
  typography hanya `bodyLarge` di-override, radius & elevasi ad-hoc, satu animasi kustom
  (pulse pada bubble “berpikir”).
- Tidak adanya: long-press/swipe/drag/pull-to-refresh/search UI/snackbar/FAB/grid,
  `rememberSaveable`, progress bar, relative time, undo, cancel turn, image loader, lokalisasi,
  chart, multi-agent UI.
- Dependency yang benar-benar dipakai vs yang hanya ada di version catalog.
- Daftar masalah UI yang disajikan di bagian 14.1 (semuanya dengan bukti file/bagian kode).
- Fitur backend tanpa UI (bagian 16.1) — diverifikasi lewat grep pemanggil (`sendImage`,
  `mediaMessages`, `markPending`, `messages`, dll.).

### 22.2 Likely (kesimpulan kuat yang tidak dinyatakan eksplisit di kode)

- Bahwa aplikasi ini dipakai **satu pengguna teknis** di satu perangkat dengan satu akun WhatsApp.
- Bahwa `HomeScreen` adalah “layar utama yang dimaksud” dan hierarchy-nya mengikuti brief
  `DOC/redesign.md` (status → percakapan → aktivitas → model → memori → koneksi → developer).
- Bahwa layar Tugas sengaja disintesis dari state lain (komentar kode menyatakan “no placeholder/
  dummy tasks are invented here”).
- Bahwa pesan feedback yang tidak terlihat (di layar selain Pengaturan/Gateway) adalah kendala nyata
  bagi pengguna, bukan hanya kekurangan kosmetik.
- Bahwa `WaMessage`/`mediaMessages` tinggal menunggu permukaan UI (belum ada pemakainya, bukan
  berarti sudah dihapus dari rencana).
- Bahwa performa start dingin & rendering daftar panjang belum pernah diukur (dokumen menyatakan
  baseline profile masih backlog).

### 22.3 Unknown / perlu dikonfirmasi

- **Nilai default runtime yang sesungguhnya** untuk `isAgentAutoReply`, `whitelistMode`, dan
  lainnya pada instalasi bersih: default ada di kode bridge (lihat 22.1), tetapi README
  menyatakan auto-reply “diaktifkan pengguna” dan tidak ada dokumentasi state awal setelah
  instalasi bersih. Untuk `approvalEnabled` sudah pasti **tidak dipersistenkan** (14.1 #31).
  Perlu konfirmasi dengan menjalankan aplikasi.
- **Tampilan visual sebenarnya di perangkat** (kepadatan, overflow, keyboard di layar kecil,
  perilaku edge-to-edge, tampilan WebView, apakah ada teks yang terpotong). Tidak dapat dipastikan
  dari source code; tidak ada artefak screenshot layar nyata di repo (hanya `greeting.png` dari test
  contoh).
- **Perilaku tombol back pada sub-screen Browser** saat ada `pendingUserAction`
  (membatalkan handoff atau tidak) — tidak ada penanganan khusus di kode layar.
- **Apakah `AgentState.COMPLETED`/`FAILED` benar-benar terlihat cukup lama** untuk ditampilkan badge
  (transisinya dikelola `AgentLoop`, tidak ada durasi tahan yang eksplisit).
- **Perilaku multi-percakapan bersamaan**: `AgentLoop` memakai mutex per percakapan dan
  `ToolConversation` untuk chat internal; apakah dua turn (WhatsApp + chat internal) benar-benar
  terlihat paralel di UI belum diverifikasi.
- **Apakah `mcpStatuses` selalu terisi** untuk ditampilkan di daftar server MCP.
- **Dukungan tablet/lipat/landscape** — tidak ada konfigurasi, tidak ada bukti pengujian.
- **Rencana jangka panjang yang tidak terdokumentasi**: apakah `SubScreen.TaskDetail`,
  `ContactRuleEntity.MODE_PENDING`, `AgentTaskEntity.STATUS_CANCELLED`, dan `WaMessage`/
  `mediaMessages` akan dipakai (kode menyisakannya, tapi tidak ada catatan rencana).
- **Angka performa nyata** (start dingin, waktu render, penggunaan memori, konsumsi baterai untuk
  gateway + WebView) — tidak ada pengukuran di repo; dokumen hanya menyebut hasil build CI.
- **Apakah proyek ini akan tetap single-user** atau menuju multi-agent/multi-akun (mempengaruhi
  seluruh model informasi UI).
- **Target rilis**: `applicationId` masih bawaan template dan README menyarankan menggantinya
  sebelum rilis publik; status rilis/publikasi **Unknown**.

---

## 23. Self-Audit (checklist yang diminta)

| # | Pertanyaan | Status |
|---|---|---|
| 1 | Seluruh screen penting tercatat? | ✅ 10 permukaan utama + 7 overlay/dialog/notifikasi, dengan file & composable sebenarnya |
| 2 | Seluruh workflow utama tercatat? | ✅ 9 flow (pairing, chat internal, auto-reply WhatsApp, approval, terminal, handoff browser, memori, tugas, diagnosa) |
| 3 | Navigasi dijelaskan? | ✅ Peta pohon, tabel From/Action/To/Condition, perilaku back sistem, dan daftar navigasi yang tidak ada |
| 4 | State penting dijelaskan? | ✅ StateFlow per kelompok, 9 tabel Room, jenis state (loading/error/empty/selection/persistensi) beserta yang tidak ada |
| 5 | Backend/service yang memengaruhi UX dijelaskan? | ✅ 14 dependency dengan dampaknya ke UX + batasan yang diturunkan dari kode |
| 6 | Technical constraints dicatat? | ✅ 20 butir (platform, navigasi, bahasa, token, dependency tidak terpasang, ABI, media, aksesibilitas, test, dll.) |
| 7 | Ada fitur yang diasumsikan tanpa bukti? | ✅ Tidak. Fitur tanpa UI ditandai eksplisit; yang belum ada (sandbox, STT, GeckoView, dll.) dipisahkan sebagai “planned/TODO” |
| 8 | Bagian penting yang masih Unknown? | ✅ Terdaftar di 22.3 (11 butir), termasuk tampilan visual di perangkat dan default runtime |
| 9 | AI lain dapat memahami aplikasi hanya dari dokumen ini? | ✅ Nama layar/file/route/state/aksi nyata, alur end-to-end, dan batasan teknis semuanya ada; bagian 19 menunjuk file untuk pendalaman |
| 10 | Cukup menjadi dasar rekomendasi UI/UX? | ✅ Konteks produk, pengguna, alur, data, state, komponen, masalah nyata, risiko, dan batasan tersedia — tanpa satu pun rekomendasi desain |

**Ringkasan keterbatasan dokumen ini**: audit dilakukan **tanpa menjalankan aplikasi di perangkat**
(tidak ada emulator/perangkat di lingkungan ini dan source code tidak diubah). Seluruh pernyataan
visual bersifat “struktural” (dari kode Compose), bukan hasil observasi tampilan.
