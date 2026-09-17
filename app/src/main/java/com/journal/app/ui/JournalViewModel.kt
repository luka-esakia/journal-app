package com.journal.app.ui

import android.app.Application
import android.net.Uri
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
import com.journal.app.data.export.JournalExporter
import com.journal.app.data.repository.JournalRepository
import com.journal.app.notification.NotificationHelper
import com.journal.app.notification.PromptBank
import com.journal.app.ui.theme.AccentColor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
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

    /**
     * Prompt attached to the quick-add sheet. Starts **null**: an entry written from inside the
     * app is just the entry — a prompt is something the user opts into, via the dice (bank) or
     * the stars (AI) button.
     */
    private val _quickAddPrompt = MutableStateFlow<String?>(null)
    val quickAddPrompt: StateFlow<String?> = _quickAddPrompt.asStateFlow()

    /** True while the AI prompt button is waiting on the network. */
    private val _promptLoading = MutableStateFlow(false)
    val promptLoading: StateFlow<Boolean> = _promptLoading.asStateFlow()

    // ------------------------------------------------------------------ entries

    fun addEntry(content: String, prompt: String?) {
        if (content.isBlank()) return
        viewModelScope.launch {
            repository.addEntry(content = content, prompt = prompt)
            _message.value = UiMessage.Res(R.string.entry_saved)
            clearPrompt()
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

    /** 🎲 — draw a different prompt from the local bank. Free and instant. */
    fun pickPromptFromBank() {
        _quickAddPrompt.value = PromptBank.randomOtherThan(_quickAddPrompt.value)
    }

    /** ✨ — ask the LLM for a new prompt informed by recent topics. Falls back to the bank. */
    fun generateAiPrompt() {
        if (_promptLoading.value) return
        viewModelScope.launch {
            _promptLoading.value = true
            val result = repository.generatePrompt()
            _promptLoading.value = false
            result.fold(
                onSuccess = { _quickAddPrompt.value = it },
                onFailure = { error ->
                    // Still give the user a prompt — just from the bank, and say why.
                    _quickAddPrompt.value = PromptBank.randomOtherThan(_quickAddPrompt.value)
                    _message.value = toMessage(error)
                }
            )
        }
    }

    fun clearPrompt() {
        _quickAddPrompt.value = null
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

    /** The single explicit save for the whole AI block. */
    fun saveAiSettings(apiKey: String, model: String, lowPriority: Boolean) {
        preferences.saveAiSettings(apiKey, model, lowPriority)
        _message.value = UiMessage.Res(R.string.settings_saved)
    }

    fun clearApiKey() {
        preferences.saveApiKey("")
        _message.value = UiMessage.Res(R.string.settings_api_key_cleared)
    }

    fun setAiEnabled(enabled: Boolean) = preferences.setAiEnabled(enabled)

    fun addCustomModel(slug: String) {
        preferences.addCustomModel(slug)
        _message.value = UiMessage.Res(R.string.settings_model_added)
    }

    fun removeCustomModel(slug: String) = preferences.removeCustomModel(slug)

    // ----------------------------------------------------------------- export

    /** Writes the journal to a user-chosen document via the Storage Access Framework. */
    fun exportTo(uri: Uri, asJson: Boolean) {
        viewModelScope.launch {
            val entries = repository.entries.first()
            if (entries.isEmpty()) {
                _message.value = UiMessage.Res(R.string.error_no_entries)
                return@launch
            }
            val now = System.currentTimeMillis()
            val payload = if (asJson) {
                JournalExporter.toJson(entries, now)
            } else {
                JournalExporter.toMarkdown(entries, now)
            }
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    app.contentResolver.openOutputStream(uri)?.use { stream ->
                        stream.write(payload.toByteArray(Charsets.UTF_8))
                    } ?: error("could not open $uri for writing")
                }
            }
            _message.value = result.fold(
                onSuccess = { UiMessage.Res(R.string.settings_export_done, entries.size) },
                onFailure = { UiMessage.Res(R.string.settings_export_failed) }
            )
        }
    }

    fun suggestedExportName(asJson: Boolean): String =
        JournalExporter.suggestedFileName(
            extension = if (asJson) "json" else "md",
            now = System.currentTimeMillis()
        )

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
