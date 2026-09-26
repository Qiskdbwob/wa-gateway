package com.example.agent.scheduler

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkRequest
import androidx.work.WorkerParameters
import com.example.agent.bridge.WhatsAppAgentBridge
import java.util.concurrent.TimeUnit

/**
 * Keeps scheduled tasks alive when the app process is not.
 *
 * The in-app ticker still runs every 60 seconds while the app is up (precise, cheap). WorkManager
 * adds the part a plain coroutine cannot do: a system-scheduled wake-up roughly every 15 minutes
 * (its documented minimum), so a task whose time passed while the process was killed still runs
 * shortly after the next wake-up instead of waiting for the user to open the app.
 *
 * The worker does exactly one thing — ask the bridge to run due tasks — and defers all decisions
 * (which task is due, how to report the result) to [SchedulerEngine], so there is a single
 * implementation of the scheduling rules.
 */
class SchedulerWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        return try {
            WhatsAppAgentBridge.getInstance(applicationContext).runSchedulerTick()
            Result.success()
        } catch (_: Exception) {
            // Retry with the backoff below; a failure here must never crash the app.
            Result.retry()
        }
    }

    companion object {
        private const val WORK_NAME = "wa-gateway-scheduler-tick"

        /** 15 minutes — WorkManager's minimum periodic interval. */
        private const val INTERVAL_MINUTES = 15L

        /**
         * Registers the periodic wake-up once per install. `KEEP` means re-scheduling on every app
         * start does not reset the existing schedule (and does not stack duplicates).
         */
        fun enqueue(context: Context) {
            try {
                val request = PeriodicWorkRequestBuilder<SchedulerWorker>(INTERVAL_MINUTES, TimeUnit.MINUTES)
                    .setBackoffCriteria(
                        BackoffPolicy.LINEAR,
                        WorkRequest.MIN_BACKOFF_MILLIS,
                        TimeUnit.MILLISECONDS
                    )
                    .build()
                WorkManager.getInstance(context.applicationContext).enqueueUniquePeriodicWork(
                    WORK_NAME,
                    ExistingPeriodicWorkPolicy.KEEP,
                    request
                )
            } catch (_: Exception) {
                // WorkManager unavailable (e.g. during unit tests): the in-app ticker still works.
            }
        }
    }
}
