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
import com.journal.app.data.local.Reflection
import com.journal.app.data.export.ImportException
import com.journal.app.data.export.JournalExporter
import com.journal.app.data.export.JournalImporter
import com.journal.app.data.repository.JournalRepository
import com.journal.app.data.repository.toLocalDate
import com.journal.app.data.search.FuzzySearch
import com.journal.app.notification.NotificationHelper
import com.journal.app.notification.PromptBank
import com.journal.app.ui.theme.AccentColor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.ZoneId

/** A one-shot, localized message for the snackbar. */
sealed interface UiMessage {
    data class Res(@StringRes val id: Int, val arg: Any? = null) : UiMessage
    data class Raw(val text: String) : UiMessage
}

/**
 * What the timeline is currently narrowed to.
 *
 * The three dimensions compose rather than replace each other — tapping `#ძილი` and then a day
 * on the calendar means "the sleep entries from that day", which is exactly what someone
 * scrubbing back through a month wants. Only [query] changes the *ordering* as well as the
 * membership, because relevance is the only sensible order for a search result.
 */
data class FeedFilter(
    val query: String = "",
    val tag: String? = null,
    val date: LocalDate? = null
) {
    fun isActive(): Boolean = query.isNotBlank() || tag != null || date != null

    /** True when results should be ranked by relevance instead of grouped by day. */
    fun isSearching(): Boolean = query.isNotBlank()
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

    /** Every generated reflection, newest first — the first is the current one. */
    val reflections: StateFlow<List<Reflection>> = repository.reflections
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT), emptyList())

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    private val _message = MutableStateFlow<UiMessage?>(null)
    val message: StateFlow<UiMessage?> = _message.asStateFlow()

    // ------------------------------------------------------------------- filter

    private val _filter = MutableStateFlow(FeedFilter())
    val filter: StateFlow<FeedFilter> = _filter.asStateFlow()

    /**
     * The feed as the timeline should render it.
     *
     * Tag and date narrow the set; the query then ranks what is left. Doing the text search last
     * means relevance is computed over the smaller set, and — more importantly — that a tag
     * filter plus a search reads as "search within this topic" rather than as two competing
     * orderings.
     */
    val visibleEntries: StateFlow<List<JournalEntry>> = combine(entries, _filter) { all, active ->
        val zone = ZoneId.systemDefault()
        val narrowed = all.filter { entry ->
            val tagMatches = active.tag == null ||
                entry.tagList().any { it.equals(active.tag, ignoreCase = true) }
            val dateMatches = active.date == null ||
                entry.createdAt.toLocalDate(zone) == active.date
            tagMatches && dateMatches
        }
        FuzzySearch.rank(narrowed, active.query)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT), emptyList())

    /**
     * Entries per calendar day, for the calendar heatmap.
     *
     * Computed from the already-loaded feed rather than in SQL: the day an entry belongs to is a
     * function of the device's current time zone, which SQLite cannot know, and a journal is
     * small enough that grouping it is cheaper than the round trip would be.
     */
    val dayCounts: StateFlow<Map<LocalDate, Int>> = entries
        .map { all ->
            val zone = ZoneId.systemDefault()
            all.groupingBy { it.createdAt.toLocalDate(zone) }.eachCount()
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT), emptyMap())

    fun setSearchQuery(query: String) {
        _filter.value = _filter.value.copy(query = query)
    }

    /** Tapping a tag chip. Tapping the tag that is already active clears it, like a toggle. */
    fun toggleTagFilter(tag: String) {
        val current = _filter.value
        _filter.value = current.copy(tag = if (current.tag == tag) null else tag)
    }

    fun setTagFilter(tag: String?) {
        _filter.value = _filter.value.copy(tag = tag)
    }

    fun setDateFilter(date: LocalDate?) {
        val current = _filter.value
        _filter.value = current.copy(date = if (current.date == date) null else date)
    }

    fun clearFilter() {
        _filter.value = FeedFilter()
    }

    // ---------------------------------------------------------------- composer

    /**
     * Whether the new-entry sheet is open.
     *
     * Sheet visibility lives here rather than in `HomeScreen` because a notification tap has to
     * be able to open it from outside the composition — see [openComposer].
     */
    private val _composerOpen = MutableStateFlow(false)
    val composerOpen: StateFlow<Boolean> = _composerOpen.asStateFlow()

    /**
     * Prompt attached to the quick-add sheet. Starts **null**: an entry written from inside the
     * app is just the entry — a prompt is something the user opts into, via the dice (bank) or
     * the stars (AI) button. A notification deep link supplies its own.
     */
    private val _quickAddPrompt = MutableStateFlow<String?>(null)
    val quickAddPrompt: StateFlow<String?> = _quickAddPrompt.asStateFlow()

    /** True while the AI prompt button is waiting on the network. */
    private val _promptLoading = MutableStateFlow(false)
    val promptLoading: StateFlow<Boolean> = _promptLoading.asStateFlow()

    /**
     * Opens the new-entry sheet, optionally carrying a prompt.
     *
     * Called both by the **+** button (no prompt) and by a notification body tap, which passes
     * the question that notification was asking so the user answers *that* rather than having to
     * remember it.
     */
    fun openComposer(prompt: String? = null) {
        _quickAddPrompt.value = prompt?.takeIf { it.isNotBlank() }
        _composerOpen.value = true
    }

    fun closeComposer() {
        _composerOpen.value = false
        clearPrompt()
    }

    // ------------------------------------------------------------------ entries

    fun addEntry(content: String, prompt: String?) {
        if (content.isBlank()) return
        viewModelScope.launch {
            repository.addEntry(content = content, prompt = prompt)
            _message.value = UiMessage.Res(R.string.entry_saved)
            closeComposer()
        }
    }

    /**
     * @param tags null when the user left the tags alone, in which case editing the text
     *   re-queues the entry for AI tagging. A non-null list is a hand edit and is kept verbatim.
     */
    fun editEntry(id: Long, content: String, prompt: String?, tags: List<String>? = null) {
        if (content.isBlank()) return
        viewModelScope.launch {
            repository.editEntry(id = id, content = content, prompt = prompt, tags = tags)
            _message.value = UiMessage.Res(R.string.entry_updated)
        }
    }

    /** Hand-edited tags for an entry whose text is unchanged. */
    fun updateTags(id: Long, tags: List<String>) {
        viewModelScope.launch {
            repository.updateTags(id, tags)
            _message.value = UiMessage.Res(R.string.entry_tags_updated)
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
    fun saveAiSettings(
        apiKey: String,
        tagModel: String,
        reflectionModel: String,
        lowPriority: Boolean
    ) {
        preferences.saveAiSettings(apiKey, tagModel, reflectionModel, lowPriority)
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
            val reflections = repository.reflections.first()
            val payload = if (asJson) {
                JournalExporter.toJson(entries, now, reflections)
            } else {
                JournalExporter.toMarkdown(entries, now, reflections)
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

    /**
     * Reads a backup document and merges it. Parse failures are reported before anything is
     * written, so a wrong file selection can never damage the journal.
     */
    fun importFrom(uri: Uri) {
        if (_busy.value) return
        viewModelScope.launch {
            _busy.value = true
            val outcome = runCatching {
                val raw = withContext(Dispatchers.IO) {
                    app.contentResolver.openInputStream(uri)?.use { stream ->
                        stream.readBytes().toString(Charsets.UTF_8)
                    } ?: error("could not open $uri")
                }
                repository.importBackup(JournalImporter.parse(raw))
            }
            _busy.value = false

            _message.value = outcome.fold(
                onSuccess = { result ->
                    when {
                        result.imported > 0 ->
                            UiMessage.Res(R.string.settings_import_done, result.imported)
                        // Entries all already present, but the file carried new reflections —
                        // reporting "nothing was added" would be false.
                        result.importedReflections > 0 -> UiMessage.Res(
                            R.string.settings_import_reflections,
                            result.importedReflections
                        )
                        else -> UiMessage.Res(R.string.settings_import_none)
                    }
                },
                onFailure = { error ->
                    when ((error as? ImportException)?.message) {
                        "wrong-format", "not-json" ->
                            UiMessage.Res(R.string.settings_import_wrong_file)
                        "version-too-new" -> UiMessage.Res(R.string.settings_import_too_new)
                        "no-entries" -> UiMessage.Res(R.string.settings_import_empty)
                        else -> UiMessage.Res(R.string.settings_import_failed)
                    }
                }
            )
        }
    }

    // -------------------------------------------------------------- re-tagging

    /** Clears every tag and regenerates them. Confirmed by the caller before it runs. */
    fun retagAll() {
        if (_busy.value) return
        viewModelScope.launch {
            _busy.value = true
            val result = repository.retagAll()
            _busy.value = false
            _message.value = result.fold(
                onSuccess = { count -> UiMessage.Res(R.string.insights_tagged, count) },
                onFailure = { error -> toMessage(error) }
            )
        }
    }

    // ------------------------------------------------------------------- lock

    val appLockEnabled: StateFlow<Boolean> = preferences.appLockEnabled

    fun setAppLockEnabled(enabled: Boolean) = preferences.setAppLockEnabled(enabled)

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
            result.fold(
                onSuccess = { reflection ->
                    _message.value =
                        UiMessage.Res(R.string.insights_reflection_done, reflection.sourceCount())
                },
                onFailure = { error -> _message.value = toMessage(error) }
            )
        }
    }

    fun deleteReflection(reflection: Reflection) {
        viewModelScope.launch {
            repository.deleteReflection(reflection)
            _message.value = UiMessage.Res(R.string.insights_reflection_deleted)
        }
    }

    /** The entries a reflection was generated from, resolved against the current journal. */
    fun sourceEntriesOf(reflection: Reflection): List<JournalEntry> {
        val ids = reflection.sourceIdList().toSet()
        if (ids.isEmpty()) return emptyList()
        return entries.value.filter { it.id in ids }.sortedBy { it.createdAt }
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
