# Linux Sandbox — Error Handling, Edge Cases, Lifecycle & Limitasi

Bagian dari dossier [Linux Sandbox](README.md). Isi: §13 Error Handling · §14 Edge Cases ·
§15 Lifecycle · §21 Known Limitations · §22 Known Bugs.

## 13. Error Handling

### 13.1 Matriks kegagalan

| Kegagalan | Perilaku sistem | Pemulihan | Label |
| --- | --- | --- | --- |
| Network gagal saat unduh rootfs (Alpine) | Coba mirror berikutnya; hanya bila **semua** gagal → error | Retry dari awal (arsip dihapus di `finally`; rootfs dihapus bila paket gagal) | **VERIFIED** |
| Network gagal saat resolve index LXC (Debian) | `IOException` → error instalasi; tidak ada fallback selain index tunggal | Retry | **VERIFIED** |
| `proot` tidak ada / tidak executable | `check(...)` gagal dengan pesan yang menyertakan isi `nativeLibraryDir` (untuk diagnosa) | Perbaiki build/packaging | **VERIFIED** |
| Ekstraksi gagal / arsip rusak | Exception keluar dari `install()`; arsip dihapus di `finally` | Retry | **VERIFIED** |
| `apt-get update` / `apk update` gagal | Debian: `check(success)`. Alpine: tulis ulang `repositories` per mirror, gagal semua → error yang menyebut jumlah mirror + detail terakhir | Retry (rootfs tidak dihapus pada tahap ini karena belum ada paket terpasang) | **VERIFIED** |
| Install base package gagal | **Rootfs dihapus** lalu error dilempar — supaya percobaan berikutnya tidak melewati unduhan dan gagal dengan cara yang sama | Retry bersih | **VERIFIED** |
| Install paket **optional** gagal | `Failure.Package(nama, 200 karakter detail)` lalu **loop dihentikan** (paket berikutnya tidak diproses) | User / agen mengulang | **VERIFIED** |
| Setup exception | Manager menulis ulang `marker` dari disk, publish `Failure.Setup(message)` | Status kembali akurat | **VERIFIED** |
| Setup dibatalkan | `CancellationException` ditangkap → `checkExistingInstallation()` (biasanya `NotInstalled`) | — | **VERIFIED** |
| **Command** timeout | `withTimeoutOrNull(30 s)` → `cancelForeground()` → tunggu 2 s; bila masih tidak lepas → `reset()` dan hasil `timed_out=true, shell_died=true, stderr="Command timed out and shell was reset"`; bila lepas, hasil dengan `timed_out=true` | Sesi shell baru dibuat malas pada perintah berikutnya | **VERIFIED** |
| Shell mati saat perintah jalan | Watchdog `awaitExit()` menyelesaikan sink dengan `shellDied=true` dan mengosongkan handle | Perintah berikutnya membuat bash baru | **VERIFIED** |
| Total gagal mengeksekusi proses (spawn error) | `ProotResult(success=false, error=message)`; map hanya berisi `{success, error}` bila tidak ada output sama sekali | — | **VERIFIED** |
| Timeout one-shot (`ProotExecutor`) | `destroyForcibly()`; stream yang sudah ada tetap dikembalikan dengan `timed_out=true`, `error="Timed out after Ns"` | — | **VERIFIED** |
| Cancel diabaikan (SIGINT+TERM+KILL) | Shell di-reset seluruhnya; satu perintah kehilangan state sesi | Command berikutnya membuat shell baru | **VERIFIED** |
| Cancel sebelum `bashPid` diketahui | Shell langsung di-reset (pilihan sadar: "cancel harus benar-benar melakukan sesuatu") | — | **VERIFIED** |
| Command dijalankan tanpa sandbox siap | Tool mengembalikan `{success:false, error:"Linux sandbox is not installed. Set it up in Settings > Tools."}`; controller mengembalikan `"Sandbox is not ready"`; jalur streaming mengirim kalimat terjemahan | Tidak ada, sampai install selesai | **VERIFIED** |
| Output sangat besar | Dipotong pada 15 000 karakter per stream; drain sisa pipe dibuang agar tidak deadlock; deskripsi tool menyuruh `head`/`tail` | — | **VERIFIED** |
| `stdout`/`stderr` ditutup di tengah baca (biasanya akibat `destroyForcibly`) | `IOException` ditelan, apa yang sudah terbaca dikembalikan | — | **VERIFIED** |
| Staging command gagal (tulis file tmp) | `{success:false, stderr:"Failed to stage command: …"}` | — | **VERIFIED** |
| Walk disk usage melempar | Tiap entri dibungkus `runCatching`; entri rusak dilewati (socket/FIFO/symlink rusak) | — | **VERIFIED** |
| `mapState` melempar | Collector ditangkap (`try/catch`) dan publish `Failure.Status(msg)` sebagai gantinya | UI tidak membeku | **VERIFIED** |
| Migrasi home gagal | `Failure.Copy(message)`; source tidak tersentuh | Bisa diulang | **VERIFIED** |
| Migrasi dibatalkan | State di-set `Ready` (bukan error) | — | **VERIFIED** |
| Satu entri home tidak bisa dibaca | Entri itu dilewati, sisa home tetap disalin | — | **VERIFIED** |
| `~/.ssh` seeding gagal | `runCatching … onFailure { Log.w }` — **non-fatal**, alur tetap lanjut ke `Ready` | — | **VERIFIED** |
| Path file tidak valid / traversal | `resolve()` mengembalikan `null` → operasi mengembalikan `emptyList`/`false`/`Result.failure` | — | **VERIFIED** |
| Menghapus/mengganti nama bind root | Ditolak (`isRoot()`), sama seperti mengganti nama root dari UI | — | **VERIFIED** |
| Baca file gagal / bukan file | `TextFileResult.Unreadable`; UI membedakan "terlalu besar", "bukan teks", dan "tidak bisa dibaca" | — | **VERIFIED** |
| Rename bertabrakan | `Result.failure("collision")` | — | **VERIFIED** |
| `ssh_configure_host` argumen tidak valid | `IllegalArgumentException` ditangkap → `{success:false, error:…}` | — | **VERIFIED** |
| Job background gagal | Hasilnya tercatat di `Session` (stdout/stderr/exit/timed_out, `finished=true`) dan bisa dibaca lewat `manage_process log` | `remove` untuk membersihkan | **VERIFIED** |

### 13.2 Kebijakan yang berlaku umum

- **Retry** ditempatkan pada tingkat yang benar: mirror Alpine di langkah unduhan (retry sebenarnya),
  semuanya yang lain menyerahkan retry ke user/agen pada tingkat instalasi.
- **Fallback** hanya ada pada unduhan (daftar URL), tidak pernah pada penafsiran hasil. Package
  manager sengaja **tidak** mempercayai exit code (`VERIFIED` dari komentar `PackageManagerSpec`):
  di bawah proot apk/apt mengembalikan kode yang tidak berarti, jadi keberhasilan dinilai dengan
  membaca ulang daftar terpasang atau `hasErrors()`. Ini keputusan desain yang harus ikut dipindahkan.
- **Skip** dipakai untuk entri filesystem individual yang tidak bisa dibaca — satu socket buruk tidak
  boleh menggagalkan seluruh migrasi/disk walk.
- **Report** selalu membawa detail asli dari OS/mirror (teks Inggris), lalu dibungkus kalimat
  terjemahan hanya di layer UI.
- **Cancel** tidak pernah meninggalkan state palsu: `CancellationException` ditangkap di setiap jalur
  dan diikuti pembacaan ulang disk.

## 14. Edge Cases

| Kasus | Perilaku | Label |
| --- | --- | --- |
| Setup ganda saat install berjalan | Diabaikan (`currentJob?.isActive == true`) | **VERIFIED** |
| Switch distro saat install berjalan | Diabaikan | **VERIFIED** |
| Migrasi saat install berjalan | Diabaikan | **VERIFIED** |
| Menjalankan command tepat setelah switch distro | Shell lama sudah dilepas **secara sinkron**, jadi shell dibuat terhadap install baru (bukan ikut dibongkar bersama yang lama) | **VERIFIED** |
| `selectDistro` ke distro yang sama | No-op | **VERIFIED** |
| Command konkuren pada satu sesi | Diserialisasi `Mutex`; hasil digabung sesuai urutan | **VERIFIED** |
| Command konkuren pada sesi berbeda | Berjalan paralel; rootfs dibagi, jadi efek filesystem bisa saling terlihat | **VERIFIED** |
| `cd /tmp` lalu `pwd` di chat yang sama | Mengembalikan `/tmp` (persisten dalam satu sesi) | **VERIFIED** |
| `cd` di chat A, lalu `pwd` di chat B | Tidak saling mempengaruhi | **VERIFIED** |
| File ditulis di satu chat | Terlihat di chat lain dan Terminal tab (satu rootfs) | **VERIFIED** |
| User mengetik `exit` di Terminal | Shell mati; perintah berikutnya membuat shell baru | **VERIFIED** |
| Percakapan dihapus | Shell sesi itu ditutup (idempoten) | **VERIFIED** |
| Sandbox di-reset | Semua shell hidup ditutup, tabel sesi dikosongkan, rootfs + marker + libtalloc dihapus; project folder selamat | **VERIFIED** |
| Install lama tanpa marker (Alpine) | Diadopsi sebagai Alpine dengan `homeOnRootfs=false`, marker ditulis, file lama tetap | **VERIFIED** |
| Install lama tanpa marker tapi rootfs hanya setengah diekstrak | **Tidak** diadopsi (bukti `etc/alpine-release` tidak ada) | **VERIFIED** |
| Direktori install berisi marker distribusi lain | Tidak pernah dikembalikan oleh `pathsFor()` | **VERIFIED** |
| Dua distribusi terpasang, switch bolak-balik | Tidak mengunduh ulang, tidak menghapus apa pun; switch kembali instan | **VERIFIED** |
| Home tujuan sudah punya file dengan nama sama | File tujuan **menang**; sumber tidak disentuh | **VERIFIED** |
| Bentuk entri berbeda (file vs direktori) di sumber dan tujuan | Yang ada di tujuan menang; rekursi turun hanya bila keduanya direktori | **VERIFIED** |
| Migrasi dijalankan dua kali | Aman; survey kedua menemukan tidak ada yang tertinggal → tawaran hilang sendiri | **VERIFIED** |
| Symlink di home | Disalin **sebagai symlink** (`NOFOLLOW_LINKS`), tidak diikuti (rootfs bisa punya link loop) | **VERIFIED** |
| Private key di home | Izin POSIX disalin eksplisit (bukan hanya `COPY_ATTRIBUTES`), supaya kunci tidak tiba world-readable dan ditolak openssh | **VERIFIED** |
| Ubah ukuran/scroll terminal isi drag-select | Prune transkrip dijeda selama drag, catch-up trim setelah gesture selesai | **VERIFIED** |
| Output burst besar (mis. loop 1000 iterasi) | Tambah+trim dalam satu snapshot (mencegah `IndexOutOfBoundsException` pada measure pass LazyColumn); penulisan disk didebounce | **VERIFIED** |
| Buka chat lama setelah restart | Transkrip tersimpan (ekor) direstorasi sebagai `initialLines`; shell hidup tetap baru | **VERIFIED** |
| `updateShellTranscript` untuk percakapan yang belum disimpan | No-op | **VERIFIED** |
| File dibuka di editor lalu agen menulisinya | Editor di-reload hanya bila buffer **bersih**; editan yang belum disimpan selalu menang | **VERIFIED** |
| Files tab dikunjungi lagi | Direktori listing diulang secara silent (entri tetap di layar, tanpa kehilangan posisi scroll); posisi direktori dipertahankan | **VERIFIED** |
| Import file dengan nama yang sudah ada | Nama baru diberi suffix `-1`, `-2`, … — tidak pernah menimpa | **VERIFIED** |
| Import file besar | Dialirkan (streaming), bukan dibuffer seluruhnya | **VERIFIED** |
| Buka file > 512 KB di editor | `TooLarge(size)`, hanya ditawarkan ke aplikasi lain (buffer terpotong tidak bisa disimpan dengan aman) | **VERIFIED** |
| File binary | `Binary` bila bukan `force`; bila dipaksa, dibuka read-only dengan replacement character (menyimpan akan merusak byte asli) | **VERIFIED** |
| File berisi NUL | Dianggap "bukan teks" **sebelum** decode dicoba (NUL sah sebagai UTF-8 tapi praktis tidak muncul di teks nyata) | **VERIFIED** |
| Link `file:///root/out.gif` di chat | Diintersep dan dibuka lewat `openFile` (menyerahkan `file://` mentah ke `startActivity` akan melempar `FileUriExposedException`) | **VERIFIED** |
| Tapping file di browser | Hanya ekstensi yang "tidak ada gunanya sebagai teks" diserahkan ke app lain; sisanya dibuka di editor, termasuk tanpa ekstensi | **VERIFIED** |
| Job background `sleep 60 &` selesai saat perintah lain jalan | Baris `Done` bisa menempel ke perintah yang sedang jalan (mengikuti perilaku terminal normal) | **VERIFIED** (dari komentar kode) |
| App di-background lalu proses dibunuh Android | Semua `proot`/bash mati; shell dibuat ulang per percakapan saat kembali; transkrip terlihat lagi, state hidup hilang | **VERIFIED** |
| Model memanggil tool sandbox saat sandbox belum siap | Tool tidak pernah terdaftar; bila terpanggil juga → error map, bukan exception | **VERIFIED** |

## 15. Lifecycle

### 15.1 Instalasi

```text
Created (tidak ada marker, tidak ada rootfs)
   ↓ setup()
Downloading(fraction)
   ↓
Extracting
   ↓
Installing(Configuring)
   ↓
Installing(BasePackages | InstallingPackage(name))
   ↓
Ready (marker ditulis)
   ├── Ready → Installing(CopyingFiles) → Ready            (migrasi home)
   ├── Ready → Installing(InstallingPackage(name)) → Ready  (paket opsional)
   ├── Ready → Ready                                        (switch distro: state mungkin tidak berubah nilainya)
   ├── Ready → NotInstalled                                 (reset/uninstall)
   └── * → Error(Failure.{Setup,Copy,Install,Status,Package})
```

Catatan penting (VERIFIED): `SandboxState` bisa **tidak berubah nilainya** pada switch distro (mis.
kedua distribusi belum terpasang). Karena `StateFlow` tidak memancarkan ulang nilai yang sama,
`AndroidSandboxController.selectDistro` **mempublikasikan status secara eksplisit** alih-alih
menunggu collector.

### 15.2 Sesi shell

```text
Absent
   ↓ shellFor(sessionId)  (lazy)
Live (bash --noprofile --norc berjalan)
   ├── run() sukses            → Live
   ├── run() timeout + cancel berhasil → Live (satu perintah menandai timed_out)
   ├── run() timeout + SIGKILL gagal   → reset() → Absent (perintah berikutnya membuat baru)
   ├── command cancel          → Cancelling → Live | Absent
   ├── bash exit (watchdog)    → Absent
   ├── closeShell(sessionId)   → Absent (permanen untuk sesi itu)
   ├── selectDistro()          → Absent (semua sesi)
   └── reset()                 → Absent (semua sesi)
```

### 15.3 Job background

```text
startBackground() → Session(status=running, stdout/stderr terisi saat selesai)
   ├── proses selesai → finished, exit_code, timed_out
   ├── kill          → finished=true, exit_code=-1, timed_out=true   (lihat §22)
   └── remove        → dibuang dari tabel
```

### 15.4 Data

`projects` (external) dan transkrip percakapan memiliki lifecycle yang **lebih panjang** daripada
rootfs: keduanya selamat dari uninstall sandbox.

## 21. Known Limitations

Semua butir di bawah adalah **VERIFIED** dari kode/komentar kecuali bila ditandai lain.

1. **Tidak ada PTY.** `vim`, `less`, `nano`, `top`, `htop`, program ncurses, dan `ssh -t host cmd`
   tidak bisa berjalan penuh layar: mereka menolak ("inappropriate ioctl for device" / "stdout is not
   a tty") atau memuntahkan escape code yang tidak dirender. Karena itu `bash` dijalankan **tanpa**
   `-i` (prompt akan mengotori stderr tanpa tempat merendernya). Layer PTY pernah dibuat dan
   di-revert — biaya (emulator terminal, IME, scrollback) tidak sepadan untuk v1.
   **INFERRED**: setiap program yang membutuhkan `isatty()` akan mengambil jalur non-interaktif.
2. **Tidak ada multiplexing SSH.** `ControlMaster` sengaja tidak diaktifkan. Protokol mux openssh
   membuat control socket lewat `link()`, dan `protected_hardlinks` + SELinux `untrusted_app` Android
   menolaknya untuk proses app; `proot` tidak bisa menyiasatinya karena kernel memeriksa uid asli.
   Gejala yang terdokumentasi: `muxserver_listen: link mux listener … Permission denied` pada setiap
   pemanggilan ssh. Setiap `ssh` melakukan handshake TCP+auth penuh.
3. **Prompt password interaktif tidak bisa dijawab openssh sendiri.** Tanpa PTY, `ssh` membaca dari
   `/dev/tty`, bukan stdin, jadi heredoc tidak bekerja. Jalur yang didokumentasikan: pasang `sshpass`
   dan pakai `sshpass -p '<pw>' ssh <alias>` (atau `-f <file>` agar password tidak ada di command line).
4. **`top`/`htop` tidak bisa melihat proses sistem.** Mount `/proc` Android memakai `hidepid=2`, jadi
   `/proc/<pid>` milik uid lain tidak terlihat; `proot` menulis ulang path tapi tidak bisa melewati
   penegakan uid kernel. Tidak ada perbaikan tanpa root. Yang bisa dipakai: `ps`, `ps -p $$`,
   `cat /proc/self/status`.
5. **Buffering stdout subproses.** `python3`/`node` sepenuhnya membuffer stdout ketika stdin adalah
   pipe; output tampak "macet" sampai buffer penuh atau proses keluar. Pakai `python3 -u` atau
   `stdbuf -o0`.
6. **App backgrounding mengakhiri sesi.** Saat Android membunuh proses app, setiap `proot` (dan
   karenanya setiap bash) ikut mati. `cwd`, env yang di-`export`, dan koneksi SSH/SFTP hilang. Tidak
   ada foreground service yang menahan sesi (trade-off sadar: tidak meminta izin itu). Transkrip
   *terlihat* tetap tersimpan.
7. **Biaya memori sesi ganda.** Setiap shell hidup adalah pasangan `proot+bash` (puluhan MB). Tidak ada
   soft cap; menutup percakapan membuang shellnya, `reset` membuang semua.
8. **Cancel tanpa PTY bersifat best-effort.** Anak proses yang mengabaikan `SIGINT`/`SIGTERM`
   memaksa reset sesi; state sesi untuk satu perintah itu hilang.
9. **Output dari job yang di-background-kan bisa menempel ke perintah lain** saat kernel melaporkan
   exit-nya (sesuai perilaku terminal normal).
10. **Sandbox tidak memberi isolasi keamanan kernel.** `proot` bukan container: ia membatasi diri pada
    apa yang Android izinkan untuk uid app, bukan pada apa yang kernel pisahkan. **INFERRED**:
    perintah di dalam sandbox berjalan dengan hak yang sama besarnya dengan aplikasi itu sendiri
    (jaringan, storage app, eksekusi). Fitur ini adalah "user space Linux", bukan sandbox keamanan.
11. **Android-only.** iOS, desktop, dan wasm memakai `NoOpSandboxController`; seluruh operasi
    mengembalikan hasil kosong/`Result.failure`. Karena itu UI sandbox pun digerbangi platform.
12. **`installedDistros` bergantung pada marker.** Direktori rootfs tanpa marker yang tidak punya
    bukti legacy tidak akan terbaca sebagai terpasang. **INFERRED**: install yang diinterupsi di
    tengah diekstraksi memang dilaporkan "tidak terpasang" — itu perilaku yang diinginkan, bukan bug.
13. **`sshpass` dan tool remote lain bukan bagian base.** `openssh-client`, `lftp`, `rsync`, dan
    `sshpass` harus di-*install on demand*; sebelum itu perintah remote gagal dengan "not found".
14. **Alpine dibatasi 3.22.** 3.23+ tidak bisa dipakai karena apk-tools 3 memakai `execveat()` yang
    tidak didukung `proot`.
15. **Kai Build hanya Debian.** Memilih Alpine untuk chat sandbox berarti Kai Build memasang Debian
    sendiri di sebelahnya — dua install, dua biaya disk (PROJECT-SPECIFIC: ini sifat fitur Kai Build,
    bukan sifat sandbox).
16. **Migrasi bukan pemindahan.** Paket tidak ikut pindah (package manager dan nama paket berbeda
    antar distribusi, jadi memang tidak mungkin).
17. **Disk usage dihitung dengan walk manual, bukan `walkTopDown()`**, karena yang terakhir bisa
    `AssertionError` bila entri berubah tipe di antara pengecekan dan konstruksi state; rootfs bisa
    berisi socket/FIFO/symlink rusak dan aktivitas instalasi paralel bisa membalap walk. Hasilnya:
    angka bisa sedikit tidak akurat pada filesystem yang berubah cepat.

## 22. Known Bugs

Tidak ada laporan bug yang diputuskan sebagai "bug" oleh audit ini. Dua hal berikut ditemukan dari
kode dan layak dicatat eksplisit sebelum dipindahkan:

### 22.1 `manage_process kill` tidak benar-benar membunuh proses

- **Fakta (VERIFIED)**: `ProcessManager.kill(sessionId)` hanya menandai objek `Session` in-memory
  (`finished = true`, `exitCode = -1`, `timedOut = true`) dan mengembalikan
  `"Process marked as terminated"`. Ia **tidak** menyentuh `Process`/`ProotHandle` yang dijalankan
  `CompletableFuture` di `startBackground`, dan tidak menyimpan referensi ke proses itu.
- **Reproduction**: jalankan `sleep 300` dengan `background=true`, lalu `manage_process kill`.
- **Expected**: proses guest benar-benar berhenti (atau pesan jujur bahwa ia hanya dilepas dari daftar).
- **Actual**: proses guest tetap berjalan sampai selesai/timeout; ia hanya tidak lagi terlihat sebagai
  "running" dari sudut pandang tool. Output akhirnya tetap dituliskan ke `Session`.
- **Possible cause**: `Session` tidak pernah menyimpan handle proses; hanya `ProotExecutor.execute`
  (one-shot blocking) yang dipakai, dan itu tidak mengembalikan handle yang bisa dibatalkan.
- **Workaround**: `reset` sandbox (membunuh semua `proot`) atau membiarkan timeout.
- **Status**: **INFERRED** sebagai keterbatasan yang belum ditangani, bukan keputusan desain yang
  didokumentasikan. Ketika memindahkan fitur ini, kontrak "kill harus benar-benar menghentikan proses"
  **harus** diimplementasikan — misalnya dengan memakai `executeStreaming` + `ProotHandle.cancel()`.

### 22.2 Tool `manage_process` terdaftar di desktop, sedangkan sandbox tidak

- **Fakta (VERIFIED)**: `ProcessManagerTool` hidup di `jvmShared` dan `createProcessManager()`
  di-supply oleh kedua build; di Android ia digerbangi oleh `isSandboxEnabled() && Ready`, tetapi
  desktop punya `ShellCommandTool` versi host-nya sendiri.
- **Konsekuensi**: "background process" di desktop adalah proses host biasa, bukan proses sandbox —
  artinya perilaku `kill` dan isolasinya berbeda antar platform.
- **Status**: **PROJECT-SPECIFIC**. Saat memindahkan, putuskan sejak awal apakah background job hidup
  di dalam sandbox atau di host; jangan mencampur keduanya tanpa menyebutkannya.

### 22.3 Yang **tidak** diklaim

Audit ini tidak menemukan, dan tidak menyimpulkan, adanya bug pada: framing sentinel, cancel
bergradasi, self-healing shell, adopsi marker legacy, merge-copy migrasi, atau penolakan traversal
path. Semuanya konsisten dengan komentar desainnya. Ini **bukan** pernyataan bahwa mereka bebas bug —
hanya bahwa pembacaan source tidak menemukan kontradiksi.
