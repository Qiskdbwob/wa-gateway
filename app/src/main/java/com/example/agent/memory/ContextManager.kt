package com.example.agent.memory

import com.example.agent.storage.entity.MemoryItemEntity

/**
 * Priority 2 — context manager (Phase 13 of DOC/context-2.md, focused scope).
 *
 * Builds the effective system prompt:
 *
 *   persona system prompt
 *   + long-term memories relevant to the current message (RAG)
 *   + active learning rules (always, they are few)
 *
 * and keeps each section short so the model's context budget is spent on the actual
 * conversation. Compaction (auto /compact) lives in the CompactManager.
 */
object ContextManager {

    private const val MAX_MEMORY_CHARS = 1_200
    private const val MAX_LEARNING_CHARS = 1_200

    fun buildSystemPrompt(
        basePrompt: String,
        relevantMemories: List<MemoryItemEntity>,
        activeLearnings: List<MemoryItemEntity>
    ): String {
        val sb = StringBuilder(basePrompt.trim())

        if (activeLearnings.isNotEmpty()) {
            val learnings = joinCapped(
                activeLearnings.map { "- ${it.content}" },
                MAX_LEARNING_CHARS
            )
            sb.append("\n\n## Pelajaran dari pengalaman sebelumnya (patuhi)\n")
                .append(learnings)
        }

        if (relevantMemories.isNotEmpty()) {
            val memories = joinCapped(
                relevantMemories.map { "- [${it.type.lowercase()}] ${it.content}" },
                MAX_MEMORY_CHARS
            )
            sb.append("\n\n## Memori jangka panjang yang relevan dengan pesan ini\n")
                .append(memories)
                .append("\n(Gunakan bila relevan; jangan mengarang isi memori yang tidak ada.)")
        }

        return sb.toString()
    }

    private fun joinCapped(lines: List<String>, maxChars: Int): String {
        val out = StringBuilder()
        for (line in lines) {
            if (out.length + line.length + 1 > maxChars) {
                out.append("- ...(memori lain dipotong)")
                break
            }
            if (out.isNotEmpty()) out.append('\n')
            out.append(line)
        }
        return out.toString()
    }
}
