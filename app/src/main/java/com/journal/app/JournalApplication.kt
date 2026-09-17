package com.journal.app

import android.app.Application
import com.journal.app.data.local.PreferenceManager
import com.journal.app.data.repository.JournalRepository
import com.journal.app.notification.NotificationHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Owns the process-wide singletons and makes sure the notification channel exists and the day is
 * planned, whichever component woke the process up.
 */
class JournalApplication : Application() {

    val preferences: PreferenceManager by lazy { PreferenceManager.getInstance(this) }
    val repository: JournalRepository by lazy { JournalRepository.getInstance(this) }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        NotificationHelper.createChannel(this)
        // Off the main thread: this touches encrypted prefs and AlarmManager.
        scope.launch {
            if (preferences.currentConfig().enabled) {
                NotificationHelper.planDay(this@JournalApplication)
            }
        }
    }
}
