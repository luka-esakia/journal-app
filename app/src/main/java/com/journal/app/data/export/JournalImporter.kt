package com.journal.app.data.export

import com.journal.app.data.local.JournalEntry
import com.journal.app.data.local.Reflection
import org.json.JSONArray
import org.json.JSONObject

/**
 * A reflection as it appeared in the file, with the source ids the exporting device used.
 *
 * Those ids are meaningless locally — [com.journal.app.data.repository.JournalRepository] maps
 * them onto the rows the entries become here — so they are kept out of [reflection] itself,
 * where they would look authoritative.
 */
data class ParsedReflection(
    val reflection: Reflection,
    val sourceIds: List<Long>
) {
    val generatedAt: Long get() = reflection.generatedAt
    val text: String get() = reflection.text
}

/** Outcome of parsing a backup file, before anything touches the database. */
data class ParsedBackup(
    val entries: List<JournalEntry>,
    /**
     * The id each entry carried in the file, positionally parallel to [entries].
     *
     * Separate from the entries themselves so [entries] can keep `id = 0` — the guarantee that
     * an import can never address, let alone overwrite, an existing local row. These are only
     * ever used as lookup keys when re-pointing reflection source links.
     */
    val sourceIds: List<Long>,
    val reflections: List<ParsedReflection>,
    val formatVersion: Int
)

/** How an import went. */
data class ImportResult(
    val imported: Int,
    val skipped: Int,
    val importedReflections: Int = 0
)

class ImportException(message: String) : Exception(message)

/**
 * Reads back what [JournalExporter] writes.
 *
 * Import is **additive and idempotent**: ids from the file are dropped (the local database assigns
 * its own), and an entry whose timestamp and text already exist is skipped. Importing the same
 * backup twice therefore changes nothing, and importing an older backup alongside newer entries
 * merges rather than replaces. Nothing is ever deleted by an import.
 *
 * File ids are still *reported*, in [ParsedBackup.sourceIds], purely so reflection source links
 * can be re-pointed at whatever rows the entries turn into. They never reach a write.
 */
object JournalImporter {

    // MIME types offered in the file picker. The wildcard is included because some file
    // providers hand JSON back as application/octet-stream.
    val OPEN_MIME_TYPES = arrayOf("application/json", "text/plain", "*/*")

    /**
     * Parses and validates a backup document.
     *
     * @throws ImportException when the file is not a Mind Journal backup, or is too new to read.
     */
    fun parse(raw: String): ParsedBackup {
        val root = try {
            JSONObject(raw)
        } catch (t: Throwable) {
            throw ImportException("not-json")
        }

        val format = root.optString("format")
        if (format != JournalExporter.FORMAT_ID) {
            throw ImportException("wrong-format")
        }

        val version = root.optInt("version", 1)
        if (version > JournalExporter.FORMAT_VERSION) {
            // Forward compatibility is not promised; refusing beats importing garbage.
            throw ImportException("version-too-new")
        }

        val array = root.optJSONArray("entries")
            ?: throw ImportException("no-entries")

        val entries = mutableListOf<JournalEntry>()
        val sourceIds = mutableListOf<Long>()
        for (i in 0 until array.length()) {
            val item = array.optJSONObject(i) ?: continue
            val entry = parseEntry(item) ?: continue
            entries += entry
            // 0 when the file predates ids or the field is missing — matches nothing, so a
            // reflection pointing at it simply drops that source rather than aliasing row 0.
            sourceIds += item.optLong("id", 0L)
        }
        if (entries.isEmpty()) throw ImportException("no-entries")

        // Absent in v1 files, which is not an error — those backups simply had no reflections.
        val reflections = root.optJSONArray("reflections")?.let(::parseReflections).orEmpty()

        return ParsedBackup(
            entries = entries,
            sourceIds = sourceIds,
            reflections = reflections,
            formatVersion = version
        )
    }

    private fun parseReflections(array: JSONArray): List<ParsedReflection> = buildList {
        for (i in 0 until array.length()) {
            val item = array.optJSONObject(i) ?: continue
            val text = item.optString("text").takeIf { it.isNotBlank() } ?: continue
            val generatedAt = item.optLong("generated_at_epoch_ms", 0L)
            if (generatedAt <= 0L) continue

            add(
                ParsedReflection(
                    reflection = Reflection(
                        id = 0L,
                        text = text,
                        generatedAt = generatedAt,
                        periodStart = item.optLong("period_start_epoch_ms", 0L),
                        periodEnd = item.optLong("period_end_epoch_ms", generatedAt),
                        // Filled in by the repository once the local ids are known.
                        sourceEntryIds = "",
                        model = item.optString("model")
                    ),
                    sourceIds = item.optJSONArray("source_entry_ids")?.let(::readIds).orEmpty()
                )
            )
        }
    }

    private fun readIds(array: JSONArray): List<Long> = buildList {
        for (i in 0 until array.length()) {
            array.optLong(i, 0L).takeIf { it > 0L }?.let(::add)
        }
    }

    private fun parseEntry(item: JSONObject): JournalEntry? {
        val content = item.optString("content").takeIf { it.isNotBlank() } ?: return null
        val createdAt = item.optLong("created_at_epoch_ms", 0L)
        if (createdAt <= 0L) return null

        val tags = item.optJSONArray("tags")?.let(::readTags).orEmpty()

        return JournalEntry(
            // id deliberately left at 0: the local database assigns a fresh one, so a backup can
            // never collide with or overwrite an existing row.
            id = 0L,
            content = content,
            createdAt = createdAt,
            prompt = item.optString("prompt").takeIf {
                it.isNotBlank() && !item.isNull("prompt")
            },
            tags = JournalEntry.joinTags(tags),
            source = item.optString("source").takeIf { it.isNotBlank() }
                ?: JournalEntry.SOURCE_APP,
            analyzed = item.optBoolean("analyzed", tags.isNotEmpty()),
            editedAt = item.optLong("edited_at_epoch_ms", 0L).takeIf { it > 0L }
        )
    }

    private fun readTags(array: JSONArray): List<String> = buildList {
        for (i in 0 until array.length()) {
            array.optString(i).takeIf { it.isNotBlank() }?.let(::add)
        }
    }
}
