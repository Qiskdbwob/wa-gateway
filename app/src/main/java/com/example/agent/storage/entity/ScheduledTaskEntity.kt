package com.example.agent.storage.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Priority 4 — Scheduled task / cron (Phase 21 of DOC/context-2.md).
 *
 * A scheduled task re-prompts the agent on a fixed schedule and sends the answer to a
 * WhatsApp chat. [schedule] is a compact expression with two forms:
 *
 *   - "interval:SECONDS"        — every N seconds counted from the last run (e.g. "interval:3600")
 *   - "daily:HH:MM[,HH:MM...]"  — fixed wall-clock times in the device time zone, which is what
 *     "kirim berita tiap jam 6 pagi dan 8 malam" actually asks for (e.g. "daily:06:00,20:00")
 *
 * The SchedulerEngine ticks every minute, executes due tasks through the AgentLoop and records
 * the outcome in [lastResult] / [lastError].
 */
@Entity(
    tableName = "scheduled_tasks",
    indices = [Index(value = ["enabled"]), Index(value = ["nextRunAt"])]
)
data class ScheduledTaskEntity(
    @PrimaryKey
    val id: String,
    val name: String,
    /** "interval:SECONDS" or "daily:HH:MM[,HH:MM...]" — the cron surface the agent needs. */
    val schedule: String,
    val prompt: String,
    val conversationId: String,
    val agentId: String = "default-agent",
    val enabled: Boolean = true,
    val createdAt: Long = System.currentTimeMillis(),
    val lastRunAt: Long? = null,
    val nextRunAt: Long? = null,
    val lastStatus: String? = null, // COMPLETED / FAILED
    val lastResult: String? = null,
    val lastError: String? = null
) {
    companion object {
        const val SCHEDULE_PREFIX = "interval:"
        const val DAILY_PREFIX = "daily:"
        const val MIN_INTERVAL_SECONDS = 60L
        const val MAX_INTERVAL_SECONDS = 30L * 24 * 60 * 60 // 30 days

        /** Fixed daily times accepted per "daily:" expression (06:00, 12:00, 18:00, 20:00, …). */
        const val MAX_DAILY_TIMES = 6
    }
}
