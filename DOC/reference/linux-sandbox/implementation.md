# Linux Sandbox — Implementasi, Kontrak & Panduan Migrasi

Bagian dari dossier [Linux Sandbox](README.md). Isi: §16 Implementasi Project Asal ·
§17 Hal yang Project-Specific · §18 Kontrak Fitur · §19 Acceptance Criteria ·
§20 Migration / Adaptation Guide · §23 Verification · §24 Transfer Summary.

## 16. Implementasi Project Asal

Kai mengimplementasikan fitur ini sebagai berikut (semua **VERIFIED**). Nama file dicantumkan sebagai
referensi; perilakunya yang mengikat, bukan strukturnya.

### 16.1 Titik masuk

| Lapisan | Berkas / simbol | Catatan |
| --- | --- | --- |
| Kontrak lintas platform | `commonMain/.../SandboxController.kt` — `SandboxController`, `SandboxStatus`, `SandboxStatusLabel`, `SandboxMigration`, `SandboxSessions`, `CommandHandle`, `SandboxFileEntry`, `NoOpSandboxController`, `expect fun createSandboxController()` | Semua tipe yang dibutuhkan UI hidup di commonMain. |
| Implementasi Android | `androidMain/.../SandboxController.android.kt` — `AndroidSandboxController` | Memetakan `SandboxState`→`SandboxStatus`, menjalankan command & file ops. |
| Implementasi non-Android | `desktopMain/.../SandboxController.jvm.kt`, plus varian iOS/wasm | Hanya satu baris factory ke `NoOpSandboxController`. |
| DI | `androidMain/.../sandbox/SandboxModule.kt` | `single { LinuxSandboxManager(androidContext(), get(), get()) }`. |

### 16.2 Mesin sandbox (Android)

| Berkas / simbol | Tanggung jawab |
| --- | --- |
| `sandbox/LinuxSandboxManager.kt` | Marker, `selected`/`paths`, sesi shell, `setup/cancel/reset/installPackages/selectDistro/migrateHome/surveyMigration`, `createProotExecutor`, `fileMap`, `getDiskUsageMB`, `arePackagesInstalled`, debounce transkrip, registry installer per direktori. |
| `sandbox/SandboxState.kt` | Enum state internal. |
| `sandbox/PersistentSandboxShell.kt` | bash persisten, staging command, sentinel, probe pid, cancel bergradasi, watchdog, `reset`, `buildResult/timeoutMap/errorMap`. |
| `sandbox/SessionShell.kt` | Fasad per sesi: `transcript` (`SnapshotStateList`), `run(displayCommand=…)`, `writeInput`, `cancelForeground`, `reset`, `setPrunePaused`. |
| `sandbox/ProotExecutor.kt` | `execute` (map hasil) & `executeStreaming` (`ProotHandle`), dua thread pembaca. |
| `linux/ProotLauncher.kt` | `buildArgs`, `buildEnv`, `start`, `execute` (timeout, drain paralel), `startStreaming`, `ProotHandle` (`writeBytes/writeLine/awaitExit/cancel`, `killGuest`), `ProotResult.failureDetail()`. |

### 16.3 Instalasi

| Berkas / simbol | Tanggung jawab |
| --- | --- |
| `linux/LinuxInstaller.kt` | `install(distro, onStep)` → `InstallMarker`; `refreshPackageIndex` (mirror walk untuk Alpine); `installBasePackages`; `companion object { val packageLock }`. |
| `linux/LinuxInstalls.kt` | `pathsFor(distro)`, `distroInSandboxDir()`, `installed()`, `homeDirFor(distro)`. |
| `linux/LinuxPaths.kt` | `InstallMarker`, `root/rootfsDir/tmpDir/prootPath/libDir/projectsDir/nativeLibDir`, `readMarker/writeMarker/deleteInstall/ensureLayout/ensureMountPoints/copyLibtalloc/archiveFile`, dua factory (`forSandbox`, `forBuild`). |
| `linux/DistroSpec.kt` | `AlpineSpec` (6 mirror, `writeRepositories`, `configure`), `DebianSpec` (resolve index LXC, `configure` termasuk `force-unsafe-io`, `prootArgs = ["--link2symlink","-L"]`, `env = DEBIAN_FRONTEND=noninteractive`). |
| `linux/RootfsDownloader.kt` | Satu jalur unduhan (streaming ke file) dengan fallback URL. |
| `linux/TarExtractor.kt` | Satu pembaca tar untuk `.tar.gz` dan `.tar.xz`; `makeWritable`, `writeResolvConf`. |
| `linux/LinuxDistro.kt` | Dua entri enum + `basePackages`, `optionalPackages`, `protectedPackages`, `packageManager`, `DEFAULT = DEBIAN`, `LEGACY = ALPINE`, `fromId`. |
| `linux/PackageManagerSpec.kt` + `Apk/AptPackageManager.kt` | Command & parser murni; `shellQuote`/`shellQuoteAll`; exit code sengaja di luar kontrak. |
| `jvmShared/linux/HomeMigration.kt` | `EXCLUDED`, `survey`, `copy` (merge, permission eksplisit, symlink tak diikuti), `walk`. |
| `jvmShared/linux/GuestPath.kt` | `safeChild`, `GuestFileMap.resolve/isRoot`. |
| Native | `androidApp/src/main/jniLibs/<abi>/libproot.so`, `libproot-loader{,32}.so`, `libtalloc.so`; `build-proot.sh`. |

### 16.4 Tools

| Berkas / simbol | Tool | Catatan |
| --- | --- | --- |
| `androidMain/.../tools/ShellCommandTool.kt` | `execute_shell_command` | Deskripsi dibangun **per distribusi**; `timeout` dijepit `1..60`; `working_dir`/`env` jadi prefix; `background`→manager proses; `fresh`→executor one-shot; sisanya→shell persisten sesi percakapan. `toolInfo.userToggleable = false`. |
| `androidMain/.../tools/SshConfigureHostTool.kt` | `ssh_configure_host` | Validasi input, `SshConfigManager(homePath).upsertHost(...)`, opsional `appendKnownHostLine`, mengembalikan `example = "ssh <alias>"`. |
| `jvmShared/.../tools/ProcessManagerTool.kt` | `manage_process` | `list`/`log`/`kill`/`remove`; dibagi Android & desktop lewat `createProcessManager()`. |
| `androidMain/.../tools/ProcessManager.kt` | — | `startBackground` (detached one-shot), tabel `ConcurrentHashMap`, `Session`. |
| `jvmShared/.../sandbox/SshConfigManager.kt` | — | Blok `# kai:<marker>:start/end` (defaults + `host:<alias>`), dedupe baris `known_hosts`, `lockDown` ke 600/700, resolusi `IdentityFile` relatif `~/.ssh`. |
| `androidMain/.../Platform.android.kt` | Registrasi | `getPlatformToolDefinitions()` (untuk nama tampilan) dan `getAvailableTools()` yang **hanya** menambahkan ketiga tool bila `isSandboxEnabled() && state is Ready`. |

### 16.5 UI

| Berkas | Peran |
| --- | --- |
| `ui/sandbox/SandboxSessionViewModel.kt` | Tab sesi, input per sesi, run/cancel, `writeInput` ketika ada perintah berjalan, `~clear`, pulse scroll. |
| `ui/settings/TerminalSheet.kt` | Terminal; `setTranscriptInteractive` saat drag-select. |
| `ui/sandbox/SandboxFileBrowserViewModel.kt` / `Screen.kt` | Listing, breadcrumb (tidak menawarkan langkah di atas root), editor, rename/delete, refresh-on-visible. |
| `ui/sandbox/SandboxPackagesViewModel.kt` / `Screen.kt` | Cari/install/uninstall/upgrade lewat `PackageManagerSpec`; gate uninstall pakai `protectedPackages`; re-rank hasil pencarian (nama eksak → prefix → segmen prefix → contains → deskripsi). |
| `ui/sandbox/SandboxTabsContent.kt` | Kerangka Terminal/Files/Packages. |
| `ui/sandbox/SandboxStatusText.kt` | Satu-satunya tempat `SandboxStatusLabel` → kalimat. |
| `ui/settings/SandboxSettings.kt` / `SandboxViewModel.kt` | Kartu: distro picker (+ mana yang sudah ada di disk), setup/cancel, Install Packages, Copy Files, uninstall, toggle, disk usage. |
| `ui/SandboxUriHandler.kt` | Menyediakan `UriHandler` di atas `LocalUriHandler`: `file:`/absolute → `openFile`, sisanya diteruskan. |
| `sandbox/SandboxFiles.kt` | `readFileAsText` (cap, NUL check, `force` → read-only), `guessMimeType`, `openFileWithIntent`, `importFileInto`, `nonCollidingChild`, `toFileEntry`. |

### 16.6 Data

| Berkas | Peran |
| --- | --- |
| `data/ConversationStorage.kt` | `updateShellTranscript(id, lines)` → trim 10 000 karakter → `persistence.saveShellTranscript`. |
| `data/ConversationPersistence.kt` | Tulisan SQLDelight. |
| `data/AppSettings.kt` | `isSandboxEnabled()`, `getSandboxDistroOrNull()`, `setSandboxDistro()`. |
| `data/RemoteDataRepository.kt` | `deleteConversation` → `sandboxController.closeSession(id)`. |
| `ConversationIdContext.kt` / `currentConversationIdOrNull()` | Mengalirkan id percakapan aktif ke eksekusi tool. |
| `TerminalLine.kt` | `Command`/`Output`/`Error`, serializable. |

### 16.7 Test yang ada (VERIFIED, keberadaannya)

- `commonTest/.../linux/ApkPackageManagerTest.kt`, `AptPackageManagerTest.kt`, `LinuxDistroTest.kt`
  — parser & command package manager.
- `commonTest/.../ui/sandbox/RankSearchResultsTest.kt`, `BreadcrumbsTest.kt`,
  `SandboxFileBrowserViewModelTest.kt` — perilaku Files/Packages.
- `commonTest/.../ui/settings/SandboxViewModelTest.kt` — pemetaan status & toggle.
- `commonTest/.../ui/SandboxUriHandlerTest.kt` — routing URI.
- `commonTest/.../data/ConversationStorageTest.kt` — trim transkrip & no-op untuk percakapan tak tersimpan.
- `commonTest/.../testutil/FakeSandboxController.kt` — fake untuk UI test.

**NOT VERIFIED**: isi dan hasil test tersebut **tidak dijalankan** selama audit ini; keberadaannya
dibaca dari daftar berkas repository. Jangan menganggap suite-nya hijau.

## 17. Hal yang Bersifat Project-Specific

Anggap semuanya di sini sebagai **referensi**, bukan persyaratan:

| Aspek | Bagian yang Kai-specific |
| --- | --- |
| Bahasa & framework | Kotlin Multiplatform + Compose Multiplatform; hukum kekekalan state lewat `SnapshotStateList`. |
| Kontrak lintas platform | `expect/actual` + `SandboxController` + `NoOpSandboxController`. Project target bisa memakai antarmuka biasa, protocol, atau service. |
| DI | Koin (`inject`, `module`). |
| Persistensi transkrip | SQLDelight + tabel percakapan; debounce 500 ms ada karena storage Kai JSON/SQL per percakapan. |
| Lokalisasi | Compose resources (`Res.string.*`), dan keputusan bahwa platform melaporkan *label* bukan kalimat. |
| Pemilih file | FileKit (`PlatformFile`) untuk import. |
| HTTP client | Ktor + OkHttp. |
| Unduhan Debian | Index LXC `images.linuxcontainers.org` + `libs.xz` untuk `tar.xz`. |
| Paket base Debian | Daftar exact (`tar` untuk installer OpenCode, `coreutils` untuk `sha256sum` Claude) — itu kebutuhan Kai Build, bukan kebutuhan sandbox. |
| Alpine 3.22.5 | Pilihan versi konkret; yang penting adalah *cap* pada apk-tools 2, bukan angka versinya. |
| Integrasi Kai Build | Rootfs Debian dibagi dengan fitur lain, memaksa `LinuxInstalls` dan `packageLock`. Project target yang tidak punya fitur serupa tidak butuh keduanya. |
| Direktori legacy | `linux-sandbox`, `kai-build`, `sandbox-home`, `kai-build-home/projects`, file `ready`. Murni historis Kai. |
| Tool `manage_process` di desktop | Khusus Kai (host process). |
| Blok `# kai:` di `~/.ssh/config` | Penanda milik Kai; nama penanda bebas. |
| FileProvider `file_paths.xml` | Konfigurasi Android Kai, menyebut dua area storage. |
| Nama tool & id sesi | `execute_shell_command`, `__default__`/`__system__`/`__terminal__`. Nama tidak penting; **semantiknya** penting. |
| 15 000 / 60 detik / 500 baris / 512 KB | Angka konkret Kai. Yang harus dipertahankan adalah *adanya* batas dan bahwa batasnya diberitahukan ke model/user. |

## 18. Kontrak Fitur

### MUST (tanpa ini fitur bukan dirinya sendiri)

1. **Eksekusi user-space tanpa root.** Perintah Linux nyata berjalan tanpa akses root dan tanpa
   binary sistem host di luar apa yang di-bind secara eksplisit.
2. **State shell persisten per sesi.** `cwd`, variabel yang di-`export`, dan state in-shell bertahan
   antar pemanggilan **dalam satu sesi**, dan **tidak** bocor antar sesi.
3. **Framing perintah yang tidak bisa ditelan program user.** Selesainya sebuah perintah harus
   terdeteksi lewat kanal yang tidak bisa di-*redirect* oleh perintah itu sendiri, dan harus membawa
   exit code.
4. **Exit code & stream terpisah.** `stdout`, `stderr`, dan `exit_code` harus tersedia terpisah untuk
   pemanggil; keberhasilan tidak boleh disimpulkan dari exit code package manager (di bawah emulasi
   user-space, kode itu tidak dapat dipercaya).
5. **Ready hanya setelah benar-benar bisa dipakai.** Marker/status "terpasang" ditulis **terakhir**,
   setelah base package sukses, dan status siap juga mensyaratkan runtime eksekusinya ada dan
   executable. Instalasi yang diinterupsi **tidak boleh** tampak siap.
6. **Instalasi parsial tidak boleh membuat retry gagal permanen.** Kegagalan pasca-ekstraksi harus
   membuang rootfs parsial agar percobaan berikutnya benar-benar mengulang dari bersih.
7. **Penghentian paksa selalu meninggalkan sistem yang bisa dipakai.** Escalation cancel dan
   self-healing wajib ada: setelah perintah yang macet dibatalkan, perintah berikutnya harus berhasil
   (walau kehilangan state sesi).
8. **Pemanggil tidak pernah menunggu selamanya.** Setiap jalur tunggu punya batas: timeout per
   perintah, deteksi kematian shell (watchdog), dan timeout proses.
9. **Switching distribusi bersifat non-destruktif.** Tidak ada unduhan dan tidak ada penghapusan saat
   berpindah; install yang ditinggalkan tetap utuh dan bisa langsung dipakai lagi.
10. **Pemindahan file antar install bersifat merge satu arah.** Tujuan selalu menang, sumber tidak
    pernah dimodifikasi, dan operasinya idempoten (aman diulang).
11. **Perubahan izin file dipertahankan.** Kunci privat yang berpindah harus tetap dapat dipakai
    openssh (bukan world-readable).
12. **Batas filesystem ditegakkan.** Path tidak absolut, mengandung `..`, atau menyelesaikan di luar
    root yang diizinkan **ditolak**; bind root tidak boleh dihapus/diganti nama.
13. **Isi file dinilai dari byte, bukan dari nama.** Teks valid → bisa diedit; bukan teks → jangan
    tawarkan penyimpanan yang akan merusak byte; terlalu besar → tolak edit, tawarkan serahkan ke app lain.
14. **Ketersediaan tool mengikuti status nyata.** Tool sandbox hanya ditawarkan ke model saat sandbox
    terpasang *dan* diaktifkan. Tool yang tidak bisa berhasil tidak boleh diiklankan.
15. **Platform tanpa sandbox tetap berfungsi.** Implementasi no-op harus membuat seluruh aplikasi
    berjalan normal, bukan crash.
16. **Output dibatasi dan pemanggil diberi tahu.** Ada cap per stream dan hasilnya menyatakan
    terpotong/tidak; tidak ada output yang tumbuh tanpa batas ke memori.
17. **Satu shell per sesi, konkurensi diserialisasi.** Dua perintah bersamaan pada satu sesi tidak
    boleh merusak framing satu sama lain.

### SHOULD (sangat diinginkan; hilangnya menurunkan kualitas)

- Transkrip sesi dapat dilihat user, termasuk perintah yang dijalankan **agen** (bukan hanya user).
- Transkrip terakhir dipersistensi agar chat lama masih memperlihatkan ekor aktivitas setelah restart.
- Distribusi terpilih tercatat, dan default "belum pernah memilih" mengarah ke apa yang sudah ada di
  disk, bukan ke default pabrik.
- Status instalasi dilaporkan sebagai *langkah*, dengan kata-kata hanya di layer UI (i18n).
- Paket opsional terpisah dari paket base dan tidak pernah dipasang otomatis.
- Pencarian paket mengutamakan kecocokan nama di atas kecocokan deskripsi.
- File browser menyegarkan dirinya saat kembali terlihat, tanpa memindahkan posisi user atau
  menimpa editan yang belum disimpan.
- Import dari perangkat tidak pernah menimpa file yang ada.

### MAY (implementasi bebas)

- `proot` sebagai runtime emulasi; runtime user-space lain yang setara boleh dipakai.
- Dua distribusi, atau satu, atau tiga — selama switching non-destruktif dan pilihan itu eksplisit.
- Paket base/opsional: daftar konkretnya, dan bahkan pemisahan base/opsional (selama "opsional tidak
  otomatis" dipertahankan).
- Ktor, SQLDelight, Koin, Compose, FileKit → semua komponen yang bisa diganti.
- Strategi persistensi transkrip: file, database, atau preference.
- Format marker (Kai memakai `key=value` di file `install`).
- Cara menegakkan cap output (potong per baris, per byte, atau minta pemanggil mem-`grep`).

### MUST NOT

- Jangan **menganggap exit code package manager sebagai kebenaran** di bawah emulasi user-space.
- Jangan **menawarkan tool sandbox saat sandbox belum siap** (model akan menghabiskan turn pada tool
  yang hanya bisa gagal).
- Jangan **menghapus install saat berpindah distribusi**.
- Jangan **menimpa file user saat migrasi** atau mengubah install sumber.
- Jangan **menjanjikan PTY/fullscreen TUI**; dokumentasikan bahwa ia tidak ada.
- Jangan **menyatakan "terpasang" dari keberadaan direktori** rootfs saja.
- Jangan **menaruh kalimat user-facing di layer platform** (harus bisa diterjemahkan).
- Jangan **mengizinkan traversal path** atau menghapus/mengganti nama bind root.
- Jangan **menulis atau mengunggah kunci privat** atas nama user.
- Jangan **memasukkan kredensial** apa pun ke konfigurasi fitur ini.

## 19. Acceptance Criteria

Menguji **perilaku**, bukan keberadaan class.

**Instalasi**

- [ ] Instalasi dari nol selesai tanpa intervensi setelah satu aksi, dan berakhir pada status siap.
- [ ] Selama instalasi, progres dilaporkan (unduhan + langkah bernama), dan UI tetap responsif.
- [ ] Menghentikan instalasi di tengah meninggalkan status "tidak terpasang" — **bukan** "siap".
- [ ] Setelah instalasi diinterupsi, percobaan berikutnya mengulang dari bersih dan berhasil.
- [ ] Sandbox mengembalikan status "siap" hanya bila runtime eksekusinya ada dan executable.
- [ ] Jika paket base gagal dipasang, rootfs parsial tidak tertinggal sehingga retry tidak terjebak
      pada kegagalan yang sama.
- [ ] Paket opsional **tidak** terpasang tanpa aksi eksplisit user.

**Eksekusi**

- [ ] `cd /tmp` lalu `pwd` dalam satu sesi mengembalikan `/tmp`.
- [ ] `cd` di sesi A tidak mengubah lokasi sesi B.
- [ ] Perintah yang gagal mengembalikan exit code bukan-nol beserta stderr, dan sesi tetap hidup.
- [ ] Perintah yang macet berhenti pada timeout; setelah itu perintah berikutnya berhasil.
- [ ] Pembatalan menghentikan perintah yang sedang berjalan, dan sesi masih bisa dipakai (atau
      dinyatakan direset dan otomatis pulih pada perintah berikutnya).
- [ ] Shell yang mati (mis. `exit`) tidak membuat pemanggil menggantung; perintah berikutnya sukses.
- [ ] Perintah yang membanjiri output tetap terbatas dan tetap menjawab.
- [ ] Dua perintah konkuren pada satu sesi tidak saling merusak hasil.
- [ ] `fresh` benar-benar tidak berbagi state dengan sesi persisten.
- [ ] `background` mengembalikan id sesi, dan hasilnya dapat dibaca setelah selesai.

**Status & platform**

- [ ] Bila sandbox tidak terpasang, tool sandbox **tidak** ditawarkan ke model.
- [ ] Bila toggle sandbox dimatikan, tool sandbox **tidak** ditawarkan ke model walaupun sandbox terpasang.
- [ ] Pada platform tanpa sandbox, seluruh alur UI tetap berjalan dan operasi mengembalikan hasil
      kosong alih-alih melempar exception.
- [ ] Memanggil eksekusi saat sandbox tidak siap mengembalikan pesan yang jelas, bukan crash.

**Distribusi & migrasi**

- [ ] Berpindah distribusi tidak mengunduh ulang, tidak menghapus, dan tidak mengubah file di install lain.
- [ ] Berpindah kembali tampak persis seperti ditinggalkan (file, paket, kunci SSH).
- [ ] Sesi shell lama tidak dipakai setelah berpindah: perintah berikutnya memakai install baru.
- [ ] Tawaran migrasi muncul hanya ketika memang ada file yang tertinggal, dan hilang setelah disalin.
- [ ] Migrasi dua kali menghasilkan hasil yang sama dan tidak menimpa apa pun.
- [ ] Kunci privat yang bermigrasi masih dapat dipakai (izinnya tidak menjadi world-readable).
- [ ] Paket, shell rc, cache, dan direktori bind tidak ikut bermigrasi.

**File**

- [ ] Path dengan `..`, path relatif, dan path di luar root ditolak.
- [ ] Bind root tidak dapat dihapus atau diganti nama.
- [ ] File teks dapat dibaca/ditulis; file binary tidak menawarkan penyimpanan yang merusak byte.
- [ ] File melebihi batas ukuran dilaporkan beserta ukurannya dan hanya ditawarkan ke aplikasi lain.
- [ ] Import file dengan nama yang sudah ada tidak menimpa; file besar tidak membebani memori app.
- [ ] Menutup dan membuka kembali aplikasi (atau kembali dari background) meninggalkan ekor transkrip
      yang tersimpan, walaupun shell hidup sudah tidak ada.

**Kontrak transfer**

- [ ] Tidak ada kredensial yang dibutuhkan atau disimpan oleh fitur.
- [ ] Tidak ada kalimat user-facing yang lahir di layer platform.

## 20. Migration / Adaptation Guide

### Source Project

**Project (Kai)** — Kotlin Multiplatform + Compose Multiplatform; Android-only sandbox; `proot`
di-vendor sebagai native library; rootfs dari mirror Alpine / LXC index Debian; persistensi lewat
SQLDelight; DI lewat Koin; tool dijalankan melalui kerangka tool milik aplikasi.

### Target Concept

Berikan aplikasi kemampuan menjalankan Linux user-space nyata di perangkat (**tanpa root**), dengan
sesi shell persisten per percakapan, agar agen AI — dan user — dapat menulis file, memasang paket,
menjalankan skrip, dan mengakses server remote; lengkap dengan Terminal, file browser, dan manajer paket.

### Required Components

1. **Runtime eksekusi user-space** + rootfs yang bisa diekstrak ke storage aplikasi, dan satu tempat
   yang mengizinkan `exec`. (Di Android: native library dir.)
2. **Launcher proses** yang menyusun argv, binds, dan environment, dan mengembalikan handle yang bisa
   dibatalkan dan dibaca baris demi baris.
3. **Sesi shell persisten per id**, dengan staging perintah + kanal penanda selesai + exit code, dan
   `Mutex` per sesi.
4. **Installer** dengan langkah yang dapat dilaporkan (unduh → ekstrak → konfigurasi → index →
   base package) dan marker yang ditulis terakhir.
5. **State + status yang dapat diobservasi** (siap / bekerja + progres / error dengan label step).
6. **Registry sesi + transkrip** yang dapat dipersistensi untuk sesi yang terikat percakapan.
7. **Peta path guest → host** yang konsisten dengan bind yang dipakai runtime.
8. **Permukaan eksekusi**: minimal satu tool untuk agen, satu API untuk UI.
9. **Gate**: status terpasang + toggle user → menentukan apakah tool diiklankan.

### Components That Can Be Replaced

| Boleh diganti | Contoh |
| --- | --- |
| Runtime emulasi | `proot` → alternatif user-space lain yang setara |
| Bahasa/framework aplikasi | Kotlin/Compose → apa pun |
| Distribusi Linux & jumlahnya | Alpine/Debian → satu distro saja, atau tiga |
| Sumber rootfs | Mirror Alpine / LXC index → image internal, tarball sendiri, cache lokal |
| Package manager yang didukung | apk + apt → salah satu saja |
| Persistensi | SQLDelight → file, SharedPreferences, Room, apa pun |
| DI, HTTP client, pemilih file | Koin, Ktor, FileKit → bebas |
| Lokalisasi status | Compose resources → mekanisme i18n apa pun |
| Format & lokasi bukti instalasi | `install` + `etc/alpine-release` → skema marker sendiri |
| Nama tool & id sesi | bebas, asalkan semantiknya sama |
| Angka batas (15 000 / 60 s / 500 baris / 512 KB) | bebas, selama batasnya ada dan diberitahukan |

### Components That Must Preserve Their Behavior

| Wajib dipertahankan | Kenapa |
| --- | --- |
| Framing selesai-perintah lewat kanal yang tidak bisa ditelan program user | Tanpa PTY, ini satu-satunya cara tahu sebuah perintah berakhir, dan hanya ini yang membawa exit code. |
| State persisten per sesi & tidak bocor antar sesi | Inti nilai UX-nya; tanpa itu agen harus memakai `cd x && cmd` untuk segalanya. |
| Escalation pembatalan + self-healing | Tanpa PTY, cancel bersifat best-effort; harus ada jalan keluar yang membuat sistem tetap terpakai. |
| Marker ditulis terakhir + syarat executable | Mencegah "siap" palsu dan retry yang tak pernah bisa berhasil. |
| Pembuangan rootfs parsial saat paket base gagal | Mencegah kegagalan permanen setelah satu gangguan jaringan. |
| Tidak mempercayai exit code package manager di bawah emulasi | Kode kembaliannya tidak bermakna; verifikasi dengan membaca daftar terpasang. |
| Tool hanya saat siap + diaktifkan | Melindungi anggaran turn model dan mencegah rangkaian kegagalan. |
| Switch distribusi non-destruktif | Satu-satunya penghapusan adalah aksi uninstall eksplisit. |
| Migrasi merge satu arah yang idempoten, izin dipertahankan | Kalau menimpa atau menghapus sesuatu, ia berubah dari "aman diulang" menjadi operasi berbahaya. |
| Penolakan traversal path & perlindungan bind root | Batas filesystem adalah batas kepercayaan. |
| Penilaian isi file dari byte + larangan menyimpan buffer yang sudah rusak | Menyimpan replacement character akan menghancurkan file user. |
| Cap output + drain pipe yang benar | Membaca stream secara berurutan akan deadlock; tanpa cap, memori meledak. |
| Satu shell per sesi, konkurensi diserialisasi | Framing sentinel tidak tahan interleaving. |
| No-op yang benar-benar no-op di platform lain | Fitur harus bisa tidak ada tanpa merusak aplikasi. |

### Suggested Implementation Order

1. **Storage & marker layer.** Direktori install, layout, marker, pembacaan status "terpasang" +
   syarat executable. (Diuji tanpa runtime Linux sama sekali.)
2. **Installer tanpa UI.** Unduh → ekstrak → konfigurasi → index → base package → marker, dengan
   langkah-langkah yang dilaporkan dan pembuangan rootfs parsial pada kegagalan.
3. **Launcher proses.** argv + binds + env, eksekusi one-shot dengan timeout dan hasil terstruktur.
4. **Sesi persisten.** Staging perintah, kanal sentinel, serialisasi, watchdog kematian shell.
5. **Pembatalan dan pemulihan.** Escalation sinyal, reset, verifikasi "perintah berikutnya berhasil".
6. **State/status publik + gate tool.** Agar UI dan agen tahu kapan boleh bertindak.
7. **Tool agen** (shell, lalu proses background, lalu konfigurasi SSH).
8. **Peta path guest → host + operasi file.**
9. **UI**: Terminal → Files → Packages (urutan ini karena Terminal memvalidasi seluruh mesin).
10. **Pemilihan distribusi & migrasi home.**
11. **Persistensi transkrip.**
12. **Test**: parser package manager, peta path (traversal!), trim transkrip, pemetaan status — semuanya
    tanpa perangkat.
13. **Verifikasi acceptance criteria** pada perangkat nyata (§19), termasuk kasus cancel-dan-lanjut.

### Hal yang harus diputuskan sejak awal (jangan di tengah jalan)

- Apakah fitur ini **Satu runtime untuk semua** konsumen lain (satu rootfs dibagi beberapa fitur), atau
  satu runtime per fitur. Kai memilih yang pertama dan konsekuensinya besar (`LinuxInstalls`,
  `packageLock`, `refreshInstallState`). Kalau target tidak memerlukannya, **jangan** bawa
  kompleksitas itu.
- Apakah "background job" milik sandbox atau host. Kai punya keduanya, dan **kill** di sandbox belum
  benar-benar membunuh proses (lihat §22.1). Implementasikan `kill` sejak awal.

## 23. Verification

**Verified by**

- **Source inspection** — seluruh klaim berlabel VERIFIED berasal dari membaca berkas berikut secara
  langsung: `SandboxController.kt`, `SandboxController.android.kt`, `sandbox/LinuxSandboxManager.kt`,
  `sandbox/PersistentSandboxShell.kt`, `sandbox/SessionShell.kt`, `sandbox/SandboxState.kt`,
  `sandbox/ProotExecutor.kt`, `sandbox/SandboxFiles.kt`, `sandbox/SandboxModule.kt`,
  `linux/LinuxInstaller.kt`, `linux/LinuxInstalls.kt`, `linux/LinuxPaths.kt`, `linux/DistroSpec.kt`,
  `linux/LinuxDistro.kt`, `linux/PackageManagerSpec.kt`, `linux/ProotLauncher.kt`,
  `jvmShared/linux/HomeMigration.kt`, `jvmShared/linux/GuestPath.kt`,
  `jvmShared/sandbox/SshConfigManager.kt`, `jvmShared/tools/ProcessManagerTool.kt`,
  `tools/ShellCommandTool.kt`, `tools/SshConfigureHostTool.kt`, `tools/ProcessManager.kt`,
  `Platform.android.kt`, `ui/sandbox/SandboxSessionViewModel.kt`, `ui/sandbox/SandboxStatusText.kt`,
  `ui/sandbox/SandboxFileBrowserViewModel.kt`, `ui/settings/SandboxViewModel.kt`,
  `data/ConversationStorage.kt`, `data/RemoteDataRepository.kt` (`deleteConversation`),
  `FileBrowserSource.kt`, `TerminalLine.kt`, `androidApp/build.gradle.kts`,
  `androidApp/src/main/AndroidManifest.xml`, `androidApp/src/main/res/xml/file_paths.xml`.
- **Cross-check dengan dokumentasi produk** — `docs/features/sandbox.md` (perilaku yang terlihat user)
  dan `docs/features/kai-build.md` (konsumen rootfs Debian) dipakai untuk memastikan tidak ada klaim
  yang bertentangan. **NOT VERIFIED**: isi `kai-build.md` tidak diaudit baris per baris.

**Not verified**

- **Tidak ada test yang dijalankan.** Test hanya terdaftar dari daftar berkas repository; tidak ada
  yang dieksekusi, jadi tidak ada klaim "hijau".
- **Tidak ada build/APK/instrumented run.** Tidak ada verifikasi runtime: tidak ada instalasi nyata,
  tidak ada `proot` yang dijalankan, tidak ada perintah yang dieksekusi di perangkat.
- **Perilaku di bawah konkurensi nyata.** Bahwa `Mutex` + sentinel + watchdog aman di bawah beban
  nyata dinyatakan sebagai INFERRED dari desain, bukan dari pengujian.
- **Detail sisi Kai Build** (PTY executor, cara agen dipasang) tidak diaudit — hanya titik
  singgungannya (marker, direktori, `packageLock`, `refreshInstallState`) yang dibaca.
- **Peran file native, `build-proot.sh`, dan `useLegacyPackaging`** diverifikasi sebagai konfigurasi
  yang ada dan saling konsisten; **hasil build-nya** tidak diuji.
- **Urutan/rincian string resource** untuk label status tidak dibaca satu per satu; yang diverifikasi
  adalah bahwa `SandboxStatusText` adalah satu-satunya pemetaan label → kalimat.
- Tidak ada pengujian di platform non-Android; jalur no-op diverifikasi hanya dari source.

## 24. Transfer Summary

```text
FEATURE:
Linux sandbox (user-space Linux via proot, tanpa root, untuk agen AI dan user)

PURPOSE:
Memberi agen dan user mesin Linux nyata di perangkat: jalankan perintah shell, pasang paket,
tulis file, akses server remote — tanpa root, tanpa server, dan dengan Terminal/file browser/
manajer paket yang menunjuk filesystem yang sama.

CORE BEHAVIOR:
1. Satu rootfs Linux diunduh ke storage aplikasi dan dieksekusi lewat runtime user-space.
2. Satu bash persisten per "sesi" (mis. per percakapan). cwd dan env bertahan antar panggilan
   dalam satu sesi, tidak bocor antar sesi; rootfs dibagi, jadi file terlihat lintas sesi.
3. "Terpasang/Ready" ditulis TERAKHIR, setelah base package sukses, dan mensyaratkan runtime
   eksekusinya ada + executable. Instalasi yang diinterupsi tidak pernah tampak siap.
4. Perintah dibungkus sehingga selesainya terdeteksi lewat kanal yang tidak bisa ditelan
   program user, membawa exit code; stdout/stderr di-cap dan dikembalikan terpisah.
5. Cancel bergradasi (INT → TERM → KILL → reset sesi) dan self-healing: setelah kegagalan apa
   pun, perintah berikutnya berhasil (maksimal kehilangan state sesi).
6. Bila ada lebih dari satu distribusi: berpindah adalah perubahan pointer, bukan unduh/hapus;
   file home dapat disalin sebagai merge satu arah yang idempoten.
7. Tool agen hanya diiklankan saat sandbox siap DAN diaktifkan; platform tanpa sandbox memakai
   implementasi no-op.

REQUIRED (minimum):
- Runtime eksekusi user-space + rootfs yang dapat diekstrak ke storage aplikasi, plus satu
  lokasi yang mengizinkan exec.
- Launcher proses (argv/binds/env) yang mengembalikan handle yang bisa dibaca streaming dan
  dibatalkan.
- Shell persisten per sesi: staging perintah, kanal penanda selesai + exit code, mutex.
- Installer dengan langkah yang dapat dilaporkan (unduh/ekstrak/konfigurasi/index/base package)
  dan penulisan marker di akhir.
- State/status terobservasi (ready / working+progress / error berlabel step).
- Registry sesi + transkrip (dapat dipersistensi untuk sesi terikat percakapan).
- Peta path guest→host yang konsisten dengan binds.
- Minimal satu tool untuk agen + satu API untuk UI, digerbangi oleh status terpasang + toggle.

MUST PRESERVE:
- Framing selesai-perintah lewat kanal yang tidak bisa di-redirect, dengan exit code.
- State persisten per sesi, tidak bocor antar sesi.
- Cancel bergradasi + self-healing; tidak ada pemanggil yang menggantung tanpa batas.
- Marker/status siap ditulis terakhir; retry setelah kegagalan pasca-ekstraksi harus mulai bersih.
- Jangan mempercayai exit code package manager di bawah emulasi user-space.
- Perpindahan distribusi non-destruktif; migrasi file merge satu arah + izin file dipertahankan.
- Penolakan traversal path; bind root tidak dapat dihapus/diganti nama.
- Isi file dinilai dari byte; jangan pernah menyimpan buffer yang sudah dirusak decode.
- Tool hanya saat siap + diaktifkan; platform tanpa sandbox tidak crash.
- Batas output/timeout ada dan diberitahukan.

PROJECT-SPECIFIC (Kai saja):
Kotlin Multiplatform + Compose + Koin + SQLDelight + Ktor + FileKit; kontrak expect/actual;
rootfs Alpine (3.22, 6 mirror) / Debian (LXC index, tar.xz, --link2symlink -L); direktori
linux-sandbox/kai-build dan layout home legacy; rootfs Debian dibagi dengan fitur Kai Build
(sehingga ada registry install + lock paket proses-wide + refreshInstallState); blok
"# kai:" di ~/.ssh/config; FileProvider file_paths.xml; nama tool/id sesi; angka batas konkret.

CAN BE REPLACED:
Runtime emulasi; bahasa/framework; jumlah & jenis distribusi; sumber rootfs; package manager yang
didukung (boleh satu saja); persistensi transkrip; DI/HTTP client/pemilih file; mekanisme i18n;
format marker; nama tool; angka batas (asal ada batas dan diberitahukan).

MAIN RISKS:
1. Menyatakan "siap" sebelum benar-benar bisa dipakai → agen membakar turn dan user bingung.
2. Tidak membuang rootfs parsial pada kegagalan paket → kegagalan permanen yang tidak bisa di-retry.
3. Membaca stdout lalu stderr secara berurutan → deadlock pada output besar.
4. Cancel tanpa jalur reset → sesi macet selamanya.
5. Mempercayai exit code apk/apt di bawah emulasi → status sukses/gagal palsu.
6. Migrasi yang menimpa file atau menghapus sumber → kehilangan data user yang tidak bisa dibatalkan.
7. Mengizinkan traversal path / menghapus bind root → keluar dari batas filesystem.
8. Menyimpan file non-teks yang dipaksa dibuka → merusak byte file user.
9. Mengiklankan tool sandbox saat belum siap → rangkaian kegagalan yang tidak informatif.
10. Menjanjikan PTY/fullscreen TUI → kekecewaan dan bug yang tidak bisa diperbaiki tanpa layer PTY.
11. "Background job" yang kill-nya hanya menandai status (lihat §22.1) → proses guest tetap berjalan.

ACCEPTANCE:
Instalasi dari nol selesai dan berakhir siap; interupsi tidak pernah menghasilkan "siap" palsu dan
tidak merusak retry. cd/export bertahan dalam satu sesi dan tidak bocor antar sesi. Perintah gagal
mengembalikan exit code + stderr tanpa membunuh sesi; perintah macet berhenti pada timeout dan
sistem tetap bisa dipakai; shell yang mati tidak membuat pemanggil menggantung. Output besar tetap
terbatas. Berpindah distribusi tidak mengunduh/menghapus; kembali menemukan semuanya utuh; sesi
berikutnya memakai install baru. Migrasi idempoten, tidak menimpa, mempertahankan izin file, dan
berhenti menawarkan diri saat tidak ada yang tertinggal. Path berbahaya ditolak; bind root
terlindungi. File binary/besar tidak pernah ditawarkan untuk disimpan. Tool sandbox hanya tersedia
saat siap dan diaktifkan; platform lain berjalan sebagai no-op. Tidak ada kredensial yang dibutuhkan.
```
