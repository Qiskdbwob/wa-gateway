package com.example.agent.scheduler

import com.example.agent.storage.dao.ScheduledTaskDao
import com.example.agent.storage.entity.ScheduledTaskEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone
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
 * Two schedule forms are supported:
 *
 *   - `interval:SECONDS` — every N seconds counted from the previous run. Good for "cek harga
 *     tiap 2 jam"; a run that is an hour late shifts every later run by that hour.
 *   - `daily:HH:MM[,HH:MM...]` — fixed times of day in the device time zone. This is what
 *     "kirim berita tiap jam 6 pagi dan 8 malam" actually means: the run lands at 06:00 and
 *     20:00, not at 21:15 + 6 h. [nextRunAt] always returns a time strictly in the future, so a
 *     slot missed while the phone was off fires once when the device is back (the following
 *     slots stay on the clock) instead of firing repeatedly.
 *
 * The engine itself has no Android dependency and is fully testable with an in-memory
 * DAO fake and a virtual clock/time zone.
 */
class SchedulerEngine(
    private val dao: ScheduledTaskDao,
    private val runTask: suspend (task: ScheduledTaskEntity) -> String,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
) {
    @Volatile
    private var running = false

    /** Creates a scheduled task, scheduling its first run at the schedule's first future slot. */
    suspend fun create(
        name: String,
        schedule: String,
        prompt: String,
        conversationId: String,
        agentId: String = "default-agent"
    ): ScheduledTaskEntity {
        val now = System.currentTimeMillis()
        val firstRunAt = nextRunAt(schedule, now)
            ?: throw IllegalArgumentException(String.format(Locale.US, INVALID_SCHEDULE_HELP, schedule))
        val task = ScheduledTaskEntity(
            id = "sched-" + UUID.randomUUID().toString().take(8),
            name = name,
            schedule = normalize(schedule) ?: schedule.trim(),
            prompt = prompt,
            conversationId = conversationId,
            agentId = agentId,
            enabled = true,
            createdAt = now,
            nextRunAt = firstRunAt
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
                nextRunAt = if (enabled) nextRunAt(task.schedule, now) else null
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
        // Reserve the next slot first so a slow execution cannot cause double-fires.
        val next = nextRunAt(task.schedule, now) ?: now + FALLBACK_INTERVAL_SECONDS * 1000L
        dao.upsert(task.copy(nextRunAt = next))
        try {
            val result = runTask(task)
            dao.recordRun(
                id = task.id,
                runAt = now,
                nextRunAt = next,
                status = "COMPLETED",
                result = result.take(400),
                error = null
            )
        } catch (e: Exception) {
            dao.recordRun(
                id = task.id,
                runAt = now,
                nextRunAt = next,
                status = "FAILED",
                result = null,
                error = (e.message ?: e.javaClass.simpleName).take(400)
            )
        }
    }

    companion object {
        /** Message shown to the user/model when a schedule cannot be parsed ("%s" = input). */
        const val INVALID_SCHEDULE_HELP =
            "Format jadwal tidak valid: \"%s\". Pakai \"daily:HH:MM\" (mis. daily:06:00,20:00 — " +
                "jam pasti waktu perangkat) atau \"interval:<detik>\" (mis. interval:3600 = tiap 1 jam)."

        /** Only used when a stored schedule is unreadable; a valid task never needs it. */
        private const val FALLBACK_INTERVAL_SECONDS = 60L

        /** How far ahead a daily slot is looked up (a week covers every weekly combination). */
        private const val MAX_DAYS_AHEAD = 8

        /** Parses "interval:SECONDS" with bounds; null when malformed. */
        fun parseIntervalSeconds(schedule: String): Long? {
            val raw = schedule.trim()
            if (!raw.startsWith(ScheduledTaskEntity.SCHEDULE_PREFIX)) return null
            val body = raw.removePrefix(ScheduledTaskEntity.SCHEDULE_PREFIX).trim()
            val seconds = body.toLongOrNull() ?: return null
            if (seconds < ScheduledTaskEntity.MIN_INTERVAL_SECONDS) return null
            return seconds.coerceAtMost(ScheduledTaskEntity.MAX_INTERVAL_SECONDS)
        }

        /**
         * Parses "daily:HH:MM[,HH:MM...]" into minutes-of-day, sorted and de-duplicated.
         * Null when the expression is not a valid daily schedule ("daily:25:00", "daily:6",
         * "setiap hari" — the last one has no prefix at all and is rejected).
         */
        fun parseDailyMinutes(schedule: String): List<Int>? {
            val raw = schedule.trim()
            val body = when {
                raw.startsWith(ScheduledTaskEntity.DAILY_PREFIX) ->
                    raw.removePrefix(ScheduledTaskEntity.DAILY_PREFIX).trim()
                // Forgiving on purpose: "06:00,20:00" names clock times just as clearly as
                // "daily:06:00,20:00", and a weaker model forgetting the prefix should not end up
                // with a task that never runs. Anything without a colon ("3600", "tiap hari")
                // is still rejected.
                raw.contains(':') -> raw
                else -> return null
            }
            if (body.isEmpty()) return null
            val parts = body.split(',').map { it.trim() }
            if (parts.size > ScheduledTaskEntity.MAX_DAILY_TIMES) return null
            val minutes = LinkedHashSet<Int>()
            for (part in parts) {
                val pieces = part.split(':')
                if (pieces.size != 2) return null
                val hour = pieces[0].trim().toIntOrNull() ?: return null
                val minute = pieces[1].trim().toIntOrNull() ?: return null
                if (hour !in 0..23 || minute !in 0..59) return null
                minutes.add(hour * 60 + minute)
            }
            if (minutes.isEmpty()) return null
            return minutes.sorted()
        }

        /** Canonical form of a valid schedule ("daily:6:0" -> "daily:06:00"); null when invalid. */
        fun normalize(schedule: String): String? {
            parseIntervalSeconds(schedule)?.let { seconds ->
                return ScheduledTaskEntity.SCHEDULE_PREFIX + seconds
            }
            val minutes = parseDailyMinutes(schedule) ?: return null
            return ScheduledTaskEntity.DAILY_PREFIX +
                minutes.joinToString(",") { formatTimeOfDay(it) }
        }

        /**
         * Next fire time strictly after [from], or null when [schedule] cannot be parsed.
         *
         * Intervals are relative to [from] (which is the previous slot during a tick, so a late
         * run does not drift the cadence), daily times are resolved against the wall clock in
         * [zone] so they keep landing on the same minutes no matter when the tick happens.
         */
        fun nextRunAt(
            schedule: String,
            from: Long,
            zone: TimeZone = TimeZone.getDefault()
        ): Long? {
            parseIntervalSeconds(schedule)?.let { seconds -> return from + seconds * 1000L }
            val minutes = parseDailyMinutes(schedule) ?: return null
            val calendar = Calendar.getInstance(zone)
            for (dayOffset in 0..MAX_DAYS_AHEAD) {
                for (minuteOfDay in minutes) {
                    calendar.timeInMillis = from
                    calendar.add(Calendar.DAY_OF_YEAR, dayOffset)
                    calendar.set(Calendar.HOUR_OF_DAY, minuteOfDay / 60)
                    calendar.set(Calendar.MINUTE, minuteOfDay % 60)
                    calendar.set(Calendar.SECOND, 0)
                    calendar.set(Calendar.MILLISECOND, 0)
                    if (calendar.timeInMillis > from) return calendar.timeInMillis
                }
            }
            return null
        }

        /** Human label for the UI and the tool output, e.g. "setiap hari pukul 06:00 & 20:00". */
        fun describeSchedule(schedule: String): String {
            parseIntervalSeconds(schedule)?.let { seconds -> return "setiap ${describeInterval(seconds)}" }
            val minutes = parseDailyMinutes(schedule)
                ?: return "jadwal tidak dikenal (\"$schedule\")"
            return "setiap hari pukul " +
                minutes.joinToString(" & ") { formatTimeOfDay(it) } + " (waktu perangkat)"
        }

        /** "06:00" — always ASCII digits, so the label reads the same in every locale. */
        fun formatTimeOfDay(minuteOfDay: Int): String =
            String.format(Locale.US, "%02d:%02d", minuteOfDay / 60, minuteOfDay % 60)

        private fun describeInterval(seconds: Long): String = when {
            seconds % 86_400L == 0L -> "${seconds / 86_400L} hari"
            seconds % 3_600L == 0L -> "${seconds / 3_600L} jam"
            seconds % 60L == 0L -> "${seconds / 60L} menit"
            else -> "$seconds detik"
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
