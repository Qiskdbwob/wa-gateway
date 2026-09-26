# Riset & Audit — Performa, Stabilitas, dan Rencana Fitur

Lingkup sesi ini: (1) APK rilis/teroptimasi supaya tidak lagi bergantung pada build debug,
(2) audit titik yang memperlambat atau menggagalkan fitur yang sudah ada (browser automation,
terminal, agent loop), (3) riset kelayakan untuk fitur besar berikutnya.

Aturan kejujuran dokumen ini: setiap klaim ditandai sumbernya.

- **[kode]** = diverifikasi langsung di repositori ini.
- **[dokumen resmi]** = dari dokumentasi Google/Android (tautan di bagian Sumber).
- **[belum diuji di perangkat]** = hanya bisa dibuktikan dengan menjalankan app di HP; CI di
  lingkungan ini tidak punya perangkat/per-emulator.

---

## 1. Mengapa APK debug terasa lambat

| Penyebab | Dampak nyata | Status |
|---|---|---|
| `isDebuggable = true` | ART menahan banyak optimasi (tidak ada AOT penuh, pemeriksaan debug aktif, JIT profiling lebih konservatif) sehingga **logika Kotlin/Compose berjalan lebih lambat**, bukan sekadar lebih besar. **[dokumen resmi]** | Diperbaiki: build type `optimized` mewarisi `release` (non-debuggable). |
| Tanpa R8 | Seluruh Compose + AndroidX + OkHttp + zxing masuk DEX tanpa penyusutan; lebih banyak kelas = cold start & memory lebih berat, tanpa inlining/optimasi yang dilakukan R8. **[kode]** (`isMinifyEnabled = false`) | Diperbaiki pada `optimized`: R8 + `isShrinkResources = true`. |
| Resource tidak menyusut | Semua drawable/string library ikut ke APK (ukuran + waktu parse resources). | Diperbaiki pada `optimized`. |
| `debugImplementation(compose.ui.tooling)` | Inspector/tooling Compose hanya ada di debug dan menambah kerja saat komposisi pertama. **[kode]** | Tidak ikut ke `optimized`. |
| Wallet ABI: `arm64-v8a + armeabi-v7a + x86_64` | 3 varian `libgojni.so` (Go) ikut ke APK → ukuran install besar, bukan penyebab lambatnya UI. **[kode]** | Dibiarkan (dibutuhkan untuk kompatibilitas device). |

Kesimpulan: pemicu terbesar "terasa lambat" adalah **non-debuggable + R8**, bukan sekadar ukuran
APK. Karena itu dibuat build type `optimized` yang bisa diproduksi CI **tanpa satu pun secret**
(ditandatangani debug key) sehingga bisa dipasang berdampingan dengan build debug untuk
perbandingan langsung.

---

## 2. Yang berubah di sesi ini

| Perubahan | Berkas |
|---|---|
| Build type `optimized` (R8, resource shrinking, non-debuggable, debug-key signing) | `app/build.gradle.kts` |
| Keep rules R8 lengkap + alasan tiap aturan | `app/proguard-rules.pro` |
| Workflow **Optimized APK** (artifact APK + laporan R8, verifikasi R8 benar-benar jalan) | `.github/workflows/optimized-apk.yml` |
| Job AAR Go dijadikan **reusable workflow** (dipakai Build & Optimized APK, satu sumber kebenaran) | `.github/workflows/gateway-aar.yml`, `.github/workflows/build.yml` |
| WebView: prioritas renderer, cookie pihak ketiga, pemulihan saat renderer dimatikan sistem | `agent/browser/WebViewBrowserEngine.kt` |

### 2.1 Keputusan keep-rules R8 (yang paling mudah salah)

Kontrak biner yang **tidak terlihat R8** dan karena itu dikunci eksplisit **[kode]**:

- `wagateway.**` — jembatan Go↔Java dari `gomobile`. Sisi Go memanggil Java lewat JNI dengan nama
  kelas/metode, jadi `Client`, `Wagateway`, dan `WaEventListener` tidak boleh di-rename.
- `class * implements wagateway.WaEventListener` — implementasinya adalah `WaGatewayManager` milik
  app; metodenya dipanggil balik dari Go berdasarkan nama.
- `* extends androidx.work.ListenableWorker` — WorkManager memulihkan worker dari nama kelas yang
  tersimpan di database-nya (`SchedulerWorker`).
- Room (`*_Impl` + `@Entity`), `@JavascriptInterface`, `native <methods>`.

Untuk kode sendiri dipakai `-keep class com.example.** { *; }` — obfuscation dimatikan **secara
sengaja**: nilainya kecil untuk aplikasi satu pengguna, sedangkan risikonya (panggilan balik JNI
dan entry point reflektif yang tidak bisa diuji di perangkat dari CI) besar. Yang tetap bekerja:
penyusutan + optimasi **library** — justru di situ sumber ukuran dan kecepatan.

Konsekuensi jujur: `release` **masih** `isMinifyEnabled = false` supaya jalur rilis bertanda tangan
yang sudah bekerja tidak berubah sebelum APK `optimized` dicoba di HP. Setelah terbukti, satu baris
di `app/build.gradle.kts` bisa mengaktifkan R8 untuk `release`.

### 2.2 Audit WebView — dua celah nyata, sekarang ditutup

1. **Renderer mati = WebView permanen rusak.** WebView merender di proses terpisah; Android boleh
   mematikannya saat memori sempit. Dokumentasi resmi jelas: instance itu **tidak bisa dipakai
   lagi** — harus dilepas dan dibuat ulang. Sebelum ini engine tidak menangani
   `onRenderProcessGone`, jadi automation yang sedang jalan bisa berakhir dengan layar mati.
   Sekarang: lepas dari parent → `destroy()` → buat WebView baru + muat ulang URL terakhir
   (maksimal 3 pemulihan, lalu error dilaporkan apa adanya agar tidak terjadi loop). Cookie ada di
   jar aplikasi, jadi sesi login tetap hidup. **[dokumen resmi] [belum diuji di perangkat]**
2. **Cookie pihak ketiga ditolak.** Login lewat OAuth/SSO yang berjalan di iframe pihak ketiga
   bisa "selesai" tetapi sesi tidak terbentuk. `setAcceptThirdPartyCookies(view, true)` diaktifkan.

Ditambah `setRendererPriorityPolicy(RENDERER_PRIORITY_IMPORTANT, false)` (API 26+) agar renderer
tidak dianggap kandidat pembunuhan saat automation berjalan di latar. **[dokumen resmi]**

Catatan yang **tidak** diubah dan tetap disengaja: Safe Browsing dibiarkan aktif (dokumentasi
menyarankan jangan dimatikan), dan tidak ada `JavascriptInterface` yang dipakai — semua interaksi
lewat `evaluateJavascript`, yang lebih aman.

---

## 3. Riset kelayakan fitur berikutnya

### 3.1 Sandbox Linux penuh (proot + Alpine) — layak, dengan satu syarat arsitektur

Temuan penting untuk `apk add python3 gh curl` (permintaan Anda):

- Sejak Android 10 / targetSdk 29, **menjalankan file dari home directory yang bisa ditulis app
  adalah pelanggaran W^X** dan diblokir; dokumentasi menyuruh app hanya memuat kode biner yang
  tertanam di dalam APK. **[dokumen resmi]**
- Jadi binary `proot`, `busybox`, `proot-distro` tidak boleh disimpan ke `filesDir` lalu
  di-`exec` (cara Termux dulu). Cara yang masih sah: bawa binary **di dalam APK** sebagai
  `jniLibs/<abi>/lib*.so`, karena installer mengekstraknya ke `nativeLibraryDir` yang boleh
  di-`exec`. Praktik ini yang direkomendasikan di diskusi publik Android Q. **[sumber publik]**
- Konsekuensi: rootfs Alpine + proot harus diunduh **saat runtime** (tidak masuk APK), sedangkan
  launcher-nya (`libproot.so`, `libbusybox.so`) ikut APK. Bit `exec` di rootfs tetap diperlukan,
  dan itu di dalam rootfs yang dikelola proot — bukan app mem-`exec` langsung.
- Batas praktis: APK/AAB punya batas ukuran; rootfs Alpine minimal ±3–5 MB plus `python3`/`gh`
  (puluhan MB) lebih baik diunduh sekali lalu di-cache di `filesDir`.
- **Rencana kerja** (Tier 5, belum dikerjakan): 1) modul `sandbox/` dengan unduhan rootfs +
  checksum, 2) `libproot.so`/`libbusybox.so` di `jniLibs`, 3) `TerminalManager` memakai shell
  proot ketika sandbox aktif, 4) `ShellPolicy` tetap sebagai gerbang approval, 5) status jujur di
  UI ("belum pernah diuji di perangkat ARM 32-bit").
- Alternatif yang **ditolak**: Pyodide/`node` WASM (tidak bisa `gh`/`pip` native), atau binary
  statis musl `gh`/python (tidak kompatibel dengan Bionic kecuali static-pie dan tanpa TLS stack
  sistem — rapuh).

### 3.2 Baseline profile (mulai dingin Compose) — optimasi lanjutan

Ukuran APK dan R8 memperbaiki *throughput*; **waktu start dingin** Compose diperbaiki dengan
baseline profile (`androidx.profileinstaller` + profil yang dihasilkan Macrobenchmark di
perangkat). Ini butuh modul `baselineprofile` dan perangkat/emulator, jadi tidak bisa diverifikasi
di CI lingkungan ini → masuk backlog, bukan dikerjakan sekarang. **[dokumen resmi]**

### 3.3 Terminal & agent (audit cepat, sudah sehat)

- Terminal memakai shell perangkat `/system/bin/sh` + toybox: **tidak ada** biaya startup shell
  tambahan; eksekusi satu-shot punya watchdog 30 s dan output di-cap 12.000 karakter sehingga
  tidak bisa mengunci turn. **[kode]**
- `ShellPolicy` hanya gerbang approval (bukan sandbox) dan itu memang desainnya — dokumen ini
  mengulanginya supaya tidak ada ilusi keamanan. **[kode]**
- Scheduler: ticker 60 s + WorkManager periodik 15 menit memakai **satu** implementasi aturan;
  tidak ada duplikasi logika. **[kode]**

---

## 4. Backlog optimasi lanjutan (urut manfaat/biaya)

| # | Item | Manfaat | Biaya | Catatan |
|---|---|---|---|---|
| 1 | Coba APK `optimized` di HP, lalu aktifkan R8 untuk `release` | Rilis resmi ikut cepat & kecil | 1 baris + 1 CI | Perlu Anda menguji **[belum diuji di perangkat]** |
| 2 | Obfuscate bertahap: ganti `-keep com.example.**` jadi `-keep com.example.agent.**` dst. | APK lebih kecil | Rendah tapi berisiko | Baru setelah #1 terbukti |
| 3 | Baseline profile (Macrobenchmark) | Start dingin jauh lebih cepat | Modul baru + perangkat | **[dokumen resmi]** |
| 4 | Sandbox Linux penuh (bagian 3.1) | `gh`, `python3`, `apk` nyata | Tinggi | Butuh uji perangkat |
| 5 | Cache snapshot DOM antar langkah browser | Memotong 1 JS eval per langkah | Sedang | Kesegaran DOM harus dijaga |
| 6 | `armeabi-v7a` diuji / atau dibuang | Ukuran APK turun bila dibuang | Rendah | Belum pernah diuji di ARM 32-bit |

## 5. Yang sengaja TIDAK dilakukan

- **GeckoView** sebagai engine browser: +~100 MB native per ABI, repo Maven Mozilla, dan Java 17
  untuk seluruh app — ditolak sejak awal; antarmuka `BrowserEngine` sudah siap ditukar. **[kode]**
- **Menyalakan R8 langsung di `release`**: mengubah jalur rilis bertanda tangan yang sudah bekerja
  tanpa perangkat untuk menguji hasilnya.
- **Mematikan Safe Browsing** demi "kecepatan": keuntungannya tidak sebanding. **[dokumen resmi]**

---

## Sumber

- Manage WebView objects (Renderer Importance, Termination Handling, Safe Browsing):
  <https://developer.android.com/develop/ui/views/layout/webapps/managing-webview>
- Behavior changes: apps targeting API 29+ (W^X, larangan exec dari home directory):
  <https://developer.android.com/about/versions/10/behavior-changes-29>
- Diskusi publik larangan eksekusi binary di Android Q (cara `nativeLibraryDir`):
  <https://github.com/termux/termux-app/issues/1072>
- Shrink, obfuscate and optimize your app (R8 keep rules):
  <https://developer.android.com/build/shrink-code>
- Baseline profiles:
  <https://developer.android.com/topic/performance/baselineprofiles/overview>
- WorkManager: konsumen R8 untuk worker reflektif:
  <https://developer.android.com/topic/libraries/architecture/workmanager/advanced/custom-configuration>
