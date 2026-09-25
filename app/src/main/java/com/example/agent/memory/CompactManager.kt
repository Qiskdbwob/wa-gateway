package com.example.agent.memory

import com.example.agent.model.AgentMessage
import com.example.agent.model.AgentRole
import com.example.agent.provider.ModelProvider
import com.example.agent.storage.AgentSessionRepository
import com.example.agent.storage.entity.MemoryItemEntity
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Priority 2 — auto/manual compact (also requested in DOC/list-fitur.md).
 *
 * When a session's message count crosses the configured threshold, the older turns
 * (everything except the most recent [keepRecent] messages) are summarized by the model
 * itself and the summary is stored as episodic memory + attached to the session as a
 * system-role message. The original turns are then pruned, so the context stays small
 * while the information survives.
 *
 * The `/compact` chat command triggers the same flow manually.
 */
class CompactManager(
    private val sessionRepository: AgentSessionRepository,
    private val memory: MemoryRepository
) {
    data class CompactResult(
        val compacted: Boolean,
        val summarizedTurns: Int,
        val summary: String?
    )

    /**
     * Summarizes the old turns of [sessionId] if it has more than [maxMessages]
     * messages (or always, when [force] = manual /compact).
     */
    suspend fun compactIfNeeded(
        sessionId: String,
        conversationId: String,
        maxMessages: Int,
        keepRecent: Int = 8,
        force: Boolean = false,
        provider: ModelProvider? = null,
        modelId: String? = null
    ): CompactResult {
        val history = sessionRepository.getMessages(sessionId, limit = 500)
        if (history.isEmpty()) return CompactResult(compacted = false, summarizedTurns = 0, summary = null)
        if (!force && history.size <= maxMessages) {
            return CompactResult(compacted = false, summarizedTurns = 0, summary = null)
        }

        val cutPoint = (history.size - keepRecent).coerceAtLeast(0)
        if (cutPoint < 4) return CompactResult(compacted = false, summarizedTurns = 0, summary = null)
        val toSummarize = history.subList(0, cutPoint)

        val summaryText = if (provider != null) {
            summarizeWithModel(toSummarize, provider, modelId)
        } else {
            extractiveSummary(toSummarize)
        }

        // Persist summary as episodic memory bound to this conversation.
        memory.recordEpisodic(
            summary = summaryText,
            conversationId = conversationId,
            source = MemoryItemEntity.SOURCE_AUTO_COMPACT
        )

        // Attach the summary to the session so future context building sees it even
        // after the raw turns are gone.
        val existingSummary = history.firstOrNull {
            it.role == AgentRole.SYSTEM && it.content.startsWith(SUMMARY_PREFIX)
        }
        val mergedSummary = if (existingSummary != null) {
            existingSummary.content.substringAfter(SUMMARY_PREFIX).trim() + "\n" + summaryText
        } else {
            summaryText
        }
        sessionRepository.saveMessage(
            AgentMessage(
                sessionId = sessionId,
                role = AgentRole.SYSTEM,
                content = SUMMARY_PREFIX + mergedSummary,
                timestamp = System.currentTimeMillis()
            )
        )

        // Drop the summarized raw turns (and any previous summary message) from history.
        sessionRepository.deleteMessagesUpTo(sessionId, toSummarize.last().timestamp)

        return CompactResult(compacted = true, summarizedTurns = cutPoint, summary = summaryText)
    }

    /** Model-backed summary; falls back to extractive summary on any failure. */
    private suspend fun summarizeWithModel(
        turns: List<AgentMessage>,
        provider: ModelProvider,
        modelId: String?
    ): String {
        val transcript = turns.joinToString("\n") { turn ->
            val who = when (turn.role) {
                AgentRole.USER -> "User"
                AgentRole.ASSISTANT -> "Agent"
                else -> "Sistem"
            }
            "$who: ${turn.content.take(400)}"
        }
        val request = com.example.agent.model.ModelRequest(
            messages = listOf(
                AgentMessage(
                    sessionId = "compact",
                    role = AgentRole.USER,
                    content = "Ringkas percakapan berikut menjadi poin-poin fakta penting " +
                        "(maksimal 10 poin, setiap poin satu kalimat, bahasa yang sama dengan percakapan):\n\n$transcript"
                )
            ),
            systemPrompt = "Kamu adalah asisten yang meringkas percakapan secara akurat dan padat.",
            modelId = modelId
        )
        return try {
            provider.generate(request).getOrNull()?.content?.takeIf { it.isNotBlank() }
                ?: extractiveSummary(turns)
        } catch (_: Exception) {
            extractiveSummary(turns)
        }
    }

    /** No-model fallback: first user message + last few turns, labelled. */
    private fun extractiveSummary(turns: List<AgentMessage>): String {
        val fmt = SimpleDateFormat("dd MMM", Locale.getDefault())
        val firstUser = turns.firstOrNull { it.role == AgentRole.USER }?.content?.take(120)
        val highlights = turns.takeLast(6)
            .filter { it.role == AgentRole.USER || it.role == AgentRole.ASSISTANT }
            .joinToString("\n") { turn ->
                val who = if (turn.role == AgentRole.USER) "User" else "Agent"
                "- $who: ${turn.content.take(100)}"
            }
        return buildString {
            append("Ringkasan percakapan (")
            append(fmt.format(Date(turns.first().timestamp)))
            append("): ")
            firstUser?.let { append("dimulai dengan \"").append(it).append("\". ") }
            append("\n")
            append(highlights)
        }
    }

    companion object {
        const val SUMMARY_PREFIX = "Ringkasan sebelumnya (compact): "
    }
}
