package com.journal.app.data.repository

import android.content.Context
import android.util.Log
import com.journal.app.data.local.JournalDao
import com.journal.app.data.local.JournalDatabase
import com.journal.app.data.local.JournalEntry
import com.journal.app.data.local.PreferenceManager
import com.journal.app.data.remote.OpenRouterClient
import com.journal.app.data.remote.OpenRouterException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Single source of truth for journal data.
 *
 * Writes always land in Room first and complete regardless of network state; LLM enrichment is a
 * best-effort background step that only flips `analyzed` once it has an answer.
 */
class JournalRepository private constructor(
    private val dao: JournalDao,
    private val prefs: PreferenceManager,
    private val client: OpenRouterClient
) {

    /** Survives individual callers (receivers, view models) so enrichment is never cancelled. */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val entries: Flow<List<JournalEntry>> = dao.observeAll()

    val unanalyzedCount: Flow<Int> = dao.observeUnanalyzedCount()

    /** Tag → occurrence count, most frequent first. */
    val tagCounts: Flow<List<Pair<String, Int>>> = entries.map { list ->
        list.flatMap { it.tagList() }
            .groupingBy { it }
            .eachCount()
            .entries
            .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
            .map { it.key to it.value }
    }

    // ------------------------------------------------------------------ writes

    /**
     * Persists an entry and returns its row id. Safe to call from a [android.content.BroadcastReceiver].
     *
     * @param analyzeNow when true, AI tagging is kicked off on the repository scope.
     */
    suspend fun addEntry(
        content: String,
        prompt: String? = null,
        source: String = JournalEntry.SOURCE_APP,
        createdAt: Long = System.currentTimeMillis(),
        analyzeNow: Boolean = true
    ): Long {
        val trimmed = content.trim()
        require(trimmed.isNotEmpty()) { "entry content must not be blank" }

        val id = withContext(Dispatchers.IO) {
            dao.insert(
                JournalEntry(
                    content = trimmed,
                    createdAt = createdAt,
                    prompt = prompt?.takeIf { it.isNotBlank() },
                    source = source
                )
            )
        }

        if (analyzeNow && prefs.currentAiSettings().isUsable()) {
            scope.launch { analyzeEntry(id) }
        }
        return id
    }

    suspend fun deleteEntry(entry: JournalEntry) = withContext(Dispatchers.IO) {
        dao.delete(entry)
    }

    suspend fun deleteAll() = withContext(Dispatchers.IO) {
        dao.deleteAll()
        prefs.clearWeeklyReflection()
    }

    // ----------------------------------------------------------------- reads

    suspend fun entriesBetween(from: Long, to: Long): List<JournalEntry> =
        withContext(Dispatchers.IO) { dao.between(from, to) }

    suspend fun entryById(id: Long): JournalEntry? =
        withContext(Dispatchers.IO) { dao.byId(id) }

    // -------------------------------------------------------------- analysis

    /** Tags a single entry. Returns the tags applied, or an empty list when nothing was stored. */
    suspend fun analyzeEntry(id: Long): List<String> {
        val settings = prefs.currentAiSettings()
        if (!settings.isUsable()) return emptyList()

        val entry = entryById(id) ?: return emptyList()
        val result = client.extractTags(settings, entry.content, entry.prompt)

        return result.fold(
            onSuccess = { tags ->
                withContext(Dispatchers.IO) {
                    if (tags.isEmpty()) {
                        dao.markAnalyzed(entry.id)
                    } else {
                        dao.applyTags(entry.id, JournalEntry.joinTags(tags))
                    }
                }
                tags
            },
            onFailure = { error ->
                // Leave `analyzed = 0` so the entry is retried later; only log the failure class.
                Log.w(TAG, "Tagging failed for entry ${entry.id}: ${error.message}")
                emptyList()
            }
        )
    }

    /** Walks the backlog of untagged entries. Returns how many were successfully tagged. */
    suspend fun analyzePending(limit: Int = PENDING_BATCH): Result<Int> {
        val settings = prefs.currentAiSettings()
        if (!settings.isUsable()) {
            return Result.failure(OpenRouterException("missing api key"))
        }
        val pending = withContext(Dispatchers.IO) { dao.unanalyzed(limit) }
        if (pending.isEmpty()) return Result.success(0)

        var tagged = 0
        pending.forEach { entry ->
            if (analyzeEntry(entry.id).isNotEmpty()) tagged++
        }
        return Result.success(tagged)
    }

    /**
     * Asks the LLM for one fresh Georgian prompt, slanted toward the user's recent topics.
     * Returns a failure (rather than a bank fallback) so the UI can explain why it didn't work.
     */
    suspend fun generatePrompt(): Result<String> {
        val settings = prefs.currentAiSettings()
        if (!settings.isUsable()) {
            return Result.failure(OpenRouterException("missing api key"))
        }
        val recentTags = withContext(Dispatchers.IO) {
            dao.latest(RECENT_FOR_PROMPT)
                .flatMap { it.tagList() }
                .groupingBy { it }
                .eachCount()
                .entries
                .sortedByDescending { it.value }
                .map { it.key }
        }
        return client.generatePrompt(settings, recentTags).mapCatching { prompt ->
            prompt.takeIf { it.isNotBlank() }
                ?: throw OpenRouterException("empty completion")
        }
    }

    /** Generates and persists the weekly reflection over the trailing seven days. */
    suspend fun generateWeeklyReflection(now: Long = System.currentTimeMillis()): Result<String> {
        val settings = prefs.currentAiSettings()
        if (!settings.isUsable()) {
            return Result.failure(OpenRouterException("missing api key"))
        }

        val zone = ZoneId.systemDefault()
        val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        val from = today.minusDays(6).atStartOfDay(zone).toInstant().toEpochMilli()
        val to = today.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()

        val week = entriesBetween(from, to)
        if (week.isEmpty()) {
            return Result.failure(OpenRouterException("no entries"))
        }

        val dated = week.map { entry ->
            val date = Instant.ofEpochMilli(entry.createdAt).atZone(zone).toLocalDate()
            val label = date.format(DATE_FORMAT)
            val text = if (entry.prompt.isNullOrBlank()) {
                entry.content
            } else {
                "${entry.prompt} → ${entry.content}"
            }
            label to text
        }

        return client.weeklyReflection(settings, dated).onSuccess { text ->
            prefs.saveWeeklyReflection(text, now)
        }
    }

    /** Entry count inside the current trailing week, used by the insights header. */
    fun countThisWeek(entries: List<JournalEntry>, now: Long = System.currentTimeMillis()): Int {
        val zone = ZoneId.systemDefault()
        val cutoff = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
            .minusDays(6)
            .atStartOfDay(zone)
            .toInstant()
            .toEpochMilli()
        return entries.count { it.createdAt >= cutoff }
    }

    companion object {
        private const val TAG = "JournalRepository"
        private const val PENDING_BATCH = 15
        private const val RECENT_FOR_PROMPT = 25
        private val DATE_FORMAT: DateTimeFormatter =
            DateTimeFormatter.ofPattern("d MMM", Locale("ka", "GE"))

        @Volatile
        private var instance: JournalRepository? = null

        fun getInstance(context: Context): JournalRepository =
            instance ?: synchronized(this) {
                instance ?: JournalRepository(
                    dao = JournalDatabase.getInstance(context).journalDao(),
                    prefs = PreferenceManager.getInstance(context),
                    client = OpenRouterClient.getInstance()
                ).also { instance = it }
            }
    }
}

/** Local date helper reused by the UI layer. */
internal fun Long.toLocalDate(zone: ZoneId = ZoneId.systemDefault()): LocalDate =
    Instant.ofEpochMilli(this).atZone(zone).toLocalDate()
