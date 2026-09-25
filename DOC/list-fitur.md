# List Fitur — Status Implementasi (audit terhadap kode)

Legenda:
- ✅ **ADA** — sudah diimplementasikan dan terhubung ke Agent Loop / UI.
- 🟡 **SEBAGIAN** — pondasi/logikanya ada, tetapi belum lengkap atau belum diekspos di UI.
- ❌ **BELUM ADA** — belum diimplementasikan (tidak ditampilkan sebagai UI palsu).

Catatan: status di bawah diverifikasi langsung dari source di `app/src/main/java/com/example/`
dan `go-wagateway/`, bukan dari rencana di dokumen.

---

## 0. Fitur yang diminta sejak awal

- ✅ auto compact & manual compact — otomatis rangkum/compact sesi chat ketika mencapai batas token
  yang ditentukan, atau manual dengan command slash `/compact`
  (`CompactManager`, `ChatCommandHandler.handleCompact`, batas dari Pengaturan `maxContextMessages`).

---

## 1. Gateway WhatsApp (transport)

- ✅ QR login + Pairing Code (`WhatsAppGatewayScreen`, `WaGatewayManager`).
- ✅ Session WhatsApp persisten di app-internal storage (SQLite via whatsmeow, `go-wagateway/store.go`).
- ✅ Auto-connect memakai session tersimpan tanpa scan QR ulang (`Client.HasSession()`).
- ✅ Kirim & terima pesan teks.
- ✅ Foreground service + notifikasi (`WaGatewayService`).
- ✅ Centang biru (MarkRead), indikator typing, bubble "⏳ Sedang berpikir..." yang diedit jadi jawaban.
- ✅ Kontrol akses kontak: whitelist & blacklist per nomor (normalisasi JID).
- ✅ Command chat: `/help /status /whitelist /blacklist /approve /reject /compact /remember /learning`.
- ❌ Auto-reply grup (sengaja dinonaktifkan; hanya chat pribadi).

## 2. Agent Core & loop

- ✅ Agent Loop sebagai state machine (`IDLE, THINKING, CALLING_TOOL, WAITING_TOOL, DELEGATING,
  WAITING_SUB_AGENT, REFLECTING, RETRYING, FALLBACK, WAITING_APPROVAL, COMPLETED, FAILED`).
- ✅ Sesi/percakapan dipisah per chat (`conversationId`), tidak tercampur.
- ✅ Persistensi pesan ke Room (`agent_sessions`, `agent_messages`); riwayat bertahan setelah restart.
- ✅ Klasifikasi error, retry + exponential backoff, fallback model, budget anti-infinite-loop.
- ✅ Antrian per-percakapan (mutex) supaya pesan masuk bersamaan tidak race.
- ✅ Tool-call loop penuh: model → `tool_calls` → registry → tool → model lagi (batas `maxToolIterations`).
- ✅ API key terenkripsi (Android Keystore, AES-256-GCM) — `SecretCipher`.

## 3. Provider & routing model

- ✅ Provider OpenAI-compatible (`OpenAiCompatibleProvider`) — OpenAI, OpenRouter, LM Studio, Ollama, gateway internal.
- ✅ Fallback otomatis antar model (fallback chain dari `ModelRouter`) + retry per model.
- ✅ Model probe `"1 + 1 ="` untuk cek ketersediaan & latensi (`ModelRouter.probeModel`).
- 🟡 Provider Gemini native — **hanya untuk vision** (`GeminiVisionProvider`); teks tetap lewat
  endpoint OpenAI-compatible.
- 🟡 Model probe belum punya tombol di UI (logikanya ada di `ModelRouter`, belum diekspos ke layar).
- 🟡 Konfigurasi combo/urutan fallback belum ada formnya di Pengaturan (chain di-set dari kode/router).
- ❌ Provider Anthropic Claude native.
- ✅ Keys pool: field "Keys Pool" di Pengaturan (satu kunci per baris), dipakai bergiliran dengan
  kunci utama; rotasi otomatis saat kunci kena 401/402/403/429, sedangkan error 5xx/timeout tetap
  ditangani retry/fallback. Kunci tambahan disimpan terenkripsi (`SecretCipher`).

## 4. Tool & kemampuan agent

- ✅ Tool System: `Tool` (id/name/description/inputSchema/permission/execute), `ToolRegistry`, `ToolResult`.
- ✅ `current_time` + **kesadaran waktu lokal**: blok "Waktu sekarang" (hari, tanggal, jam, offset
  UTC, nama zona perangkat) disuntik ulang ke system prompt di setiap turn, jadi agent tahu jam
  berapa sekarang tanpa perlu memanggil tool lebih dulu.
- ✅ File I/O **hanya di dalam workspace**: `list_files, read_file, write_file, append_file,
  move_path, copy_path, delete_path, make_directory` (`delete_path` = CONFIRM/destruktif).
- ✅ Workspace isolasi path absolut + guard anti `..` dan symlink escape (`Workspace.resolve`).
- ✅ Web search & web fetch (read-only, AUTO_SAFE) — `web_search`, `web_fetch`.
- ✅ Network tool memakai scraping HTML DuckDuckGo.
- ✅ Memori tool: `remember`, `recall_memory`.
- ✅ Sub-agent tool: `delegate_task`.
- ✅ Refleksi: `reflect` (menyimpan pelajaran sebagai kandidat).
- ✅ Council: `council` (2 debater + 1 moderator, dibatasi timeout 120 detik).
- ✅ Scheduler tool: `schedule_task`.
- ✅ Permission tool (SAFE / AUTO_SAFE / CONFIRM) + approval destruktif via chat/UI.
- ✅ Unified search: `search` (chat / memory / tasks / files / tools) — lihat bagian 5.
- ✅ **Terminal built-in**: `run_command` (shell perangkat `/system/bin/sh` + toybox, `cwd` di workspace)
  dan `terminal_info` (laporan biner yang benar-benar ada: sh/bash/curl/wget/python3/git/gh/node).
  Approval **per perintah** lewat `ShellPolicy`: install/upgrade/hapus paket, `rm`, `sudo`, `kill`,
  `git push`, tulis di luar workspace → `PENDING_APPROVAL` + `/approve`.
- ✅ **Kirim file hasil agent ke chat** (`send_file_to_chat`: screenshot sebagai foto, lain sebagai dokumen).
- ✅ **Browser automation**: `browser_open`, `browser_read`, `browser_click`, `browser_type`,
  `browser_scroll`, `browser_screenshot`, `browser_login`, `browser_ask_user`, `browser_logout`
  (engine Android WebView di balik antarmuka `BrowserEngine`).
- ✅ Serah terima captcha/2FA ke pengguna (`browser_ask_user` menunggu, bukan mengarang hasil).
- ✅ **Sesi login browser persisten**: cookie disimpan `CookieManager` aplikasi di data privat app,
  bukan di objek WebView. Login sekali (lewat `browser_login` atau manual di tab Browser) tetap
  dipakai pada panggilan tool berikutnya, saat WebView dibuat ulang, dan setelah aplikasi
  ditutup–dibuka lagi; `browser_type` dengan `submit` / `browser_click` memanggil
  `CookieManager.flush()` agar cookie langsung tertulis ke disk. Sesi hanya dihapus oleh
  `browser_logout`, yang membersihkan cookie + cache + form data + history.
- 🟡 Web search baru **DuckDuckGo**; belum ada opsi Bing.
- 🟡 `read_file` memotong di 16.000 karakter; pembacaan bertahap (offset/limit) belum ada.
- 🟡 Browser memakai **WebView Android**, belum GeckoView (antarmuka `BrowserEngine` siap ditukar).
- ✅ Shell interaktif di aplikasi (layar Terminal): shell persisten, `cd` persist, exit code per perintah, cap 1.500 baris.
- ❌ Sandbox Linux penuh (proot/rootfs) supaya `apt`/`pip` nyata tersedia tanpa toolchain pengguna.
- ❌ Skill markdown universal (hermes/openclaw/claude code) — tab Skills masih empty state.
- ❌ MCP connector (HTTP/HTTPS/SSE: name + base URL + headers).

## 5. Memori, RAG, Knowledge Base & learning

- ✅ Memori jangka panjang 3 lapisan dalam satu tabel `memory_items`: EPISODIC / KNOWLEDGE / LEARNING.
- ✅ RAG leksikal on-device (`MemoryRetriever`: stopwords ID+EN, skor overlap + boost kebaruan ≤25%).
- ✅ Context Manager menyuntikkan memori relevan + pelajaran aktif ke system prompt per-turn.
- ✅ Episodic memory dari ringkasan auto-compact (`CompactManager`).
- ✅ Learning pipeline: `candidate → active` (`/learning approve|reject`, tombol di layar Memori).
- ✅ Refleksi/pelajaran dari pengalaman (`reflect`) bisa dipakai lagi di turn berikutnya.
- ✅ Knowledge memory (fakta yang diminta diingat) via `remember` / `/remember`.
- 🟡 "Memory episodic + refleksi berkala (generative agents)": refleksi berjalan saat model memanggil
  tool `reflect`, belum ada loop refleksi otomatis/periodik terjadwal.
- 🟡 Knowledge Base = memori KNOWLEDGE (belum ada import/export dokumen eksternal).
- ✅ Unified search `search(query, scope, limit)` (`builtin.search`, AUTO_SAFE, lokal tanpa jaringan)
  untuk scope `chat / memory / tasks / files / tools` (plus `all`). Ranking leksikal: frasa di judul
  > frasa di isi > kata per kata, seri diputus yang terbaru; satu huruf tidak menghasilkan apa-apa
  supaya tidak membanjiri konteks. Hasil menyertakan id/path untuk ditindaklanjuti.
  Catatan jujur: scope `skills` belum ada karena Skills (markdown) belum diimplementasikan.
- ❌ Learning dalam format skill markdown / aturan berkas yang bisa dibaca ulang sebagai prosedur.

## 6. Multi-agent collaboration

- ✅ Sub-agent latar belakang: `delegate_task` langsung mengembalikan id, agent utama **tidak menunggu**,
  hasil dikirim ke chat sebagai pesan baru (`SubAgentManager`, tabel `agent_tasks`).
- ✅ Sub-agent punya nama, tugas, persona/system prompt, model sendiri, dan flag tools.
- ✅ Status sub-agent QUEUED/RUNNING/COMPLETED/FAILED terlihat di tab Tugas.
- ✅ Council mode (beberapa persona berdebat, agent utama sebagai moderator).
- ✅ Sub-agent media/vision: media masuk dianalisis provider vision (Gemini native atau OpenAI-compatible).
- 🟡 Pemilihan model vision untuk media masih manual (diisi di Pengaturan), belum otomatis
  "agent utama tidak support vision → delegasi sendiri".

## 7. Task & scheduler

- ✅ Task system: tabel `agent_tasks` (sub-agent) + `scheduled_tasks` (terjadwal).
- ✅ Scheduler engine: `interval:<detik>` (min 60 detik, maks 30 hari), ticker 60 detik.
- ✅ Eksekusi task terjadwal lewat AgentLoop, hasil dikirim ke chat asal, status COMPLETED/FAILED dicatat.
- ✅ Jadwal berikutnya ditentukan **sebelum** eksekusi (anti dobel-jalan).
- ✅ UI Tugas: filter Scheduled / Sub-agent / Completed / Failed + aksi pause, resume, run now, delete.
- 🟡 Scheduler berjalan dari proses aplikasi; kalau proses dimatikan sistem, task baru jalan lagi
  setelah aplikasi dibuka (WorkManager/foreground penuh belum dipakai).

## 8. Media WhatsApp

- ✅ Pesan media masuk: gambar/video dianalisis provider vision, dokumen teks dibaca langsung.
- ✅ Audio/voice note diterima (dicatat), **belum ditranskripsi** (butuh provider STT).
- ✅ Pengiriman media keluar: gambar, dokumen, audio/voice note, video (`WaGatewayManager`).
- ✅ Bridge mengunduh + mendekripsi bytes media (`DownloadMedia`).
- 🟡 Video besar hanya dianalisis dari metadata (batas inline Gemini ±20 MB).
- 🟡 Kirim media dari UI belum ada (API siap, layar chat belum memakainya).

## 9. Dashboard, log & observability

- ✅ Dashboard status agent realtime (Home: state, model, WhatsApp, task aktif, aktivitas terkini).
- ✅ Log aktivitas agent (`AgentLoop.log`, event `MODEL_REQUEST / FALLBACK_STARTED / TOOL_CALLS_REQUESTED /
  TOOL_RESULT / RETRY_STARTED / MODEL_SUCCESS / AGENT_FAILURE / ...`).
- ✅ Log peralihan model (retry & fallback) tampil sebagai aktivitas + di Developer → raw logs.
- ✅ Log penggunaan tool oleh model (nama tool, latensi, hasil).
- 🟡 Token usage dihitung & dicatat di log, tetapi belum ditampilkan sebagai angka di dashboard.
- 🟡 Latensi per request tercatat di log, tetapi belum ada panel metrik di dashboard.
- 🟡 Developer & Debug: interactive test console, Tool Registry, diagnostics, raw logs (belum ada tombol probe latensi).

## 10. UI / layout aplikasi

- ✅ Beranda (dashboard agent), Chat, Tugas, Memori, Pengaturan, Gateway, Developer.
- ✅ Playground chat dengan agent/model (tab Chat) + interactive test console di Developer.
- ✅ Layar Memori: Sessions / Episodic / Knowledge / Learning / Skills (Skills masih empty state jujur).
- ✅ Pengaturan: Model & Provider, Persona, Saluran, Keamanan & Akses, Memori, Vision, Terminal, Browser, Developer.
- ✅ Empty state jujur untuk fitur yang belum ada (tidak ada UI palsu).
- ✅ Layar **Terminal** built-in (shell interaktif + pindai toolchain + hentikan shell).
- ✅ Layar **Browser** (sesi live yang sama dengan agent + banner “agent menunggu Anda” untuk captcha/2FA).
- ✅ Pengaturan → Browser: simpan akun per situs (password terenkripsi) + User-Agent kustom.
- ❌ Sandbox Linux penuh (proot) dan MCP.

---

## Ringkasan prioritas (permintaan 1–6)

| # | Prioritas | Status |
|---|---|---|
| 1 | Keamanan & filter kontak WhatsApp (whitelist/blacklist) | ✅ |
| 2 | Long-term memory, RAG & knowledge base | ✅ (knowledge base = memori KNOWLEDGE) |
| 3 | Eksekusi tools/alat (tanpa human-in-the-loop, tapi approval untuk destruktif) | ✅ |
| 4 | Task scheduler & background cron | ✅ (belum tahan proses dimatikan OS) |
| 5 | Dukungan media WhatsApp (gambar, audio/voice note, dokumen) | ✅ (transkripsi audio belum) |
| 6 | Multi-agent collaboration & self-reflection (sub-agent latar belakang) | ✅ |
| — | Terminal built-in (agent bisa curl/wget/bash/python) | ✅ (shell perangkat; sandbox proot belum) |
| — | Browser automation (post ke media sosial, captcha/2FA ke pengguna) | ✅ (WebView Android, bukan GeckoView) |
| — | Kirim hasil kerja agent (file/screenshot) ke chat WhatsApp | ✅ |
| — | Keys pool (rotasi banyak API key saat rate limit/quota) | ✅ |
| — | Unified search lintas memori, chat, task, file & daftar tool | ✅ |

## Yang belum ada (dan sengaja tidak dipalsukan)

Sandbox Linux penuh (proot/rootfs), MCP connector, skill markdown universal,
provider Anthropic native, transkripsi audio, transkrip media besar.

Dokumen referensi untuk yang belum ada sudah disimpan di `DOC/reference/`
(`linux-sandbox/`, `terminal/builtin-terminal.md`, `browser-automation.md`).

## Catatan waktu & sesi

* **Waktu:** blok waktu selalu ikut di setiap turn (tidak bergantung pada toggle memori jangka
  panjang) dan dihitung dari `TimeZone.getDefault()` perangkat, sehingga pertanyaan "hari ini hari
  apa?" atau penjadwalan tidak pernah memakai tanggal basi. Tool `current_time` tetap tersedia untuk
  presisi detik atau zona waktu lain.
* **Sesi browser:** persistensi berasal dari cookie store WebView di disk (privat app), bukan dari
  objek WebView-nya, dan hal ini belum diverifikasi di perangkat nyata melintasi restart aplikasi;
  situs yang mengakhiri sesi di sisi server (atau memakai token non-cookie) tetap bisa meminta login
  ulang. Tombol logout di UI dan tool `browser_logout` menghapus sesi secara eksplisit.

## Catatan engine browser

Browser automation berjalan di **WebView Android** di balik antarmuka `BrowserEngine`. GeckoView
belum dipakai karena memerlukan repository Maven Mozilla, kenaikan toolchain Java 17 untuk seluruh
app, dan ±100 MB native library per ABI di atas gateway Go yang sudah ada. Seluruh tool agent hanya
berbicara dengan antarmuka itu, jadi menukar engine (GeckoView, driver lain, atau double untuk test)
tidak menyentuh satu pun tool.
