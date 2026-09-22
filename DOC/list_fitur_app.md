#list fitur

- memory episodic + refleksi berkala (gaya generative agents dari standdord)
- learning periodic (mencatat pembelajaran atau mencatat hasil pembelajaran dari kesalahan yang telah dilakukan menjadi sebuah nota atau skill markdown atau aturan yang bisa dibaca lagi untuk nanti bila ada masalah yang sama)
- search (search_tools, search_memory, search_learning, search_sesiChat atau alat searching bagi agent untuk mencari baik itu tools yang tersedia memory dan lainnya).
- sub_agent : agent utama bisa panggil sub_agent lain di latar belakang tanpa mengganggu agent utama. juga agent utama bisa menentukan sub agent itu pakai model apa, tugasnya, identitas/personanya dan alat yang bisa dipakainya.
- workspace isolasi dengan membuat path absolut agar agent tidak keluar dari path workspace.
- tools-kemampuan built-in: File i/o hanya berlaku pada workspace saja, searching web - web fetch searching via scrape bing & ddg, skill markdown (universal-opsional dimana support semua skill dari hermes, openclaw, claude code dan lainnya), eksekusi terminal dengan batasan tidak boleh install package, update - upgrade package dan hapus perlu approve manual dari user.
- plug-in: MCP HTTPS/HTTP/SSE - user bisa menambahkan nama. url base serta header untuk menambahkan kemampuan MCP yang di inginkannya.
- provider custom openai compact, gemini-google & anthropic
- routing: keys pool, fallback otomatis (user bisa buat sebuah configurasi combo untuk model apa aja yang akan digunakan dengan fallback otomatis ke model yang telah diatur), 
- tes latensi pada model yang telah dipilih + penambahan custom id model bila model tidak terindeks otomatis (test dengan cara request pesan seperti "1 + 1 = " untuk mengetahui latensi dan ketersediaan model sekaligus)
- agent utama bisa panggil sub_agent dengan model yang support image understand untuk mengetahui image yang diberikan user bila agent utama ga support vission.
- mode: agent utama bisa melaksanakan mode council (memanggil beberapa sub_agent secara langsung untuk berdepat dengan batasan yang telah di tentukan agent utama bila agent utama diberi perintah council atau keinginannya sendiri untuk memahami berbagai pandangan dari banyak model/persona) agent utama bertindak sebagai moderator atau host untuk mengatur arah debat dan perhentiannya.
- sub_agent khusus media understanding (atau image understanding) yang bisa dipanggil agent utama dilatarbelakang ketika user memberikan video atau image juga bila model utama yang berperan sebagai agent utama ga support vission/multimodal. sub_agent media understanding mesti bisa memahami video atau image (saya akan pakai model gemini karena ia multimodal support video dan image. lihat cara implementasi pada google bila belum mengetahui). user kirim media > agent utama ga support media > panggil agent media understanding > agent utama konfirmasi contoh "tunggu biar <nama panggilan agent understanding> menganalisis <media yang diberikan> nanti saya agent utama beritau lagi"

#layout atau fitur pada aplikasi

- dasboard realtime agent, catat token yang telah digunakan, catat latensi dari input user ke output agent atau dari mulai streaming token hingga pemberian ouput ke user.
- log penggunaan model/provider seperti apakah model berganti atau ga, catat peralihan model (fallback route), log aktifitas penggunaan tool oleh model
- tab/screen playground tempat chat dengan agent/model
- terminal built-in toy-box/proot (jika memungkinkan) utamakan bisa eksekusi shell seperti curl, gh cli, git.
- browser automation dengan gecko bukan webview (scroll, screenshoot, input, sumbit, (form) write)