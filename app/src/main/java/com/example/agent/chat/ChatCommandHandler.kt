package com.example.agent.chat

import com.example.agent.approval.ApprovalCoordinator
import com.example.agent.loop.AgentLoop
import com.example.agent.memory.CompactManager
import com.example.agent.memory.MemoryRepository
import com.example.agent.model.AgentRole
import com.example.agent.provider.ModelProvider
import com.example.agent.storage.ContactAccessRepository
import com.example.agent.storage.agentIdFromJid
import com.example.agent.storage.dao.ScheduledTaskDao
import com.example.agent.storage.entity.MemoryItemEntity

/**
 * Priorities 1-4 — WhatsApp chat commands, handled BEFORE anything reaches the agent.
 *
 * Commands (Indonesian UI, English keywords like the DOC conventions):
 *   /help                          — daftar perintah
 *   /whitelist add|del <no> [label]— kelola whitelist (whitelist ON juga lewat sini)
 *   /blacklist add|del <no> [label]
 *   /whitelist on|off              — mode whitelist global (hanya nomor tertentu bisa akses)
 *   /approve <id> / /reject <id>   — setujui/tolak tool destruktif yang pending
 *   /compact                       — ringkas riwayat percakapan ini
 *   /remember <teks>               — simpan fakta ke memori jangka panjang
 *   /learning [approve|reject <id>]— kelola kandidat pembelajaran
 *   /status                        — ringkasan status agent
 */
class ChatCommandHandler(
    private val contactAccess: ContactAccessRepository,
    private val approvals: ApprovalCoordinator,
    private val memory: MemoryRepository,
    private val compact: CompactManager,
    private val scheduledTaskDao: ScheduledTaskDao,
    private val agentLoop: AgentLoop,
    private val isWhitelistMode: () -> Boolean,
    private val setWhitelistMode: (Boolean) -> Unit,
    /** Executes an approved destructive tool call and returns the human-readable result. */
    private val executeApprovedTool: suspend (toolName: String, arguments: String, conversationId: String) -> String,
    /** Compact needs a provider; the bridge resolves the current one lazily. */
    private val resolveProvider: () -> Pair<ModelProvider, String?>?,
    /** Live context bound from settings, so /compact honours the configured value. */
    private val maxContextMessages: () -> Int = { 30 },
    /**
     * Runs a shell command for `/terminal`. Implemented by the bridge, which applies the
     * destructive-command policy (the same one the `run_command` tool uses) and turns a
     * destructive command into an approval request instead of executing it.
     */
    private val runTerminalCommand: suspend (command: String, conversationId: String) -> String =
        { _, _ -> "Tool terminal tidak aktif. Aktifkan di Pengaturan → Terminal." },
    /** Opens a URL for `/browser`. Implemented by the bridge. */
    private val openBrowserUrl: suspend (url: String, conversationId: String) -> String =
        { _, _ -> "Browser automation tidak aktif. Aktifkan di Pengaturan → Browser." }
) {

    sealed class Result {
        /** None of the handlers consumed the input — it is a normal agent message. */
        object NotACommand : Result()

        /** The command was handled; [reply] is what the bot sends back. */
        data class Handled(val reply: String) : Result()
    }

    suspend fun handle(text: String, conversationId: String, contactId: String, sessionId: String?): Result {
        val trimmed = text.trim()
        if (!trimmed.startsWith("/")) return Result.NotACommand
        val parts = trimmed.split(Regex("\\s+"))
        val command = parts[0].lowercase().removePrefix("/")

        return when (command) {
            "help" -> Result.Handled(helpText())

            "whitelist" -> handleWhitelist(parts.drop(1), contactId)

            "blacklist" -> handleBlacklist(parts.drop(1), contactId)

            "approve" -> handleApproval(parts.getOrNull(1), conversationId, approved = true)

            "reject" -> handleApproval(parts.getOrNull(1), conversationId, approved = false)

            "compact" -> handleCompact(sessionId, conversationId)

            "remember" -> {
                val content = trimmed.removePrefix("/remember").trim()
                if (content.isEmpty()) {
                    Result.Handled("Format: /remember <fakta yang ingin diingat>")
                } else {
                    memory.remember(
                        content = content,
                        type = MemoryItemEntity.TYPE_KNOWLEDGE,
                        conversationId = contactId,
                        source = MemoryItemEntity.SOURCE_MANUAL
                    )
                    Result.Handled("🧠 Tersimpan ke memori jangka panjang: \"$content\"")
                }
            }

            "learning" -> handleLearning(parts.drop(1))

            "terminal" -> {
                val command = trimmed.removePrefix("/terminal").trim()
                if (command.isEmpty()) {
                    Result.Handled("Format: /terminal <perintah>\nContoh: /terminal curl -s https://example.com")
                } else {
                    Result.Handled(runTerminalCommand(command, conversationId))
                }
            }

            "browser" -> {
                val url = trimmed.removePrefix("/browser").trim()
                if (url.isEmpty()) {
                    Result.Handled("Format: /browser <url>\nContoh: /browser https://example.com")
                } else {
                    Result.Handled(openBrowserUrl(url, conversationId))
                }
            }

            "status" -> Result.Handled(statusText(contactId))

            else -> Result.NotACommand
        }
    }

    private suspend fun handleWhitelist(args: List<String>, senderContact: String): Result {
        if (args.isEmpty()) return Result.Handled(whitelistUsage())
        return when (args[0].lowercase()) {
            "on" -> {
                setWhitelistMode(true)
                Result.Handled(
                    "🔐 Mode whitelist AKTIF. Hanya nomor yang ada di whitelist yang bisa berbicara dengan agent.\n" +
                        "Tambahkan nomor: /whitelist add <nomor> [label]"
                )
            }

            "off" -> {
                setWhitelistMode(false)
                Result.Handled("Mode whitelist dimatikan. Semua nomor bisa berbicara kecuali yang di blacklist.")
            }

            "add" -> {
                val number = args.getOrNull(1)?.let { ContactAccessRepository.normalizePhone(it) }.orEmpty()
                if (number.length < 6) return Result.Handled(whitelistUsage())
                val label = args.drop(2).joinToString(" ").ifBlank { null }
                contactAccess.allow(number, label)
                Result.Handled("✅ $number ditambahkan ke whitelist${label?.let { " ($it)" } ?: ""}.")
            }

            "del", "remove", "hapus" -> {
                val number = args.getOrNull(1)?.let { ContactAccessRepository.normalizePhone(it) }.orEmpty()
                if (number.isEmpty()) return Result.Handled(whitelistUsage())
                contactAccess.remove(number)
                Result.Handled("🗑️ $number dihapus dari whitelist.")
            }

            "list" -> {
                val list = contactAccess.whitelist()
                if (list.isEmpty()) {
                    Result.Handled("Whitelist kosong. Tambahkan dengan /whitelist add <nomor> [label]")
                } else {
                    Result.Handled(
                        "🔐 Whitelist (${list.size}):\n" +
                            list.joinToString("\n") { "• ${it.contactId}${it.label?.let { l -> " — $l" } ?: ""}" }
                    )
                }
            }

            else -> Result.Handled(whitelistUsage())
        }
    }

    private suspend fun handleBlacklist(args: List<String>, senderContact: String): Result {
        if (args.isEmpty()) {
            return Result.Handled("Format: /blacklist add|del <nomor> [label]")
        }
        return when (args[0].lowercase()) {
            "add" -> {
                val number = args.getOrNull(1)?.let { ContactAccessRepository.normalizePhone(it) }.orEmpty()
                if (number.length < 6) return Result.Handled("Format: /blacklist add <nomor>")
                val label = args.drop(2).joinToString(" ").ifBlank { null }
                contactAccess.block(number, label)
                Result.Handled("⛔ $number diblokir dari agent.")
            }

            "del", "remove", "hapus" -> {
                val number = args.getOrNull(1)?.let { ContactAccessRepository.normalizePhone(it) }.orEmpty()
                if (number.isEmpty()) return Result.Handled("Format: /blacklist del <nomor>")
                contactAccess.remove(number)
                Result.Handled("$number dihapus dari blacklist.")
            }

            "list" -> {
                val list = contactAccess.blacklist()
                if (list.isEmpty()) {
                    Result.Handled("Blacklist kosong.")
                } else {
                    Result.Handled(
                        "⛔ Blacklist (${list.size}):\n" +
                            list.joinToString("\n") { "• ${it.contactId}${it.label?.let { l -> " — $l" } ?: ""}" }
                    )
                }
            }

            else -> Result.Handled("Format: /blacklist add|del <nomor> [label]")
        }
    }

    private suspend fun handleApproval(id: String?, conversationId: String, approved: Boolean): Result {
        if (id.isNullOrBlank()) {
            return Result.Handled("Format: ${if (approved) "/approve" else "/reject"} <id-permintaan>")
        }
        val request = approvals.resolve(id, approved)
            ?: return Result.Handled(
                "Permintaan \"$id\" tidak ditemukan, sudah diputuskan, atau kedaluwarsa."
            )
        if (!approved) {
            return Result.Handled("❌ Permintaan ${request.toolName} ($id) ditolak. Tidak ada yang dijalankan.")
        }
        // Approved: execute the stored destructive call now.
        val output = try {
            executeApprovedTool(request.toolName, request.arguments, request.conversationId)
        } catch (e: Exception) {
            "⚠️ Eksekusi ${request.toolName} gagal: ${e.message}"
        }
        return Result.Handled("✅ ${request.toolName} disetujui & dijalankan:\n$output")
    }

    private suspend fun handleCompact(sessionId: String?, conversationId: String): Result {
        if (sessionId == null) {
            return Result.Handled("Belum ada percakapan aktif untuk diringkas.")
        }
        val providerPair = resolveProvider()
        // An explicit /compact always runs (force = true); the CompactManager keeps the
        // guard that a conversation too short to summarize is left alone.
        val result = compact.compactIfNeeded(
            sessionId = sessionId,
            conversationId = conversationId,
            maxMessages = maxContextMessages(),
            force = true,
            provider = providerPair?.first,
            modelId = providerPair?.second
        )
        return if (result.compacted) {
            Result.Handled(
                "🗜️ ${result.summarizedTurns} pesan lama diringkas menjadi memori episodik. " +
                    "Konteks percakapan kini lebih ringan."
            )
        } else {
            Result.Handled("Percakapan masih pendek, belum perlu diringkas.")
        }
    }

    private suspend fun handleLearning(args: List<String>): Result {
        val candidates = memory.learningCandidates()
        if (args.isEmpty() || args[0].equals("list", true)) {
            if (candidates.isEmpty()) {
                return Result.Handled("Tidak ada kandidat pembelajaran menunggu review.")
            }
            return Result.Handled(
                "💡 Kandidat pembelajaran (${candidates.size}):\n" +
                    candidates.joinToString("\n") { "• ${it.id}: ${it.content.take(140)}" } +
                    "\n\nSetujui dengan /learning approve <id>, tolak dengan /learning reject <id>."
            )
        }
        val id = args.getOrNull(1) ?: return Result.Handled("Format: /learning approve|reject <id>")
        return when (args[0].lowercase()) {
            "approve", "aktifkan" -> {
                memory.promoteLearning(id)
                Result.Handled("✅ Pembelajaran $id kini AKTIF dan dipatuhi agent.")
            }

            "reject", "tolak" -> {
                memory.rejectLearning(id)
                Result.Handled("🗑️ Kandidat $id ditolak.")
            }

            else -> Result.Handled("Format: /learning approve|reject <id>")
        }
    }

    private suspend fun statusText(contactId: String): String {
        val state = agentLoop.state.value
        val whitelistOn = isWhitelistMode()
        val access = contactAccess.getAccess(contactId, whitelistOn)
        val pendingApprovals = approvals.pendingFor(contactId).size
        val memories = memory.countByType(MemoryItemEntity.TYPE_KNOWLEDGE)
        val episodic = memory.countByType(MemoryItemEntity.TYPE_EPISODIC)
        val scheduled = scheduledTaskDao.getAll().count { it.enabled }
        return buildString {
            appendLine("📊 Status Agent")
            appendLine("• State: $state")
            appendLine("• Akses kamu: ${if (access.decision == ContactAccessRepository.Decision.ALLOW) "DIIZINKAN" else "DIBLOKIR"}")
            appendLine("• Mode whitelist: ${if (whitelistOn) "AKTIF" else "nonaktif"}")
            appendLine("• Approval pending: $pendingApprovals")
            appendLine("• Memori: $memories fakta, $episodic episodik")
            appendLine("• Tugas terjadwal aktif: $scheduled")
        }.trimEnd()
    }

    private fun whitelistUsage(): String =
        "Format:\n/whitelist on|off\n/whitelist add <nomor> [label]\n/whitelist del <nomor>\n/whitelist list"

    private fun helpText(): String = """
        🤖 Perintah yang tersedia:
        /help — tampilkan bantuan ini
        /status — status agent & akses kamu
        /whitelist on|off — mode hanya-nomor-tertentu
        /whitelist add|del <nomor> [label]
        /blacklist add|del <nomor>
        /compact — ringkas riwayat percakapan ini
        /terminal <perintah> — jalankan perintah shell di perangkat
        /browser <url> — buka halaman di browser otomatis agent
        /remember <teks> — simpan fakta ke memori
        /learning — review kandidat pembelajaran
        /approve <id> — setujui tool destruktif
        /reject <id> — tolak tool destruktif
    """.trimIndent()
}
