package com.example.agent.memory

import com.example.agent.storage.entity.MemoryItemEntity
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlin.math.abs

/**
 * Priority 2 — context manager (Phase 13 of DOC/context-2.md, focused scope).
 *
 * Builds the effective system prompt:
 *
 *   persona system prompt
 *   + current device date & time (see [timeContext])
 *   + long-term memories relevant to the current message (RAG)
 *   + active learning rules (always, they are few)
 *
 * and keeps each section short so the model's context budget is spent on the actual
 * conversation. Compaction (auto /compact) lives in the CompactManager.
 */
object ContextManager {

    private const val MAX_MEMORY_CHARS = 1_200
    private const val MAX_LEARNING_CHARS = 1_200

    /**
     * Wall-clock block prepended to every turn's system prompt: the agent then knows "now" (device
     * date, hour and zone) without a tool round-trip, so "hari ini hari apa?" and schedule maths use
     * the right day. Pure function on purpose — [now], [zone] and [locale] are injectable so unit
     * tests can pin the exact output.
     */
    fun timeContext(
        now: Long = System.currentTimeMillis(),
        zone: TimeZone = TimeZone.getDefault(),
        locale: Locale = Locale.getDefault()
    ): String {
        val human = SimpleDateFormat("EEEE, d MMMM yyyy, HH:mm", locale)
            .apply { timeZone = zone }
            .format(Date(now))
        return buildString {
            append("## Waktu sekarang\n")
            append("Waktu lokal perangkat: ").append(human)
                .append(" (UTC").append(utcOffsetLabel(now, zone))
                .append(", zona ").append(zone.id).append(").\n")
            append("Pakai ini untuk pertanyaan hari/tanggal/jam dan untuk menghitung jadwal atau ")
            append("pengingat. Tool `current_time` tetap ada bila butuh presisi detik atau zona lain.")
        }
    }

    /**
     * "+07:00" / "-05:30" / "+00:00". Computed from the zone itself (so DST is honoured) instead
     * of a date pattern, which keeps the label identical on every Android version.
     */
    private fun utcOffsetLabel(now: Long, zone: TimeZone): String {
        val totalMinutes = zone.getOffset(now) / 60_000
        val sign = if (totalMinutes < 0) "-" else "+"
        val absMinutes = abs(totalMinutes)
        return String.format(Locale.US, "%s%02d:%02d", sign, absMinutes / 60, absMinutes % 60)
    }

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
