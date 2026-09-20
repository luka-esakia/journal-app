package com.journal.app.data.export

import com.journal.app.data.local.JournalEntry
import com.journal.app.data.local.Reflection
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

    /** v2 adds the `reflections` array. v1 files (entries only) still import unchanged. */
    const val FORMAT_VERSION = 2

    private val GEORGIAN = Locale("ka", "GE")
    private val FILE_STAMP: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd", Locale.US)
    private val DAY_HEADING: DateTimeFormatter =
        DateTimeFormatter.ofPattern("d MMMM yyyy, EEEE", GEORGIAN)
    private val CLOCK: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm", GEORGIAN)
    private val SOURCE_STAMP: DateTimeFormatter =
        DateTimeFormatter.ofPattern("d MMM, HH:mm", GEORGIAN)

    /** Enough of a source entry to recognise it in the timeline above, not a second copy of it. */
    private const val SOURCE_PREVIEW_CHARS = 90

    fun suggestedFileName(extension: String, now: Long, zone: ZoneId = ZoneId.systemDefault()): String {
        val stamp = Instant.ofEpochMilli(now).atZone(zone).format(FILE_STAMP)
        return "mind-journal-$stamp.$extension"
    }

    /**
     * Lossless backup. Timestamps carry both epoch millis and an ISO-8601 rendering.
     *
     * Entry ids are exported even though an import discards them, because they are what makes
     * `reflections[].source_entry_ids` mean anything: the importer maps each exported id onto the
     * row it becomes locally, and so the link between a reflection and the raw entries behind it
     * survives moving to a new device.
     */
    fun toJson(
        entries: List<JournalEntry>,
        exportedAt: Long,
        reflections: List<Reflection> = emptyList(),
        zone: ZoneId = ZoneId.systemDefault()
    ): String {
        val payload = JSONObject().apply {
            put("format", FORMAT_ID)
            put("version", FORMAT_VERSION)
            put("exported_at", Instant.ofEpochMilli(exportedAt).toString())
            put("exported_at_epoch_ms", exportedAt)
            put("time_zone", zone.id)
            put("entry_count", entries.size)
            put("reflection_count", reflections.size)
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
            put(
                "reflections",
                JSONArray().apply {
                    reflections.sortedBy { it.generatedAt }.forEach { reflection ->
                        put(
                            JSONObject().apply {
                                put("id", reflection.id)
                                put("generated_at_epoch_ms", reflection.generatedAt)
                                put(
                                    "generated_at",
                                    Instant.ofEpochMilli(reflection.generatedAt).toString()
                                )
                                put("period_start_epoch_ms", reflection.periodStart)
                                put("period_end_epoch_ms", reflection.periodEnd)
                                put("model", reflection.model)
                                put("text", reflection.text)
                                put(
                                    "source_entry_ids",
                                    JSONArray().apply {
                                        reflection.sourceIdList().forEach { put(it) }
                                    }
                                )
                            }
                        )
                    }
                }
            )
        }
        return payload.toString(2)
    }

    /**
     * Human-readable copy, grouped by day, with the reflections and their sources at the end.
     *
     * Reflections cite their sources by timestamp rather than by row id: a number means nothing
     * to someone reading the file, whereas "14 მარტი, 21:40" can be found in the timeline above.
     */
    fun toMarkdown(
        entries: List<JournalEntry>,
        exportedAt: Long,
        reflections: List<Reflection> = emptyList(),
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

        if (reflections.isEmpty()) return@buildString

        val byId = entries.associateBy { it.id }
        appendLine("---")
        appendLine()
        appendLine("# რეფლექსიები")
        appendLine()
        reflections.sortedBy { it.generatedAt }.forEach { reflection ->
            val stamp = Instant.ofEpochMilli(reflection.generatedAt).atZone(zone)
            appendLine("## " + stamp.format(DAY_HEADING) + ", " + stamp.format(CLOCK))
            appendLine()
            appendLine(reflection.text.trim())
            appendLine()
            if (reflection.model.isNotBlank()) {
                appendLine("მოდელი: `${reflection.model}`")
                appendLine()
            }

            val sources = reflection.sourceIdList().mapNotNull(byId::get).sortedBy { it.createdAt }
            if (sources.isEmpty()) {
                appendLine("წყარო ჩანაწერები: არ არის ჩაწერილი")
            } else {
                appendLine("წყარო ჩანაწერები (${sources.size}):")
                sources.forEach { source ->
                    val at = Instant.ofEpochMilli(source.createdAt).atZone(zone)
                    appendLine(
                        "- " + at.format(SOURCE_STAMP) + " — " +
                            source.content.replace('\n', ' ').trim().take(SOURCE_PREVIEW_CHARS)
                    )
                }
            }
            appendLine()
        }
    }
}
