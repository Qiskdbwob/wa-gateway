# Desain UI — "Signal Console"

Dokumen ini menjelaskan sistem desain aplikasi, alasan di balik keputusan visualnya, dan
apa yang **belum** diverifikasi. Ditulis setelah redesign UI (lihat riwayat commit
`feat(ui): ...` dan `fix(ui): ...`).

## 1. Aplikasi ini apa, dan untuk siapa

Agent AI pribadi yang hidup di HP dan menjawab lewat WhatsApp. Pemakainya satu orang,
teknis, dan sedang **mengoperasikan** sebuah sistem: dia ingin tahu agent-nya hidup atau
tidak, sedang mengerjakan apa, butuh keputusan apa, dan apa yang baru saja terjadi.

Karena itu arah visualnya adalah **console untuk mesin yang berjalan**, bukan aplikasi
konsumen: informasi padat, status selalu terlihat, dan setiap angka mesin disajikan
apa adanya.

## 2. Keputusan yang diambil (dan alasannya)

| Aspek | Keputusan | Alasan |
| --- | --- | --- |
| Warna | **Statis**, dua skema (terang & gelap) yang dituning manual | Warna di app ini punya arti (emerald = agent hidup, hijau WhatsApp = kanal, amber = butuh manusia, merah = gagal). Arti itu tidak boleh berubah mengikuti wallpaper pengguna, jadi Material You dimatikan. |
| Tema | Mengikuti setelan sistem | Sesuai permintaan; terang dan gelap sama-sama dituning, bukan salah satunya hasil inversi otomatis. |
| Bentuk | Skala 4 / 8 / 12 / 16 / 24 dp | Sebelumnya ada 8 radius berbeda (6–24 dp) tanpa aturan, karena tiap kartu ditulis manual. Kontrol pakai ujung kecil, kontainer pakai `medium`, sheet pakai `extraLarge`. |
| Tipografi | Dua suara: sans platform untuk prosa, **monospace untuk data mesin** | Ini elemen tanda tangan app ini. Model id, timestamp, nama tool, output terminal, counter — semuanya mono. Turunan langsung dari subjeknya (telemetri agent), dan menggantikan dekorasi. |
| Elevasi | Tone permukaan (`surfaceContainer*`) + garis rambut `outlineVariant`, tanpa drop shadow | Sesuai arah Material 3: bayangan bukan satu-satunya pembawa hierarki. |
| Spasi | Ritme 8 dp (`Spacing.xxs…xxl`) | Menggantikan gap ad-hoc 2/4/6/10/12/14/16/20/24 dp. |
| Gerak | Transisi antar-tab & antar-layar singkat (140–220 ms), tanpa bounce berlebihan | Gerak dipakai untuk memberi tahu *arah* navigasi, bukan hiasan. Semua spinner/label tetap terbaca tanpa animasi. |
| Ikon di navigasi | Satu sumber: `MainTab` | Dulu shell menulis ulang daftarnya dengan ikon berbeda (`Home` vs `Dashboard`), jadi bottom bar dan enum bisa tidak sinkron. |

### Peran warna status

Material tidak punya slot untuk "tool sedang jalan" atau "agent butuh kamu", sehingga
sebelumnya muncul 65 nilai hex mentah di sembilan file. Sekarang ada `StatusPalette`
(`success`, `warning`, `info`, `neutral`, `danger`) yang diakses lewat
`MaterialTheme.status.*`, dengan kontras diperiksa untuk tema terang **dan** gelap.

Contoh yang diperbaiki: emerald `#10B981` sebagai teks di atas putih hanya 2.5:1 (syarat
4.5:1). Di tema terang sekarang teks memakai `#047857` (5.5:1); `#25D366` (hijau WhatsApp)
disimpan khusus sebagai *isi* (dot, track switch) dan bukan teks. Terminal & console log
memakai token `TerminalSurface`/`TerminalInk` (`#E2E8F0` di atas `#0B111B` = 15:1) dan
sengaja gelap di kedua tema.

### Status tidak pernah hanya warna

Setiap status = ikon + label + warna (`StatusBadge`), karena dot berwarna saja tidak
terbaca oleh pengguna dengan buta warna maupun TalkBack.

## 3. Struktur navigasi

- **Phone (< 600 dp)**: bottom navigation bar 5 tab.
- **≥ 600 dp (tablet, foldable terbuka, ChromeOS)**: navigation rail + kolom konten
  dibatasi `Sizes.contentMaxWidth = 640.dp` supaya layout HP tidak direntangkan.
  Dipilih tanpa dependensi baru (`NavigationRail` sudah ada di material3), sehingga tidak
  menambah risiko versi.
- **Sub-layar** (Gateway, Terminal, Browser, Developer & Diagnostik) didorong di atas
  shell dengan transisi geser + fade, dan kembali ke tab asal.
- **Deep link ke bagian Pengaturan**: `SettingsSection` menentukan tujuan; tiap
  `SectionHeader` mengukur offset-nya sendiri, dan ada baris chip untuk lompat antar
  bagian. Sebelumnya menekan peringatan keamanan di Beranda hanya membuang pengguna ke
  atas halaman 1200 baris tanpa petunjuk.
- **State navigasi** pakai `rememberSaveable`: proses mati atau rotasi kembali ke tab
  yang sama, bukan reset ke Beranda.
- **Back**: satu tingkat demi satu tingkat — tutup sub-layar, lalu kembali ke Beranda,
  lalu keluar aplikasi.

## 4. Bug frontend yang diperbaiki

1. Memori → tab **Skill tidak bisa di-scroll**: kontennya `Column` di dalam
   `Box(fillMaxSize)` tanpa scroll, jadi kartu di bawah layar pertama tak terjangkau.
2. Tugas → filter **Terjadwal menampilkan tiap task dua kali** (blok kontrol di atas
   daftar + kartu di dalam daftar). Kontrolnya dipindah ke sheet detail.
3. `CompactCard` selalu `clickable` walau tanpa aksi → diumumkan TalkBack sebagai tombol
   yang tidak melakukan apa pun.
4. `SubScreen.TaskDetail` adalah state navigasi mati (tidak pernah bisa dibuat).
5. Tombol ikon 32/36 dp dan tombol aksi section 32 dp — di bawah target sentuh 48 dp.
6. `Divider` (deprecated) → `HorizontalDivider`; `Color.Red`/`Color.Gray` mentah → peran
   tema.
7. Campur bahasa di UI berbahasa Indonesia ("Sessions", "Running", "Connected",
   "Memory & Persistence", "Ready (Idle)") → konsisten Indonesia.
8. Baris timestamp + aksi di kartu sesi bisa mendorong tombolnya keluar layar pada skala
   font besar → teks diberi `weight(1f)`.

## 5. Belum diverifikasi (jujur)

Repo ini tidak punya JDK/Android SDK lokal, jadi verifikasi hanya lewat GitHub Actions
(kompilasi + unit test + APK debug). Yang **tidak** bisa diperiksa di sini:

- Perilaku keyboard/IME di perangkat nyata.
- Tampilan aktual tema terang vs gelap (kontras dihitung manual, bukan diukur dari hasil
  render).
- Layout rail di tablet/foldable dan postur lipatan.
- Layar WebView (Gateway QR, Browser handoff) — butuh device.
- Predictive back (pratinjau gesture) belum diaktifkan; butuh
  `android:enableOnBackInvokedCallback` + uji perangkat, jadi sengaja ditunda.

## 6. Sisa pekerjaan yang diketahui

- Beberapa kartu lama di Chat/Pengaturan/Gateway/Developer masih memakai pola
  `Card(...)` langsung alih-alih `ConsoleCard`; secara warna sudah memakai token tema,
  tapi belum seragam bentuknya.
- Belum ada baseline profile / screenshot test untuk UI (Roborazzi sudah tersedia tapi
  test screenshot masih opt-in).
- Per-ABI split APK (lihat `DOC/riset-optimasi.md`).
