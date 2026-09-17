package com.journal.app.notification

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Receives the scheduled alarms.
 *
 * Three jobs:
 *  - [NotificationHelper.ACTION_SHOW_PROMPT] — post one prompt notification.
 *  - [NotificationHelper.ACTION_PLAN_DAY] — the daily planner alarm, re-chunks the new day.
 *  - system broadcasts (boot, package replaced, time/timezone change) — alarms do not survive
 *    those, so the day is re-planned immediately.
 */
class NotificationReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        when (val action = intent.action) {
            NotificationHelper.ACTION_SHOW_PROMPT -> showPrompt(context, intent)

            NotificationHelper.ACTION_REROLL_PROMPT -> reroll(context, intent)

            NotificationHelper.ACTION_PLAN_DAY -> planAsync(context, "planner alarm")

            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            Intent.ACTION_TIME_CHANGED,
            Intent.ACTION_TIMEZONE_CHANGED -> planAsync(context, action.orEmpty())

            else -> Log.w(TAG, "Ignoring unknown action: $action")
        }
    }

    /**
     * Planning touches encrypted preferences and AlarmManager, so it is pushed off the main
     * thread; [goAsync] keeps the broadcast alive until it finishes.
     */
    private fun planAsync(context: Context, reason: String) {
        val appContext = context.applicationContext
        val pendingResult = goAsync()
        scope.launch {
            try {
                Log.i(TAG, "Re-planning after $reason")
                NotificationHelper.planDay(appContext)
            } catch (t: Throwable) {
                Log.e(TAG, "Planning failed", t)
            } finally {
                pendingResult.finish()
            }
        }
    }

    private fun showPrompt(context: Context, intent: Intent) {
        val slotIndex = intent.getIntExtra(NotificationHelper.EXTRA_SLOT_INDEX, 0)
        val notificationId = intent.getIntExtra(
            NotificationHelper.EXTRA_NOTIFICATION_ID,
            DEFAULT_NOTIFICATION_ID + slotIndex
        )
        val prompt = intent.getStringExtra(NotificationHelper.EXTRA_PROMPT)
            ?.takeIf { it.isNotBlank() }
            ?: PromptBank.random()

        Log.i(TAG, "Showing prompt for slot $slotIndex")
        NotificationHelper.showPromptNotification(context, prompt, notificationId)
    }

    /**
     * "🔄 შეცვლა" — swap the prompt for a different one, in place, without opening the app.
     * Synchronous: no storage or preference reads, just a draw from the in-memory bank.
     */
    private fun reroll(context: Context, intent: Intent) {
        val notificationId = intent.getIntExtra(
            NotificationHelper.EXTRA_NOTIFICATION_ID,
            DEFAULT_NOTIFICATION_ID
        )
        val current = intent.getStringExtra(NotificationHelper.EXTRA_PROMPT)
        Log.i(TAG, "Rerolling prompt for notification $notificationId")
        NotificationHelper.rerollPrompt(context, notificationId, current)
    }

    companion object {
        private const val TAG = "NotificationReceiver"
        private const val DEFAULT_NOTIFICATION_ID = 2_000

        /** Outlives the receiver instance, which is discarded as soon as onReceive returns. */
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }
}
