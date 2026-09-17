package com.journal.app.ui

import android.app.Application
import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.journal.app.R
import com.journal.app.data.local.AiSettings
import com.journal.app.data.local.JournalEntry
import com.journal.app.data.local.NotificationConfig
import com.journal.app.data.local.PreferenceManager
import com.journal.app.data.local.WeeklyReflection
import com.journal.app.data.repository.JournalRepository
import com.journal.app.notification.NotificationHelper
import com.journal.app.ui.theme.AccentColor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** A one-shot, localized message for the snackbar. */
sealed interface UiMessage {
    data class Res(@StringRes val id: Int, val arg: Any? = null) : UiMessage
    data class Raw(val text: String) : UiMessage
}

/**
 * Single view model for the whole app. The screens are small and share the same handful of flows,
 * so splitting per screen would only duplicate the wiring.
 */
class JournalViewModel(
    private val app: Application,
    private val repository: JournalRepository,
    private val preferences: PreferenceManager
) : ViewModel() {

    val entries: StateFlow<List<JournalEntry>> = repository.entries
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT), emptyList())

    val tagCounts: StateFlow<List<Pair<String, Int>>> = repository.tagCounts
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT), emptyList())

    val unanalyzedCount: StateFlow<Int> = repository.unanalyzedCount
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT), 0)

    val accent: StateFlow<AccentColor> = preferences.accentKey
        .map { AccentColor.fromKey(it) }
        .stateIn(
            viewModelScope,
            SharingStarted.Eagerly,
            AccentColor.fromKey(preferences.accentKey.value)
        )

    val config: StateFlow<NotificationConfig> = preferences.notificationConfig
    val aiSettings: StateFlow<AiSettings> = preferences.aiSettings
    val weeklyReflection: StateFlow<WeeklyReflection?> = preferences.weeklyReflection

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    private val _message = MutableStateFlow<UiMessage?>(null)
    val message: StateFlow<UiMessage?> = _message.asStateFlow()

    /** Prompt shown in the quick-add sheet; reshuffled on demand. */
    private val _quickAddPrompt = MutableStateFlow(NotificationHelper.PROMPTS.random())
    val quickAddPrompt: StateFlow<String> = _quickAddPrompt.asStateFlow()

    // ------------------------------------------------------------------ entries

    fun addEntry(content: String, prompt: String?) {
        if (content.isBlank()) return
        viewModelScope.launch {
            repository.addEntry(content = content, prompt = prompt)
            _message.value = UiMessage.Res(R.string.entry_saved)
            shufflePrompt()
        }
    }

    fun deleteEntry(entry: JournalEntry) {
        viewModelScope.launch {
            repository.deleteEntry(entry)
            _message.value = UiMessage.Res(R.string.entry_deleted)
        }
    }

    fun deleteAllEntries() {
        viewModelScope.launch {
            repository.deleteAll()
            _message.value = UiMessage.Res(R.string.settings_cleared)
        }
    }

    fun shufflePrompt() {
        val current = _quickAddPrompt.value
        val candidates = NotificationHelper.PROMPTS.filter { it != current }
        _quickAddPrompt.value = candidates.randomOrNull() ?: current
    }

    fun entriesThisWeek(): Int = repository.countThisWeek(entries.value)

    // ----------------------------------------------------------------- schedule

    fun saveConfig(config: NotificationConfig) {
        viewModelScope.launch {
            preferences.saveConfig(config)
            withContext(Dispatchers.IO) {
                if (config.enabled) {
                    NotificationHelper.planDay(app)
                } else {
                    NotificationHelper.cancelAll(app)
                }
            }
            _message.value = UiMessage.Res(R.string.config_saved)
        }
    }

    /** Deterministic preview of a schedule, so the UI does not reshuffle on every recomposition. */
    fun previewSlots(config: NotificationConfig, dayStartMillis: Long, seed: Int) =
        NotificationHelper.buildSlots(
            config = config,
            dayStartMillis = dayStartMillis,
            random = kotlin.random.Random(seed)
        )

    // --------------------------------------------------------------- settings

    fun setAccent(accent: AccentColor) = preferences.setAccentKey(accent.name)

    fun saveApiKey(key: String) {
        preferences.saveApiKey(key)
        _message.value = UiMessage.Res(R.string.settings_api_key_saved)
    }

    fun clearApiKey() = preferences.saveApiKey("")

    fun saveModel(model: String) = preferences.saveModel(model)

    fun setAiEnabled(enabled: Boolean) = preferences.setAiEnabled(enabled)

    // ---------------------------------------------------------------- insights

    fun analyzePending() {
        if (_busy.value) return
        viewModelScope.launch {
            _busy.value = true
            val result = repository.analyzePending()
            _busy.value = false
            result.fold(
                onSuccess = { count ->
                    _message.value = if (count == 0) {
                        UiMessage.Res(R.string.error_no_entries)
                    } else {
                        UiMessage.Res(R.string.insights_tagged, count)
                    }
                },
                onFailure = { error -> _message.value = toMessage(error) }
            )
        }
    }

    fun generateWeeklyReflection() {
        if (_busy.value) return
        viewModelScope.launch {
            _busy.value = true
            val result = repository.generateWeeklyReflection()
            _busy.value = false
            result.onFailure { error -> _message.value = toMessage(error) }
        }
    }

    fun consumeMessage() {
        _message.value = null
    }

    private fun toMessage(error: Throwable): UiMessage = when {
        error.message == "missing api key" -> UiMessage.Res(R.string.error_no_key)
        error.message == "no entries" -> UiMessage.Res(R.string.error_no_entries)
        error.message.isNullOrBlank() -> UiMessage.Res(R.string.error_generic)
        else -> UiMessage.Raw(error.message!!)
    }

    companion object {
        private const val STOP_TIMEOUT = 5_000L

        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val application = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY]
                    as Application
                JournalViewModel(
                    app = application,
                    repository = JournalRepository.getInstance(application),
                    preferences = PreferenceManager.getInstance(application)
                )
            }
        }
    }
}
