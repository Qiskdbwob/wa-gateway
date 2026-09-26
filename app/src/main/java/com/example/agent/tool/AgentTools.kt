package com.example.agent.tool

import com.example.agent.memory.MemoryRepository
import com.example.agent.model.Tool
import com.example.agent.model.ToolPermission
import com.example.agent.model.ToolResult
import com.example.agent.storage.entity.MemoryItemEntity
import com.example.agent.subagent.SubAgentSpec
import kotlinx.coroutines.currentCoroutineContext
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/**
 * Coroutine-context carrier for the conversation a tool call belongs to.
 *
 * The `Tool.execute(input)` boundary only carries argument JSON, so the conversation id
 * travels out-of-band. Unlike a ThreadLocal this survives coroutine thread hops, because
 * the bridge wraps every agent turn in `withContext(ToolConversation(...))`.
 */
class ToolConversation(
    val conversationId: String
) : AbstractCoroutineContextElement(ToolConversation) {
    companion object Key : CoroutineContext.Key<ToolConversation>
}

/** Reads the current turn's conversation id; empty when unavailable. */
suspend fun currentToolConversation(): String =
    currentCoroutineContext()[ToolConversation]?.conversationId.orEmpty()

// =============================================================================
// Priority 2 — memory tools
// =============================================================================

/** The model calls this when the user says "remember that..." or asks to store knowledge. */
class RememberTool(private val memory: MemoryRepository) : Tool {

    override val id: String = "builtin.remember"
    override val name: String = "remember"
    override val description: String =
        "Menyimpan informasi jangka panjang ke memori agent. Gunakan saat pengguna berkata " +
            "\"ingat\", \"catat\", atau saat ada fakta penting yang harus diingat di percakapan berikutnya."
    override val inputSchema: String = """
        {
          "type": "object",
          "properties": {
            "content": {
              "type": "string",
              "description": "Fakta/informasi yang disimpan, ditulis sebagai kalimat mandiri."
            },
            "type": {
              "type": "string",
              "enum": ["KNOWLEDGE", "EPISODIC"],
              "description": "Jenis memori. Default KNOWLEDGE (fakta persisten)."
            }
          },
          "required": ["content"]
        }
    """.trimIndent()

    override val permission: ToolPermission = ToolPermission.SAFE

    override suspend fun execute(input: String): ToolResult {
        val content = JsonArgs.string(input, "content")?.trim().orEmpty()
        if (content.isEmpty()) {
            return ToolResult(success = false, output = "", error = "Argumen 'content' wajib diisi.")
        }
        val type = when (JsonArgs.string(input, "type")?.uppercase()) {
            "EPISODIC" -> MemoryItemEntity.TYPE_EPISODIC
            else -> MemoryItemEntity.TYPE_KNOWLEDGE
        }
        val item = memory.remember(content, type)
        return ToolResult(
            success = true,
            output = "Tersimpan ke memori jangka panjang (${item.type}): ${item.content}",
            metadata = mapOf("memoryId" to item.id)
        )
    }
}

/** RAG recall over long-term memory. */
class RecallMemoryTool(private val memory: MemoryRepository) : Tool {

    override val id: String = "builtin.recall_memory"
    override val name: String = "recall_memory"
    override val description: String =
        "Mencari memori jangka panjang yang relevan dengan sebuah pertanyaan/kata kunci. " +
            "Gunakan bila perlu mengingat hal yang pernah disimpan atau dibahas sebelumnya."
    override val inputSchema: String = """
        {
          "type": "object",
          "properties": {
            "query": { "type": "string", "description": "Pertanyaan atau kata kunci pencarian memori." },
            "limit": { "type": "integer", "description": "Jumlah memori maksimum yang dikembalikan, default 5." }
          },
          "required": ["query"]
        }
    """.trimIndent()

    override val permission: ToolPermission = ToolPermission.SAFE

    override suspend fun execute(input: String): ToolResult {
        val query = JsonArgs.string(input, "query")?.trim().orEmpty()
        if (query.isEmpty()) {
            return ToolResult(success = false, output = "", error = "Argumen 'query' wajib diisi.")
        }
        val limit = (JsonArgs.int(input, "limit") ?: 5).coerceIn(1, 15)
        val found = memory.recall(query, limit)
        if (found.isEmpty()) {
            return ToolResult(success = true, output = "Tidak ada memori yang cocok untuk \"$query\".")
        }
        val body = found.joinToString("\n\n") { "- [${it.type}] ${it.content}" }
        return ToolResult(success = true, output = "Memori yang relevan:\n$body")
    }
}

// =============================================================================
// Priority 6 — subagent tools
// =============================================================================

/**
 * Delegate a task to a background subagent. The tool returns IMMEDIATELY with the task
 * id; the subagent keeps running while the main agent continues answering the user.
 */
class DelegateTaskTool(
    private val launcher: suspend (
        spec: SubAgentSpec,
        conversationId: String
    ) -> String
) : Tool {

    override val id: String = "builtin.delegate_task"
    override val name: String = "delegate_task"
    override val description: String =
        "Mendelegasikan tugas ke sub-agent yang berjalan di latar belakang. Agent utama " +
            "TIDAK menunggu hasilnya: segera konfirmasi ke pengguna bahwa sub-agent sedang " +
            "bekerja, hasilnya akan dikirim otomatis ke chat ketika selesai."
    override val inputSchema: String = """
        {
          "type": "object",
          "properties": {
            "name": { "type": "string", "description": "Nama sub-agent, misal 'Peneliti Web'." },
            "task": { "type": "string", "description": "Deskripsi tugas yang dikerjakan sub-agent, lengkap dan mandiri." },
            "persona": { "type": "string", "description": "Kepribadian/system prompt sub-agent. Opsional." },
            "model_id": { "type": "string", "description": "Model yang dipakai sub-agent. Kosong = model utama." },
            "tools_enabled": { "type": "boolean", "description": "true bila sub-agent boleh memakai tool. Default false." }
          },
          "required": ["task"]
        }
    """.trimIndent()

    override val permission: ToolPermission = ToolPermission.SAFE

    override suspend fun execute(input: String): ToolResult {
        val task = JsonArgs.string(input, "task")?.trim().orEmpty()
        if (task.isEmpty()) {
            return ToolResult(success = false, output = "", error = "Argumen 'task' wajib diisi.")
        }
        val spec = SubAgentSpec(
            name = JsonArgs.string(input, "name")?.takeIf { it.isNotBlank() } ?: "SubAgent",
            task = task,
            persona = JsonArgs.string(input, "persona").orEmpty(),
            modelId = JsonArgs.string(input, "model_id").orEmpty(),
            toolsEnabled = JsonArgs.boolean(input, "tools_enabled") ?: false
        )
        val conversationId = currentToolConversation()
        val taskId = launcher(spec, conversationId)
        return ToolResult(
            success = true,
            output = "Sub-agent '${spec.name}' mulai bekerja di latar belakang (task id: $taskId). " +
                "Beri tahu pengguna bahwa hasil akan dikirim ke chat ini ketika selesai.",
            metadata = mapOf("taskId" to taskId)
        )
    }
}

// =============================================================================
// Priority 6 — self-reflection tool
// =============================================================================

/**
 * The model calls `reflect` at natural stopping points: it converts an observation into
 * a candidate learning. The learning pipeline (candidate → active) does the promotion.
 */
class ReflectTool(private val memory: MemoryRepository) : Tool {

    override val id: String = "builtin.reflect"
    override val name: String = "reflect"
    override val description: String =
        "Merefleksikan pengalaman/kesalahan pada percakapan ini menjadi pelajaran singkat " +
            "yang bisa dipakai lagi. Gunakan setelah berhasil menyelesaikan sesuatu yang sulit " +
            "atau setelah melakukan kesalahan yang perlu dihindari di masa depan."
    override val inputSchema: String = """
        {
          "type": "object",
          "properties": {
            "observation": { "type": "string", "description": "Apa yang terjadi (peristiwa/kesalahan)." },
            "lesson": { "type": "string", "description": "Pelajaran/aturan yang ditarik, satu-dua kalimat, berbunyi seperti instruksi ke diri sendiri." }
          },
          "required": ["observation", "lesson"]
        }
    """.trimIndent()

    override val permission: ToolPermission = ToolPermission.SAFE

    override suspend fun execute(input: String): ToolResult {
        val observation = JsonArgs.string(input, "observation")?.trim().orEmpty()
        val lesson = JsonArgs.string(input, "lesson")?.trim().orEmpty()
        if (observation.isEmpty() || lesson.isEmpty()) {
            return ToolResult(success = false, output = "", error = "Argumen 'observation' dan 'lesson' wajib diisi.")
        }
        val candidate = memory.recordLearning("$observation → Pelajaran: $lesson", conversationId = null)
        return ToolResult(
            success = true,
            output = "Pelajaran dicatat sebagai kandidat (${candidate.id}). " +
                "Pengguna bisa mengaktifkannya lewat menu Memori → Learning.",
            metadata = mapOf("learningId" to candidate.id)
        )
    }
}

// =============================================================================
// Priority 4 — scheduler tool
// =============================================================================

/**
 * Creates a scheduled (cron-like) task that re-prompts the agent periodically and sends
 * the answer to a chat. schedule format: "interval:SECONDS".
 */
class ScheduleTaskTool(
    private val creator: suspend (name: String, schedule: String, prompt: String, conversationId: String) -> String
) : Tool {

    override val id: String = "builtin.schedule_task"
    override val name: String = "schedule_task"
    override val description: String =
        "Membuat tugas terjadwal (berulang) yang menjalankan prompt agent secara periodik " +
            "dan mengirim hasilnya ke chat ini. Format jadwal: \"interval:<detik>\", " +
            "misal \"interval:3600\" = tiap 1 jam, \"interval:86400\" = tiap hari."
    override val inputSchema: String = """
        {
          "type": "object",
          "properties": {
            "name": { "type": "string", "description": "Nama tugas, misal 'Laporan cuaca pagi'." },
            "schedule": { "type": "string", "description": "\"interval:<detik>\" — jeda antar eksekusi, minimum 60 detik." },
            "prompt": { "type": "string", "description": "Prompt yang dijalankan agent pada setiap eksekusi." }
          },
          "required": ["name", "schedule", "prompt"]
        }
    """.trimIndent()

    override val permission: ToolPermission = ToolPermission.SAFE

    override suspend fun execute(input: String): ToolResult {
        val name = JsonArgs.string(input, "name")?.trim().orEmpty()
        val schedule = JsonArgs.string(input, "schedule")?.trim().orEmpty()
        val prompt = JsonArgs.string(input, "prompt")?.trim().orEmpty()
        if (name.isEmpty() || schedule.isEmpty() || prompt.isEmpty()) {
            return ToolResult(success = false, output = "", error = "Argumen 'name', 'schedule', dan 'prompt' wajib diisi.")
        }

        val seconds = schedule.removePrefix("interval:").trim().toLongOrNull()
            ?: return ToolResult(
                success = false,
                output = "",
                error = "Format jadwal tidak valid. Gunakan \"interval:<detik>\", contoh interval:3600."
            )
        if (seconds < com.example.agent.storage.entity.ScheduledTaskEntity.MIN_INTERVAL_SECONDS) {
            return ToolResult(
                success = false,
                output = "",
                error = "Interval minimum ${com.example.agent.storage.entity.ScheduledTaskEntity.MIN_INTERVAL_SECONDS} detik."
            )
        }

        val conversationId = currentToolConversation()
        val id = creator(name, "interval:$seconds", prompt, conversationId)
        return ToolResult(
            success = true,
            output = "Tugas terjadwal '$name' dibuat (setiap $seconds detik). Kelola di tab Tugas → Scheduled.",
            metadata = mapOf("scheduledTaskId" to id)
        )
    }
}

// =============================================================================
// Priority 6 — council mode
// =============================================================================

/**
 * Council: the main agent convenes 2–3 subagent personas, collects their positions and
 * synthesises. The implementation is synchronous (the main agent explicitly asked for a
 * debate, so it *does* wait) but strictly bounded: 2 debaters max, one round each,
 * short answers — so latency stays acceptable on WhatsApp.
 */
class CouncilTool(
    private val runCouncil: suspend (topic: String, conversationId: String) -> String
) : Tool {

    override val id: String = "builtin.council"
    override val name: String = "council"
    override val description: String =
        "Menyelenggarakan diskusi 'council': 2 sub-agent dengan sudut pandang berbeda " +
            "(pendukung vs kritikus) memberikan argumen tentang sebuah topik, lalu hasil " +
            "sinerginya dikembalikan. Gunakan untuk keputusan penting yang butuh lebih dari satu perspektif."
    override val inputSchema: String = """
        {
          "type": "object",
          "properties": {
            "topic": { "type": "string", "description": "Topik/pertanyaan yang diperdebatkan." }
          },
          "required": ["topic"]
        }
    """.trimIndent()

    override val permission: ToolPermission = ToolPermission.SAFE

    override suspend fun execute(input: String): ToolResult {
        val topic = JsonArgs.string(input, "topic")?.trim().orEmpty()
        if (topic.isEmpty()) {
            return ToolResult(success = false, output = "", error = "Argumen 'topic' wajib diisi.")
        }
        val conversationId = currentToolConversation()
        val synthesis = runCouncil(topic, conversationId)
        return ToolResult(success = true, output = synthesis)
    }
}
