package com.example.agent.scheduler

import com.example.agent.storage.dao.ScheduledTaskDao
import com.example.agent.storage.entity.ScheduledTaskEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * Priority 4 — scheduler / background cron (Phase 21 of DOC/context-2.md).
 *
 * A periodic ticker (default every 60 s) checks `scheduled_tasks` for enabled rows whose
 * `nextRunAt <= now`, and for each one:
 *
 *   1. computes and stores the next run time first (no double-fire when execution is slow)
 *   2. executes [runTask] — implemented by the bridge: it sends the task prompt through
 *      the AgentLoop and the answer to the target WhatsApp chat
 *   3. records COMPLETED/FAILED with result/error on the row
 *
 * The engine itself has no Android dependency and is fully testable with an in-memory
 * DAO fake and a virtual ticker.
 */
class SchedulerEngine(
    private val dao: ScheduledTaskDao,
    private val runTask: suspend (task: ScheduledTaskEntity) -> String,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
) {
    @Volatile
    private var running = false

    /** Creates a scheduled task, scheduling its first run one interval from now. */
    suspend fun create(
        name: String,
        schedule: String,
        prompt: String,
        conversationId: String,
        agentId: String = "default-agent"
    ): ScheduledTaskEntity {
        val seconds = parseIntervalSeconds(schedule)
            ?: throw IllegalArgumentException("Format jadwal tidak valid: \"$schedule\"")
        val now = System.currentTimeMillis()
        val task = ScheduledTaskEntity(
            id = "sched-" + UUID.randomUUID().toString().take(8),
            name = name,
            schedule = "interval:$seconds",
            prompt = prompt,
            conversationId = conversationId,
            agentId = agentId,
            enabled = true,
            createdAt = now,
            nextRunAt = now + seconds * 1000L
        )
        dao.upsert(task)
        return task
    }

    suspend fun setEnabled(id: String, enabled: Boolean) {
        val task = dao.findById(id) ?: return
        val now = System.currentTimeMillis()
        dao.upsert(
            task.copy(
                enabled = enabled,
                nextRunAt = if (enabled) now + (parseIntervalSeconds(task.schedule) ?: 60L) * 1000L else null
            )
        )
    }

    suspend fun delete(id: String) = dao.deleteById(id)
    suspend fun getAll(): List<ScheduledTaskEntity> = dao.getAll()
    suspend fun runNow(id: String): Boolean {
        val task = dao.findById(id) ?: return false
        executeOne(task)
        return true
    }

    /** One scheduler tick: find due tasks and run them. Called by the periodic worker. */
    suspend fun tick(now: Long = System.currentTimeMillis()) {
        if (running) return // previous tick still working: skip, do not stack runs
        running = true
        try {
            val due = dao.getEnabled().filter { (it.nextRunAt ?: 0L) <= now }
            for (task in due) {
                executeOne(task, now)
            }
        } finally {
            running = false
        }
    }

    private suspend fun executeOne(task: ScheduledTaskEntity, now: Long = System.currentTimeMillis()) {
        val interval = (parseIntervalSeconds(task.schedule) ?: 60L) * 1000L
        // Reserve the next slot first so a slow execution cannot cause double-fires.
        dao.upsert(task.copy(nextRunAt = now + interval))
        try {
            val result = runTask(task)
            dao.recordRun(
                id = task.id,
                runAt = now,
                nextRunAt = now + interval,
                status = "COMPLETED",
                result = result.take(400),
                error = null
            )
        } catch (e: Exception) {
            dao.recordRun(
                id = task.id,
                runAt = now,
                nextRunAt = now + interval,
                status = "FAILED",
                result = null,
                error = (e.message ?: e.javaClass.simpleName).take(400)
            )
        }
    }

    companion object {
        /** Parses "interval:SECONDS" with bounds; null when malformed. */
        fun parseIntervalSeconds(schedule: String): Long? {
            val raw = schedule.trim().removePrefix(ScheduledTaskEntity.SCHEDULE_PREFIX).trim()
            val seconds = raw.toLongOrNull() ?: return null
            if (seconds < ScheduledTaskEntity.MIN_INTERVAL_SECONDS) return null
            return seconds.coerceAtMost(ScheduledTaskEntity.MAX_INTERVAL_SECONDS)
        }

        /** Starts the periodic ticker; returns the Job so the caller owns its lifetime. */
        fun startTicking(
            engine: SchedulerEngine,
            periodMs: Long = 60_000L,
            scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        ): kotlinx.coroutines.Job = scope.launch {
            while (isActive) {
                try {
                    engine.tick()
                } catch (_: Exception) {
                    // A bad tick must never kill the scheduler loop.
                }
                kotlinx.coroutines.delay(periodMs)
            }
        }
    }
}
