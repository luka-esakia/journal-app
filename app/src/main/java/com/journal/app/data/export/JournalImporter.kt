package com.journal.app.data.export

import com.journal.app.data.local.JournalEntry
import org.json.JSONArray
import org.json.JSONObject

/** Outcome of parsing a backup file, before anything touches the database. */
data class ParsedBackup(
    val entries: List<JournalEntry>,
    val formatVersion: Int
)

/** How an import went. */
data class ImportResult(
    val imported: Int,
    val skipped: Int
)

class ImportException(message: String) : Exception(message)

/**
 * Reads back what [JournalExporter] writes.
 *
 * Import is **additive and idempotent**: ids from the file are dropped (the local database assigns
 * its own), and an entry whose timestamp and text already exist is skipped. Importing the same
 * backup twice therefore changes nothing, and importing an older backup alongside newer entries
 * merges rather than replaces. Nothing is ever deleted by an import.
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

        val entries = buildList {
            for (i in 0 until array.length()) {
                val item = array.optJSONObject(i) ?: continue
                parseEntry(item)?.let(::add)
            }
        }
        if (entries.isEmpty()) throw ImportException("no-entries")

        return ParsedBackup(entries = entries, formatVersion = version)
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
