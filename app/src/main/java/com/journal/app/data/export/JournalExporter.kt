package com.journal.app.data.export

import com.journal.app.data.local.JournalEntry
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Turns the journal into a file the user owns.
 *
 * Two formats, because a backup and a reading copy want opposite things:
 *
 *  - **JSON** is the backup. It round-trips losslessly — ids, epoch-millis timestamps, the prompt,
 *    the AI tags, the source, the analysis flag — so a future import can reconstruct the database
 *    exactly. It is also the only format that stays machine-readable if this app disappears.
 *  - **Markdown** is the reading copy: grouped by day, plain text, opens in anything.
 *
 * Deliberately not CSV: entries are multi-line free text, and every CSV consumer disagrees about
 * embedded newlines. Deliberately not a Room `.db` copy: opaque, version-locked, and useless
 * without this schema.
 */
object JournalExporter {

    const val JSON_MIME = "application/json"
    const val MARKDOWN_MIME = "text/markdown"

    const val FORMAT_ID = "mind-journal-export"
    const val FORMAT_VERSION = 1

    private val GEORGIAN = Locale("ka", "GE")
    private val FILE_STAMP: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd", Locale.US)
    private val DAY_HEADING: DateTimeFormatter =
        DateTimeFormatter.ofPattern("d MMMM yyyy, EEEE", GEORGIAN)
    private val CLOCK: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm", GEORGIAN)

    fun suggestedFileName(extension: String, now: Long, zone: ZoneId = ZoneId.systemDefault()): String {
        val stamp = Instant.ofEpochMilli(now).atZone(zone).format(FILE_STAMP)
        return "mind-journal-$stamp.$extension"
    }

    /** Lossless backup. Timestamps carry both epoch millis and an ISO-8601 rendering. */
    fun toJson(
        entries: List<JournalEntry>,
        exportedAt: Long,
        zone: ZoneId = ZoneId.systemDefault()
    ): String {
        val payload = JSONObject().apply {
            put("format", FORMAT_ID)
            put("version", FORMAT_VERSION)
            put("exported_at", Instant.ofEpochMilli(exportedAt).toString())
            put("exported_at_epoch_ms", exportedAt)
            put("time_zone", zone.id)
            put("entry_count", entries.size)
            put(
                "entries",
                JSONArray().apply {
                    // Oldest first: a backup reads as a timeline, not a feed.
                    entries.sortedBy { it.createdAt }.forEach { entry ->
                        put(
                            JSONObject().apply {
                                put("id", entry.id)
                                put("created_at_epoch_ms", entry.createdAt)
                                put("created_at", Instant.ofEpochMilli(entry.createdAt).toString())
                                put("content", entry.content)
                                put("prompt", entry.prompt ?: JSONObject.NULL)
                                put("source", entry.source)
                                put("analyzed", entry.analyzed)
                                put("edited_at_epoch_ms", entry.editedAt ?: JSONObject.NULL)
                                put("tags", JSONArray(entry.tagList()))
                            }
                        )
                    }
                }
            )
        }
        return payload.toString(2)
    }

    /** Human-readable copy, grouped by day. */
    fun toMarkdown(
        entries: List<JournalEntry>,
        exportedAt: Long,
        zone: ZoneId = ZoneId.systemDefault()
    ): String = buildString {
        appendLine("# გონების დღიური")
        appendLine()
        appendLine(
            "ექსპორტი: " + Instant.ofEpochMilli(exportedAt).atZone(zone).format(DAY_HEADING) +
                " · ჩანაწერი: ${entries.size}"
        )
        appendLine()

        entries
            .sortedBy { it.createdAt }
            .groupBy { Instant.ofEpochMilli(it.createdAt).atZone(zone).toLocalDate() }
            .forEach { (date, dayEntries) ->
                appendLine("## " + date.format(DAY_HEADING))
                appendLine()
                dayEntries.forEach { entry ->
                    val time = Instant.ofEpochMilli(entry.createdAt).atZone(zone).format(CLOCK)
                    appendLine("### $time")
                    if (!entry.prompt.isNullOrBlank()) {
                        appendLine()
                        appendLine("> ${entry.prompt}")
                    }
                    appendLine()
                    // Blockquote-free body so the entry text stays copy-pasteable.
                    appendLine(entry.content.trim())
                    val tags = entry.tagList()
                    if (tags.isNotEmpty()) {
                        appendLine()
                        appendLine("`${tags.joinToString("` `")}`")
                    }
                    appendLine()
                }
            }
    }
}
