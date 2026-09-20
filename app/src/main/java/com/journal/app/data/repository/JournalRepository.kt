package com.journal.app.data.repository

import android.content.Context
import android.util.Log
import com.journal.app.data.export.ImportResult
import com.journal.app.data.export.ParsedBackup
import com.journal.app.data.local.AiTask
import com.journal.app.data.local.JournalDao
import com.journal.app.data.local.JournalDatabase
import com.journal.app.data.local.JournalEntry
import com.journal.app.data.local.PreferenceManager
import com.journal.app.data.local.Reflection
import com.journal.app.data.local.ReflectionDao
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
    private val reflectionDao: ReflectionDao,
    private val prefs: PreferenceManager,
    private val client: OpenRouterClient
) {

    /** Survives individual callers (receivers, view models) so enrichment is never cancelled. */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    init {
        scope.launch { migrateLegacyReflection() }
    }

    val entries: Flow<List<JournalEntry>> = dao.observeAll()

    /** Every generated reflection, newest first. */
    val reflections: Flow<List<Reflection>> = reflectionDao.observeAll()

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

    /**
     * Rewrites an entry's text.
     *
     * @param tags when null, the old tags are dropped and the entry is re-queued for analysis,
     *   because topics derived from the previous wording would otherwise linger. When non-null
     *   the user edited the tags by hand, so they are stored as-is and the LLM is not invited to
     *   overwrite them — hand-written tags outrank generated ones.
     */
    suspend fun editEntry(
        id: Long,
        content: String,
        prompt: String?,
        tags: List<String>? = null,
        editedAt: Long = System.currentTimeMillis()
    ) {
        val trimmed = content.trim()
        require(trimmed.isNotEmpty()) { "entry content must not be blank" }

        withContext(Dispatchers.IO) {
            dao.editEntry(
                id = id,
                content = trimmed,
                prompt = prompt?.takeIf { it.isNotBlank() },
                editedAt = editedAt
            )
            // editEntry clears tags and resets `analyzed`; re-apply straight after so a combined
            // text-and-tag edit does not lose the tags the user just typed.
            if (tags != null) dao.applyTags(id, JournalEntry.joinTags(tags))
        }
        if (tags == null && prefs.currentAiSettings().isUsable()) {
            scope.launch { analyzeEntry(id) }
        }
    }

    /**
     * Replaces an entry's tags without touching its text.
     *
     * Marks the entry analyzed, which is what keeps the background tagger from quietly reverting
     * the edit on its next pass.
     */
    suspend fun updateTags(id: Long, tags: List<String>) = withContext(Dispatchers.IO) {
        dao.applyTags(id, JournalEntry.joinTags(tags.mapNotNull(JournalEntry::normalizeTag)))
    }

    suspend fun deleteEntry(entry: JournalEntry) = withContext(Dispatchers.IO) {
        dao.delete(entry)
    }

    /**
     * Merges a parsed backup. Additive only: entries already present (same timestamp and text)
     * are skipped, so re-importing the same file is a no-op and nothing is ever overwritten.
     *
     * Reflections come with the entry ids *the exporting device* used, which mean nothing here.
     * Each file id is therefore resolved to the local row — the one just inserted, or the
     * existing duplicate that was skipped — and ids with no local counterpart are dropped rather
     * than kept as dangling numbers. Inserting one entry at a time (instead of `insertAll`) is
     * what makes that mapping available; a backup is a few thousand rows at most, once.
     */
    suspend fun importBackup(backup: ParsedBackup): ImportResult = withContext(Dispatchers.IO) {
        var skippedEntries = 0
        /** Exported entry id → local row id. */
        val idMap = HashMap<Long, Long>()
        var imported = 0

        backup.entries.forEachIndexed { index, entry ->
            val fileId = backup.sourceIds.getOrNull(index)

            // Catches rows this device already had *and* duplicates inside the file itself: the
            // first copy has been inserted by the time the second is looked up.
            val existing = dao.idOf(entry.createdAt, entry.content)
            if (existing != null) {
                skippedEntries++
                if (fileId != null) idMap[fileId] = existing
                return@forEachIndexed
            }

            // id is already 0 out of the importer; forced here so a future parser change cannot
            // let a file-supplied id overwrite a local row.
            val localId = dao.insert(entry.copy(id = 0L))
            imported++
            if (fileId != null) idMap[fileId] = localId
        }

        var importedReflections = 0
        backup.reflections.forEach { parsed ->
            if (reflectionDao.countMatching(parsed.generatedAt, parsed.text) > 0) return@forEach
            reflectionDao.insert(
                parsed.reflection.copy(
                    id = 0L,
                    sourceEntryIds = Reflection.joinIds(parsed.sourceIds.mapNotNull(idMap::get))
                )
            )
            importedReflections++
        }

        ImportResult(
            imported = imported,
            skipped = skippedEntries,
            importedReflections = importedReflections
        )
    }

    /** Queues every entry for re-tagging, then walks the whole backlog. */
    suspend fun retagAll(): Result<Int> {
        val settings = prefs.currentAiSettings()
        if (!settings.isUsable()) {
            return Result.failure(OpenRouterException("missing api key"))
        }
        withContext(Dispatchers.IO) { dao.resetAllAnalysis() }

        // Snapshot rather than re-querying `unanalyzed` in a loop: a failed call intentionally
        // leaves `analyzed = 0` for a later retry, so a drain loop would never terminate while
        // the API is down.
        val snapshot = withContext(Dispatchers.IO) { dao.latest(MAX_RETAG) }
        if (snapshot.size == MAX_RETAG) {
            Log.w(TAG, "Re-tagging capped at $MAX_RETAG entries; older ones keep their tags")
        }

        var tagged = 0
        var consecutiveFailures = 0
        for (entry in snapshot) {
            if (analyzeEntry(entry.id).isNotEmpty()) {
                tagged++
                consecutiveFailures = 0
            } else {
                consecutiveFailures++
                // Bail out early rather than burning the user's credit on a failing key/model.
                if (consecutiveFailures >= RETAG_FAILURE_LIMIT) {
                    return Result.failure(
                        OpenRouterException("re-tagging stopped after $consecutiveFailures failures")
                    )
                }
            }
        }
        return Result.success(tagged)
    }

    suspend fun deleteAll() = withContext(Dispatchers.IO) {
        dao.deleteAll()
        // Reflections are derived from entries; keeping them would leave a summary of a journal
        // that no longer exists, with source links pointing at deleted rows.
        reflectionDao.deleteAll()
    }

    suspend fun deleteReflection(reflection: Reflection) = withContext(Dispatchers.IO) {
        reflectionDao.delete(reflection)
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

    /**
     * Generates and persists the weekly reflection over the trailing seven days.
     *
     * The ids of the entries that were actually sent are stored alongside the text, so an export
     * can show exactly which raw material produced which reflection. Only entries that made it
     * into the request are recorded — not "everything in the window" — which is the difference
     * between provenance and a guess.
     */
    suspend fun generateWeeklyReflection(now: Long = System.currentTimeMillis()): Result<Reflection> {
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

        return client.weeklyReflection(settings, dated).mapCatching { text ->
            val reflection = Reflection(
                text = text,
                generatedAt = now,
                periodStart = from,
                periodEnd = to,
                sourceEntryIds = Reflection.joinIds(week.map { it.id }),
                model = settings.modelFor(AiTask.REFLECTION)
            )
            val id = withContext(Dispatchers.IO) { reflectionDao.insert(reflection) }
            reflection.copy(id = id)
        }
    }

    /**
     * Moves the single pre-v3 reflection out of preferences and into the table, once.
     *
     * Its source ids are unknowable — the old storage never recorded them — so the row lands with
     * an empty list. That is honest: an export will show "no recorded sources" rather than
     * inventing a plausible week's worth.
     */
    private suspend fun migrateLegacyReflection() {
        try {
            val legacy = prefs.consumeLegacyReflection() ?: return
            if (reflectionDao.count() > 0) return
            val generatedAt = legacy.generatedAt
            reflectionDao.insert(
                Reflection(
                    text = legacy.text,
                    generatedAt = generatedAt,
                    periodStart = generatedAt - LEGACY_PERIOD_MILLIS,
                    periodEnd = generatedAt,
                    sourceEntryIds = "",
                    model = ""
                )
            )
        } catch (t: Throwable) {
            Log.w(TAG, "Could not migrate the legacy reflection", t)
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
        private const val MAX_RETAG = 500
        private const val RETAG_FAILURE_LIMIT = 3

        /** Assumed span of a migrated pre-v3 reflection: the seven days it always covered. */
        private const val LEGACY_PERIOD_MILLIS = 7L * 24 * 60 * 60 * 1000

        private val DATE_FORMAT: DateTimeFormatter =
            DateTimeFormatter.ofPattern("d MMM", Locale("ka", "GE"))

        @Volatile
        private var instance: JournalRepository? = null

        fun getInstance(context: Context): JournalRepository =
            instance ?: synchronized(this) {
                instance ?: run {
                    val database = JournalDatabase.getInstance(context)
                    JournalRepository(
                        dao = database.journalDao(),
                        reflectionDao = database.reflectionDao(),
                        prefs = PreferenceManager.getInstance(context),
                        client = OpenRouterClient.getInstance()
                    ).also { instance = it }
                }
            }
    }
}

/** Local date helper reused by the UI layer. */
internal fun Long.toLocalDate(zone: ZoneId = ZoneId.systemDefault()): LocalDate =
    Instant.ofEpochMilli(this).atZone(zone).toLocalDate()
