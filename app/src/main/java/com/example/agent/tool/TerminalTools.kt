package com.example.agent.tool

import com.example.agent.model.ApprovalAwareTool
import com.example.agent.model.Tool
import com.example.agent.model.ToolPermission
import com.example.agent.model.ToolResult
import com.example.agent.terminal.ShellPolicy
import com.example.agent.terminal.ShellResult
import com.example.agent.terminal.TerminalManager

/**
 * Priority 3 + terminal goal — lets the agent actually *do* things on the device shell:
 * download with curl/wget, run a bash/python script, inspect files, call git/gh when the
 * device has them.
 *
 * Approval is applied per command, not per tool, because the interesting distinction is
 * what the command does: `ls -la` must not need a human, `pip install x` must. Commands the
 * [ShellPolicy] flags as destructive are parked as an approval request (the same
 * `PENDING_APPROVAL:<id>` contract the Agent Loop already understands) and only run after
 * the user answers `/approve <id>` (or the Setujui button), at which point the bridge calls
 * [executeApproved] to run the exact stored command.
 */
class TerminalTool(
    private val manager: TerminalManager,
    private val approvalEnabled: () -> Boolean,
    /** Stores the model's original arguments so the approved run is byte-for-byte the same call. */
    private val requestApproval: suspend (conversationId: String, arguments: String) -> String
) : Tool, ApprovalAwareTool {

    override val id: String = "builtin.run_command"
    override val name: String = "run_command"
    override val description: String =
        "Menjalankan satu perintah shell di perangkat (Android shell + toybox) dengan direktori kerja " +
            "di dalam workspace agent. Gunakan untuk curl/wget, menjalankan skrip bash atau python, " +
            "atau memeriksa file. Jalankan `terminal_info` lebih dulu untuk melihat biner apa saja yang " +
            "tersedia di perangkat ini. Perintah yang merusak (hapus data, pasang/hapus paket, sudo, " +
            "git push, tulis di luar workspace) otomatis meminta persetujuan pengguna."
    override val inputSchema: String = """
        {
          "type": "object",
          "properties": {
            "command": {
              "type": "string",
              "description": "Perintah shell, contoh: \"curl -s https://example.com\", \"sh bin/tugas.sh\", \"python3 skrip.py\"."
            },
            "working_directory": {
              "type": "string",
              "description": "Direktori kerja relatif di dalam workspace. Default: akar workspace."
            },
            "timeout_seconds": {
              "type": "integer",
              "description": "Batas waktu eksekusi dalam detik (1-120, default 30)."
            }
          },
          "required": ["command"]
        }
    """.trimIndent()

    override val permission: ToolPermission = ToolPermission.AUTO_SAFE

    override suspend fun execute(input: String): ToolResult {
        val command = JsonArgs.string(input, "command")?.trim().orEmpty()
        if (command.isEmpty()) {
            return ToolResult(success = false, output = "", error = "Argumen 'command' wajib diisi.")
        }

        val verdict = ShellPolicy.assess(command)
        if (verdict.destructive) {
            if (!approvalEnabled()) {
                return ToolResult(
                    success = false,
                    output = "",
                    error = "Perintah ini dinilai destruktif (${verdict.reason}) dan mode persetujuan sedang " +
                        "nonaktif, jadi tidak dijalankan. Minta pengguna mengaktifkan \"Persetujuan tool " +
                        "destruktif\" di Pengaturan bila memang ingin mengizinkan."
                )
            }
            val conversationId = currentToolConversation()
            val requestId = requestApproval(conversationId, input)
            return ToolResult(
                success = false,
                output = "",
                error = "PENDING_APPROVAL:$requestId:Perintah ini perlu persetujuan karena ${verdict.reason}. " +
                    "Beri tahu pengguna agar membalas /approve $requestId untuk menjalankan atau " +
                    "/reject $requestId untuk membatalkan."
            )
        }

        return runNow(input)
    }

    /** Runs the command that was already approved by the user. */
    override suspend fun executeApproved(input: String): ToolResult = runNow(input)

    private suspend fun runNow(input: String): ToolResult {
        val command = JsonArgs.string(input, "command")?.trim().orEmpty()
        if (command.isEmpty()) {
            return ToolResult(success = false, output = "", error = "Argumen 'command' wajib diisi.")
        }
        val workingDirectory = JsonArgs.string(input, "working_directory")
        val timeoutSeconds = (JsonArgs.int(input, "timeout_seconds") ?: 30).coerceIn(1, 120)

        val result = manager.runner.run(
            command = command,
            timeoutMs = timeoutSeconds * 1000L,
            workingDirectory = workingDirectory
        )
        return toToolResult(command, result)
    }

    private fun toToolResult(command: String, result: ShellResult): ToolResult {
        val body = buildString {
            appendLine("$ $command")
            appendLine("(exit code: ${result.exitCode}${if (result.timedOut) ", timeout" else ""})")
            appendLine("(cwd: ${result.cwd})")
            if (result.output.isBlank()) {
                append("(tanpa output)")
            } else {
                append(result.output)
            }
        }
        return ToolResult(
            success = result.success,
            output = body,
            error = if (result.success) null else "Perintah keluar dengan exit code ${result.exitCode}" +
                if (result.timedOut) " (timeout)" else "",
            metadata = mapOf(
                "exitCode" to result.exitCode.toString(),
                "cwd" to result.cwd
            )
        )
    }
}

/**
 * Reports the device toolchain (what is installed) plus the workspace layout, so the model
 * plans with facts instead of assuming a full Linux desktop. Read-only, runs automatically.
 */
class TerminalInfoTool(private val manager: TerminalManager) : Tool {

    override val id: String = "builtin.terminal_info"
    override val name: String = "terminal_info"
    override val description: String =
        "Menampilkan biner apa saja yang tersedia untuk tool run_command di perangkat ini " +
            "(curl, wget, python, git, gh, dan lainnya) beserta lokasi workspace agent. " +
            "Panggil ini sebelum memakai run_command bila tidak yakin perintah tersedia."
    override val inputSchema: String = """
        {
          "type": "object",
          "properties": {
            "refresh": {
              "type": "boolean",
              "description": "true untuk memindai ulang toolchain perangkat."
            }
          },
          "required": []
        }
    """.trimIndent()

    override val permission: ToolPermission = ToolPermission.AUTO_SAFE

    override suspend fun execute(input: String): ToolResult {
        val refresh = JsonArgs.boolean(input, "refresh") ?: false
        manager.loadCapabilities(force = refresh)
        val summary = buildString {
            appendLine(manager.capabilitiesSummary())
            appendLine()
            appendLine("Workspace agent: ${manager.runner.resolveInWorkspace(".").absolutePath}")
            appendLine("Direktori skrip: ${manager.runner.binDirectory.absolutePath}")
            appendLine("Direktori output: ${manager.runner.outputDirectory.absolutePath}")
            append("Semua perintah berjalan di dalam workspace ini (path absolut di luar workspace ditolak).")
        }
        return ToolResult(success = true, output = summary)
    }
}
