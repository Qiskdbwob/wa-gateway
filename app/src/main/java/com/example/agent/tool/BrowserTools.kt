package com.example.agent.tool

import com.example.agent.browser.BrowserAutomationManager
import com.example.agent.browser.BrowserSnapshot
import com.example.agent.model.Tool
import com.example.agent.model.ToolPermission
import com.example.agent.model.ToolResult

/**
 * Browser automation tools (goal: let the agent run a real browsing session — open a page,
 * read it, click, type, submit, scroll, screenshot — and post to a site like a social network
 * the user asked it to).
 *
 * All of them are AUTO_SAFE: they act on the page the user asked the agent to work with, and
 * they cannot touch the device outside that session. Human approval is requested *inside* the
 * flow only where a human is genuinely required (captcha/2FA), via [BrowserUserHelpTool].
 */

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
        val snapshot: BrowserSnapshot = manager.read(maxChars)
        if (!manager.isReady()) {
            return ToolResult(
                success = false,
                output = "",
                error = "Belum ada halaman terbuka. Panggil browser_open lebih dulu."
            )
        }
        return ToolResult(success = true, output = manager.describe(snapshot, maxChars))
    }
}

class BrowserClickTool(private val manager: BrowserAutomationManager) : Tool {
    override val id: String = "builtin.browser_click"
    override val name: String = "browser_click"
    override val description: String =
        "Mengklik elemen halaman memakai ref dari browser_read (contoh \"agx-3\")."
    override val inputSchema: String = """
        {
          "type": "object",
          "properties": {
            "ref": { "type": "string", "description": "Ref elemen, contoh agx-3." },
            "wait_ms": { "type": "integer", "description": "Jeda setelah klik sebelum membaca halaman, default 1500 ms." }
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
        return ToolResult(success = true, output = "${result.detail}\n\n" + manager.describe(manager.read(3_000), 3_000))
    }
}

class BrowserTypeTool(private val manager: BrowserAutomationManager) : Tool {
    override val id: String = "builtin.browser_type"
    override val name: String = "browser_type"
    override val description: String =
        "Mengetik teks ke sebuah elemen (input/textarea) memakai ref dari browser_read. " +
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
        return ToolResult(success = true, output = "${result.detail}\n\n" + manager.describe(manager.read(3_000), 3_000))
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
                error = "Pengguna belum menyelesaikan langkah manual dalam batas waktu. " +
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
