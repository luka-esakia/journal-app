package com.journal.app.notification

import android.content.Context
import android.util.Log
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.journal.app.data.local.PreferenceManager
import com.journal.app.data.repository.JournalRepository
import java.util.concurrent.TimeUnit

/**
 * Generates the weekly reflection in the background and tells the user when it is ready.
 *
 * ### Why a worker and not an alarm
 * This is the one AI call in the app that needs the network, takes real time, and nobody is
 * waiting on. `WorkManager` will hold it until there is connectivity and survive a reboot, which
 * an exact alarm firing into airplane mode would not. The prompt schedule keeps using
 * `AlarmManager` for the opposite reason: a prompt is worthless if it arrives an hour late.
 *
 * ### Why it notifies
 * Previously the reflection only existed if the user happened to press the button, and a
 * background one would have appeared silently in a tab they had no reason to open. Finishing
 * quietly is the same as not finishing.
 */
class WeeklyReflectionWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val preferences = PreferenceManager.getInstance(applicationContext)
        if (!preferences.currentAiSettings().isUsable()) {
            // No key, or AI switched off. Not a failure — just nothing to do this week.
            Log.i(TAG, "AI is not configured; skipping the weekly reflection")
            return Result.success()
        }

        val outcome = JournalRepository.getInstance(applicationContext).generateWeeklyReflection()

        return outcome.fold(
            onSuccess = { reflection ->
                NotificationHelper.showReflectionReady(applicationContext, reflection.text)
                Log.i(TAG, "Weekly reflection stored from ${reflection.sourceCount()} entries")
                Result.success()
            },
            onFailure = { error ->
                // An empty week is the expected outcome for anyone who did not write; retrying
                // it every few minutes would burn battery to reach the same conclusion.
                if (error.message == NO_ENTRIES) {
                    Log.i(TAG, "No entries this week; nothing to reflect on")
                    Result.success()
                } else {
                    Log.w(TAG, "Weekly reflection failed: ${error.message}")
                    Result.retry()
                }
            }
        )
    }

    companion object {
        private const val TAG = "WeeklyReflectionWorker"
        private const val WORK_NAME = "mind_journal_weekly_reflection"
        private const val NO_ENTRIES = "no entries"

        /**
         * Schedules the weekly run, keeping any existing schedule.
         *
         * `KEEP` rather than `UPDATE`: replacing the request on every process start would reset
         * the period each time and, for anyone who opens the app most days, mean the reflection
         * never actually comes due.
         */
        fun ensureScheduled(context: Context) {
            val request = PeriodicWorkRequestBuilder<WeeklyReflectionWorker>(7, TimeUnit.DAYS)
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build()
                )
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request
            )
        }

        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
        }
    }
}
