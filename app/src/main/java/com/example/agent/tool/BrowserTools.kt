package com.example.agent.tool

import com.example.agent.browser.BrowserActionResult
import com.example.agent.browser.BrowserAutomationManager
import com.example.agent.browser.BrowserSnapshot
import com.example.agent.model.Tool
import com.example.agent.model.ToolPermission
import com.example.agent.model.ToolResult
import kotlinx.coroutines.delay

/**
 * Browser automation tools (goal: let the agent run a real browsing session — open a page,
 * read it, click, type, select, press a key, submit, scroll, screenshot — and post to a site like
 * a social network the user asked it to).
 *
 * All of them are AUTO_SAFE: they act on the page the user asked the agent to work with, and
 * they cannot touch the device outside that session. Human approval is requested *inside* the
 * flow only where a human is genuinely required (captcha/2FA), via [BrowserUserHelpTool].
 *
 * The engine, not these tools, owns validation and verification: every action is checked against
 * the live element (visible, enabled, not covered) before it runs, the DOM is given time to settle
 * afterwards, and a before/after fingerprint decides whether anything actually changed.
 */

/**
 * Shared output for a mutating browser action: the engine's detail, an honest "nothing changed"
 * note when the fingerprints matched, and the fresh page view so the model does not need an extra
 * round-trip on `browser_read`.
 */
private fun describeActionResult(
    manager: BrowserAutomationManager,
    result: BrowserActionResult,
    maxChars: Int = 3_000
): String {
    val noChange = if (result.changed) {
        ""
    } else {
        "\n\n⚠️ Tidak ada perubahan halaman yang terdeteksi dari aksi ini. Aksi mungkin belum " +
            "berpengaruh (mis. tombol inert, overlay menutupi, atau halaman belum selesai " +
            "merender). Ambil browser_read terbaru sebelum melanjutkan."
    }
    return result.detail + noChange + "\n\n" + manager.describe(manager.read(maxChars), maxChars)
}

class BrowserOpenTool(private val manager: BrowserAutomationManager) : Tool {
    override val id: String = "builtin.browser_open"
    override val name: String = "browser_open"
    override val description: String =
        "Membuka URL di browser otomatis bawaan aplikasi (sesi terpisah dari aplikasi lain, " +
            "cookie-nya tersimpan sehingga login tetap aktif). Kembalikan ringkasan halaman."
    override val inputSchema: String = """
        {
          "type": "object",
          "properties": {
            "url": { "type": "string", "description": "URL lengkap, contoh https://www.instagram.com" }
          },
          "required": ["url"]
        }
    """.trimIndent()

    override val permission: ToolPermission = ToolPermission.AUTO_SAFE

    override suspend fun execute(input: String): ToolResult {
        val url = JsonArgs.string(input, "url")?.trim().orEmpty()
        if (url.isEmpty()) return ToolResult(false, "", "Argumen 'url' wajib diisi.")
        val result = manager.open(url)
        if (!result.ok) {
            return ToolResult(false, "", "Gagal membuka $url: ${result.error ?: "tidak diketahui"}")
        }
        val snapshot = manager.read(4_000)
        return ToolResult(
            success = true,
            output = "Halaman dibuka (${result.url}).\n\n" + manager.describe(snapshot, 3_000),
            metadata = mapOf("url" to result.url)
        )
    }
}

class BrowserReadTool(private val manager: BrowserAutomationManager) : Tool {
    override val id: String = "builtin.browser_read"
    override val name: String = "browser_read"
    override val description: String =
        "Membaca halaman yang sedang terbuka: teks yang terlihat beserta daftar elemen interaktif " +
            "(ref untuk browser_click/browser_type). Panggil ulang setelah halaman berubah."
    override val inputSchema: String = """
        {
          "type": "object",
          "properties": {
            "max_chars": { "type": "integer", "description": "Batas karakter teks, default 6000." }
          },
          "required": []
        }
    """.trimIndent()

    override val permission: ToolPermission = ToolPermission.AUTO_SAFE

    override suspend fun execute(input: String): ToolResult {
        val maxChars = (JsonArgs.int(input, "max_chars") ?: 6_000).coerceIn(500, 20_000)
        if (!manager.isReady()) {
            return ToolResult(
                success = false,
                output = "",
                error = "Belum ada halaman terbuka. Panggil browser_open lebih dulu."
            )
        }
        val snapshot: BrowserSnapshot = manager.read(maxChars)
        return ToolResult(success = true, output = manager.describe(snapshot, maxChars))
    }
}

class BrowserClickTool(private val manager: BrowserAutomationManager) : Tool {
    override val id: String = "builtin.browser_click"
    override val name: String = "browser_click"
    override val description: String =
        "Mengklik elemen halaman memakai ref dari browser_read (contoh \"agx-3\"). Mesin " +
            "memverifikasi elemen masih ada, aktif, dan tidak tertutup overlay sebelum mengklik."
    override val inputSchema: String = """
        {
          "type": "object",
          "properties": {
            "ref": { "type": "string", "description": "Ref elemen, contoh agx-3." },
            "wait_ms": { "type": "integer", "description": "Jeda tambahan setelah klik sebelum membaca halaman (ms). Mesin sudah menunggu DOM stabil; pakai ini hanya untuk situs yang lambat." }
          },
          "required": ["ref"]
        }
    """.trimIndent()

    override val permission: ToolPermission = ToolPermission.AUTO_SAFE

    override suspend fun execute(input: String): ToolResult {
        val ref = JsonArgs.string(input, "ref")?.trim().orEmpty()
        if (ref.isEmpty()) return ToolResult(false, "", "Argumen 'ref' wajib diisi.")
        val result = manager.click(ref)
        if (!result.ok) return ToolResult(false, "", result.error ?: "Klik gagal.")
        // `wait_ms` used to be documented in the schema but never read; it is now an *extra* delay
        // on top of the engine's settle wait, for the sites where even that is too fast.
        val waitMs = (JsonArgs.int(input, "wait_ms") ?: 0).coerceIn(0, 10_000)
        if (waitMs > 0) delay(waitMs.toLong())
        return ToolResult(success = true, output = describeActionResult(manager, result))
    }
}

class BrowserTypeTool(private val manager: BrowserAutomationManager) : Tool {
    override val id: String = "builtin.browser_type"
    override val name: String = "browser_type"
    override val description: String =
        "Mengetik teks ke sebuah elemen (input/textarea/select) memakai ref dari browser_read. " +
            "Set submit=true untuk langsung mengirim form (tombol Enter/submit)."
    override val inputSchema: String = """
        {
          "type": "object",
          "properties": {
            "ref": { "type": "string", "description": "Ref elemen input, contoh agx-1." },
            "text": { "type": "string", "description": "Teks yang diketik." },
            "submit": { "type": "boolean", "description": "true untuk mengirim form setelah mengetik. Default false." }
          },
          "required": ["ref", "text"]
        }
    """.trimIndent()

    override val permission: ToolPermission = ToolPermission.AUTO_SAFE

    override suspend fun execute(input: String): ToolResult {
        val ref = JsonArgs.string(input, "ref")?.trim().orEmpty()
        val text = JsonArgs.string(input, "text") ?: ""
        if (ref.isEmpty()) return ToolResult(false, "", "Argumen 'ref' wajib diisi.")
        val submit = JsonArgs.boolean(input, "submit") ?: false
        val result = manager.type(ref, text, submit)
        if (!result.ok) return ToolResult(false, "", result.error ?: "Mengetik gagal.")
        return ToolResult(success = true, output = describeActionResult(manager, result))
    }
}

/**
 * Picks an option in a native `<select>`. Custom dropdowns built from `<div>`s are not `<select>`
 * elements — the engine says so honestly instead of silently doing nothing, and the model should
 * then open the dropdown with browser_click and click the option.
 */
class BrowserSelectTool(private val manager: BrowserAutomationManager) : Tool {
    override val id: String = "builtin.browser_select"
    override val name: String = "browser_select"
    override val description: String =
        "Memilih opsi pada elemen <select> memakai ref dari browser_read. Cocokkan lewat value " +
            "atau teks labelnya (mis. \"Indonesia\"). Jika elemennya bukan <select>, tool ini " +
            "melaporkannya; pakai browser_click pada opsinya untuk dropdown kustom."
    override val inputSchema: String = """
        {
          "type": "object",
          "properties": {
            "ref": { "type": "string", "description": "Ref elemen <select>, contoh agx-4." },
            "value": { "type": "string", "description": "Nilai opsi: value-nya atau teks labelnya (mis. \"Indonesia\")." }
          },
          "required": ["ref", "value"]
        }
    """.trimIndent()

    override val permission: ToolPermission = ToolPermission.AUTO_SAFE

    override suspend fun execute(input: String): ToolResult {
        val ref = JsonArgs.string(input, "ref")?.trim().orEmpty()
        val value = JsonArgs.string(input, "value")?.trim().orEmpty()
        if (ref.isEmpty()) return ToolResult(false, "", "Argumen 'ref' wajib diisi.")
        if (value.isEmpty()) return ToolResult(false, "", "Argumen 'value' wajib diisi.")
        val result = manager.select(ref, value)
        if (!result.ok) return ToolResult(false, "", result.error ?: "Memilih opsi gagal.")
        return ToolResult(success = true, output = describeActionResult(manager, result))
    }
}

/**
 * Sends one key to the focused element. This is the only way to close an overlay (Escape), move
 * between fields (Tab) or submit a widget that is not inside a `<form>` (Enter).
 */
class BrowserPressKeyTool(private val manager: BrowserAutomationManager) : Tool {
    override val id: String = "builtin.browser_press_key"
    override val name: String = "browser_press_key"
    override val description: String =
        "Menekan satu tombol pada elemen yang sedang fokus: Enter (submit/cari), Escape (tutup " +
            "modal/dropdown), Tab (pindah field), Backspace, Space, dan tombol panah."
    override val inputSchema: String = """
        {
          "type": "object",
          "properties": {
            "key": {
              "type": "string",
              "enum": ["Enter", "Escape", "Tab", "Backspace", "Space", "ArrowUp", "ArrowDown", "ArrowLeft", "ArrowRight"],
              "description": "Tombol yang ditekan."
            }
          },
          "required": ["key"]
        }
    """.trimIndent()

    override val permission: ToolPermission = ToolPermission.AUTO_SAFE

    override suspend fun execute(input: String): ToolResult {
        val key = JsonArgs.string(input, "key")?.trim().orEmpty()
        if (key.isEmpty()) return ToolResult(false, "", "Argumen 'key' wajib diisi.")
        val result = manager.pressKey(key)
        if (!result.ok) return ToolResult(false, "", result.error ?: "Menekan tombol gagal.")
        return ToolResult(success = true, output = describeActionResult(manager, result))
    }
}

class BrowserScrollTool(private val manager: BrowserAutomationManager) : Tool {
    override val id: String = "builtin.browser_scroll"
    override val name: String = "browser_scroll"
    override val description: String = "Menggulir halaman: direction = down | up | top | bottom."
    override val inputSchema: String = """
        {
          "type": "object",
          "properties": {
            "direction": { "type": "string", "enum": ["down", "up", "top", "bottom"] },
            "amount": { "type": "integer", "description": "Jumlah piksel, default 800." }
          },
          "required": ["direction"]
        }
    """.trimIndent()

    override val permission: ToolPermission = ToolPermission.AUTO_SAFE

    override suspend fun execute(input: String): ToolResult {
        val direction = JsonArgs.string(input, "direction")?.trim()?.lowercase().orEmpty()
        if (direction.isEmpty()) return ToolResult(false, "", "Argumen 'direction' wajib diisi.")
        val amount = (JsonArgs.int(input, "amount") ?: 800).coerceIn(100, 10_000)
        val result = manager.scroll(direction, amount)
        if (!result.ok) return ToolResult(false, "", result.error ?: "Gulir gagal.")
        return ToolResult(success = true, output = result.detail)
    }
}

class BrowserScreenshotTool(
    private val manager: BrowserAutomationManager,
    /** Stores the PNG inside the workspace and returns its workspace-relative path. */
    private val saveScreenshot: (bytes: ByteArray, fileName: String) -> String
) : Tool {
    override val id: String = "builtin.browser_screenshot"
    override val name: String = "browser_screenshot"
    override val description: String =
        "Mengambil tangkapan layar halaman yang sedang terbuka dan menyimpannya di workspace " +
            "(folder output). Pakai send_file_to_chat untuk mengirimkannya ke pengguna."
    override val inputSchema: String = """
        {
          "type": "object",
          "properties": {
            "file_name": { "type": "string", "description": "Nama file opsional, default screenshot-<waktu>.png" }
          },
          "required": []
        }
    """.trimIndent()

    override val permission: ToolPermission = ToolPermission.AUTO_SAFE

    override suspend fun execute(input: String): ToolResult {
        val requested = JsonArgs.string(input, "file_name")?.trim().orEmpty()
        val name = if (requested.isBlank()) {
            "screenshot-" + System.currentTimeMillis() + ".png"
        } else {
            requested.substringAfterLast('/').ifBlank { "screenshot.png" }.let {
                if (it.endsWith(".png", true)) it else "$it.png"
            }
        }
        val bytes = manager.screenshot()
            ?: return ToolResult(
                success = false,
                output = "",
                error = "Tidak bisa mengambil tangkapan layar (browser belum membuka halaman)."
            )
        val path = saveScreenshot(bytes, name)
        return ToolResult(
            success = true,
            output = "Tangkapan layar disimpan: $path (${bytes.size / 1024} KB). " +
                "Kirim ke pengguna dengan send_file_to_chat bila perlu.",
            metadata = mapOf("path" to path)
        )
    }
}

/**
 * Signs in using an account the user stored in Settings. The password is never echoed back,
 * and if the site asks for a captcha/2FA the tool parks the turn and asks the user to finish
 * it by hand in the Browser tab.
 */
class BrowserLoginTool(private val manager: BrowserAutomationManager) : Tool {
    override val id: String = "builtin.browser_login"
    override val name: String = "browser_login"
    override val description: String =
        "Login ke sebuah situs memakai akun yang sudah disimpan pengguna di Pengaturan → Browser. " +
            "Bila muncul captcha/2FA, pengguna akan diminta menyelesaikannya di tab Browser."
    override val inputSchema: String = """
        {
          "type": "object",
          "properties": {
            "site": { "type": "string", "description": "Nama situs seperti yang disimpan, contoh instagram." }
          },
          "required": ["site"]
        }
    """.trimIndent()

    override val permission: ToolPermission = ToolPermission.AUTO_SAFE

    override suspend fun execute(input: String): ToolResult {
        val site = JsonArgs.string(input, "site")?.trim().orEmpty()
        if (site.isEmpty()) return ToolResult(false, "", "Argumen 'site' wajib diisi.")
        val result = manager.login(site, currentToolConversation())
        if (!result.ok) {
            return ToolResult(false, "", result.error ?: "Login $site gagal.")
        }
        return ToolResult(
            success = true,
            output = "${result.detail}\n\n" + manager.describe(manager.read(3_000), 3_000)
        )
    }
}

/**
 * Hands control to the human for a step only a human can pass (captcha, OTP, 2FA). The agent's
 * turn waits until the user confirms in the Browser screen, so it must be used deliberately.
 */
class BrowserUserHelpTool(private val manager: BrowserAutomationManager) : Tool {
    override val id: String = "builtin.browser_ask_user"
    override val name: String = "browser_ask_user"
    override val description: String =
        "Meminta pengguna menyelesaikan langkah manual di halaman yang sedang terbuka " +
            "(captcha, kode OTP, verifikasi 2 langkah). Pengguna akan menerima pesan di chat dan " +
            "menekan tombol konfirmasi di tab Browser; agent menunggu sampai itu terjadi."
    override val inputSchema: String = """
        {
          "type": "object",
          "properties": {
            "instruction": { "type": "string", "description": "Apa yang harus dilakukan pengguna di halaman itu." }
          },
          "required": ["instruction"]
        }
    """.trimIndent()

    override val permission: ToolPermission = ToolPermission.AUTO_SAFE

    override suspend fun execute(input: String): ToolResult {
        val instruction = JsonArgs.string(input, "instruction")?.trim().orEmpty()
        if (instruction.isEmpty()) return ToolResult(false, "", "Argumen 'instruction' wajib diisi.")
        val finished = manager.requestUserHelp(instruction, currentToolConversation())
        return if (finished) {
            ToolResult(
                success = true,
                output = "Pengguna menyatakan langkah manual selesai.\n\n" +
                    manager.describe(manager.read(4_000), 4_000)
            )
        } else {
            ToolResult(
                success = false,
                output = "",
                error = "Pengguna belum menyelesaikan (atau membatalkan) langkah manual. " +
                    "Jangan mengarang hasil; beri tahu pengguna bahwa langkah itu masih tertunda."
            )
        }
    }
}

class BrowserClearSessionTool(private val manager: BrowserAutomationManager) : Tool {
    override val id: String = "builtin.browser_logout"
    override val name: String = "browser_logout"
    override val description: String =
        "Menghapus cookie & cache sesi browser (logout dari semua situs). Gunakan hanya bila " +
            "pengguna meminta keluar dari akun."
    override val inputSchema: String = """{"type":"object","properties":{},"required":[]}"""

    override val permission: ToolPermission = ToolPermission.AUTO_SAFE

    override suspend fun execute(input: String): ToolResult {
        manager.clearSession()
        return ToolResult(success = true, output = "Sesi browser dibersihkan (cookie, cache, riwayat).")
    }
}
