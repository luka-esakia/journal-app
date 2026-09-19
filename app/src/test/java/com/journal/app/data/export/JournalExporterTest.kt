package com.journal.app.data.export

import com.journal.app.data.local.JournalEntry
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId

/**
 * The export is a backup, so the bar is round-trip fidelity: every field the database holds has
 * to survive, and Georgian text has to come back byte-identical.
 */
class JournalExporterTest {

    private val zone: ZoneId = ZoneId.of("Asia/Tbilisi")
    private val exportedAt = 1_760_000_000_000L

    private val entries = listOf(
        JournalEntry(
            id = 2,
            content = "საღამოს ვიფიქრე იმაზე,\nრაც დღეს გამომივიდა.",
            createdAt = 1_759_000_000_000L,
            prompt = "რამ წაგართვა ენერგია დღეს?",
            tags = "#მუშაობა,#დაღლილობა",
            source = JournalEntry.SOURCE_NOTIFICATION,
            analyzed = true
        ),
        JournalEntry(
            id = 1,
            content = "უბრალო ჩანაწერი შეკითხვის გარეშე",
            createdAt = 1_758_000_000_000L,
            prompt = null,
            tags = "",
            source = JournalEntry.SOURCE_APP,
            analyzed = false
        )
    )

    @Test
    fun `json carries every field and preserves Georgian text`() {
        val root = JSONObject(JournalExporter.toJson(entries, exportedAt, zone))

        assertEquals(JournalExporter.FORMAT_ID, root.getString("format"))
        assertEquals(JournalExporter.FORMAT_VERSION, root.getInt("version"))
        assertEquals(2, root.getInt("entry_count"))
        assertEquals("Asia/Tbilisi", root.getString("time_zone"))

        val list = root.getJSONArray("entries")
        // Oldest first, regardless of input order.
        assertEquals(1L, list.getJSONObject(0).getLong("id"))
        assertEquals(2L, list.getJSONObject(1).getLong("id"))

        val tagged = list.getJSONObject(1)
        assertEquals(entries[0].content, tagged.getString("content"))
        assertEquals("რამ წაგართვა ენერგია დღეს?", tagged.getString("prompt"))
        assertEquals(JournalEntry.SOURCE_NOTIFICATION, tagged.getString("source"))
        assertTrue(tagged.getBoolean("analyzed"))
        assertEquals(1_759_000_000_000L, tagged.getLong("created_at_epoch_ms"))
        assertEquals(2, tagged.getJSONArray("tags").length())
        assertEquals("#მუშაობა", tagged.getJSONArray("tags").getString(0))
    }

    @Test
    fun `a promptless entry exports a null prompt and an empty tag list`() {
        val root = JSONObject(JournalExporter.toJson(entries, exportedAt, zone))
        val plain = root.getJSONArray("entries").getJSONObject(0)
        assertTrue(plain.isNull("prompt"))
        assertEquals(0, plain.getJSONArray("tags").length())
    }

    @Test
    fun `markdown contains every entry body and its prompt`() {
        val md = JournalExporter.toMarkdown(entries, exportedAt, zone)
        entries.forEach { entry ->
            assertTrue("missing body", md.contains(entry.content.trim()))
        }
        assertTrue(md.contains("რამ წაგართვა ენერგია დღეს?"))
        assertTrue(md.contains("#მუშაობა"))
        assertTrue(md.startsWith("# გონების დღიური"))
    }

    @Test
    fun `export then import round-trips every field`() {
        val json = JournalExporter.toJson(entries, exportedAt, zone)
        val parsed = JournalImporter.parse(json)

        assertEquals(2, parsed.entries.size)
        val restored = parsed.entries.first { it.prompt != null }
        val original = entries[0]

        assertEquals(original.content, restored.content)
        assertEquals(original.prompt, restored.prompt)
        assertEquals(original.createdAt, restored.createdAt)
        assertEquals(original.source, restored.source)
        assertEquals(original.analyzed, restored.analyzed)
        assertEquals(original.tagList(), restored.tagList())
        // Ids are deliberately dropped so an import cannot overwrite existing rows.
        assertEquals(0L, restored.id)
    }

    @Test
    fun `import rejects files that are not Mind Journal backups`() {
        val wrong = listOf(
            """{"format":"something-else","version":1,"entries":[]}""",
            "not json at all",
            """{"format":"mind-journal-export","version":1}"""
        )
        wrong.forEach { raw ->
            assertThrows(ImportException::class.java) { JournalImporter.parse(raw) }
        }
    }

    @Test
    fun `import refuses a format version from the future`() {
        val future = """
            {"format":"mind-journal-export","version":99,
             "entries":[{"content":"ა","created_at_epoch_ms":1}]}
        """.trimIndent()
        val error = assertThrows(ImportException::class.java) { JournalImporter.parse(future) }
        assertEquals("version-too-new", error.message)
    }

    @Test
    fun `import skips malformed entries but keeps the good ones`() {
        val mixed = """
            {"format":"mind-journal-export","version":1,"entries":[
              {"content":"","created_at_epoch_ms":123},
              {"content":"კარგი ჩანაწერი","created_at_epoch_ms":0},
              {"content":"სწორი ჩანაწერი","created_at_epoch_ms":1759000000000}
            ]}
        """.trimIndent()
        val parsed = JournalImporter.parse(mixed)
        assertEquals(1, parsed.entries.size)
        assertEquals("სწორი ჩანაწერი", parsed.entries.first().content)
    }

    @Test
    fun `suggested file name is date stamped and extension correct`() {
        val name = JournalExporter.suggestedFileName("json", exportedAt, zone)
        assertTrue(name, name.startsWith("mind-journal-"))
        assertTrue(name, name.endsWith(".json"))
    }
}
