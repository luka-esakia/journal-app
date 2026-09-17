package com.journal.app.notification

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.app.RemoteInput
import com.journal.app.data.local.JournalEntry
import com.journal.app.data.repository.JournalRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

/**
 * Handles text typed directly into the notification (banner or lockscreen).
 *
 * The write is asynchronous but the broadcast is kept alive with [goAsync] until Room has
 * committed, then the same notification is rebuilt as a "შენახულია ✓" confirmation. The user never
 * leaves the lockscreen.
 */
class InlineReplyReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != NotificationHelper.ACTION_INLINE_REPLY) return

        val notificationId = intent.getIntExtra(NotificationHelper.EXTRA_NOTIFICATION_ID, 0)
        val prompt = intent.getStringExtra(NotificationHelper.EXTRA_PROMPT)
        val reply = RemoteInput.getResultsFromIntent(intent)
            ?.getCharSequence(NotificationHelper.KEY_TEXT_REPLY)
            ?.toString()
            ?.trim()
            .orEmpty()

        if (reply.isEmpty()) {
            // Nothing typed: drop the notification rather than storing an empty entry.
            NotificationHelper.cancel(context, notificationId)
            return
        }

        val appContext = context.applicationContext
        val pendingResult = goAsync()

        scope.launch {
            try {
                withTimeout(WRITE_TIMEOUT_MILLIS) {
                    JournalRepository.getInstance(appContext).addEntry(
                        content = reply,
                        prompt = prompt,
                        source = JournalEntry.SOURCE_NOTIFICATION
                    )
                }
                NotificationHelper.showSaved(appContext, notificationId, prompt, reply)
            } catch (t: Throwable) {
                Log.e(TAG, "Failed to store inline reply", t)
                NotificationHelper.showSaveError(appContext, notificationId, prompt, reply)
            } finally {
                pendingResult.finish()
            }
        }
    }

    companion object {
        private const val TAG = "InlineReplyReceiver"
        private const val WRITE_TIMEOUT_MILLIS = 8_000L

        /**
         * Receiver-scoped job. A receiver instance is discarded right after [onReceive], so the
         * scope has to outlive it; AI enrichment then continues on the repository's own scope.
         */
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }
}
