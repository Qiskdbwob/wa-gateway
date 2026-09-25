package com.example.agent.tool

import com.example.agent.model.Tool
import com.example.agent.model.ToolPermission
import com.example.agent.model.ToolResult

/**
 * Sends a file produced inside the workspace back to the user's chat (a screenshot taken by
 * the browser, a report the model wrote, a document it downloaded). Without this, an agent
 * that can *make* files could never hand them to the person on the other end of WhatsApp.
 */
class SendFileToChatTool(
    private val sendFile: suspend (conversationId: String, path: String, caption: String) -> ToolResult
) : Tool {

    override val id: String = "builtin.send_file_to_chat"
    override val name: String = "send_file_to_chat"
    override val description: String =
        "Mengirim file dari workspace agent ke chat pengguna (gambar dikirim sebagai foto, " +
            "tipe lain sebagai dokumen). Gunakan untuk mengirim hasil screenshot, laporan, " +
            "atau file yang baru dibuat/diunduh."
    override val inputSchema: String = """
        {
          "type": "object",
          "properties": {
            "path": { "type": "string", "description": "Path file relatif di dalam workspace, contoh output/screenshot-1.png" },
            "caption": { "type": "string", "description": "Keterangan singkat untuk pengguna." }
          },
          "required": ["path"]
        }
    """.trimIndent()

    override val permission: ToolPermission = ToolPermission.AUTO_SAFE

    override suspend fun execute(input: String): ToolResult {
        val path = JsonArgs.string(input, "path")?.trim().orEmpty()
        if (path.isEmpty()) return ToolResult(false, "", "Argumen 'path' wajib diisi.")
        val caption = JsonArgs.string(input, "caption").orEmpty()
        val conversationId = currentToolConversation()
        if (conversationId.isEmpty()) {
            return ToolResult(
                success = false,
                output = "",
                error = "Tidak ada konteks percakapan (file hanya bisa dikirim dari dalam percakapan)."
            )
        }
        return sendFile(conversationId, path, caption)
    }
}
