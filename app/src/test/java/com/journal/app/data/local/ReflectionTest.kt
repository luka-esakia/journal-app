package com.journal.app.data.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The source-id list is the whole point of the reflections table, so the parsing has to survive
 * whatever an import or a hand-edited backup puts in the column.
 */
class ReflectionTest {

    private fun reflection(ids: String) = Reflection(
        text = "ტექსტი",
        generatedAt = 1_759_500_000_000L,
        periodStart = 1_758_900_000_000L,
        periodEnd = 1_759_500_000_000L,
        sourceEntryIds = ids
    )

    @Test
    fun `ids round-trip through the stored string`() {
        val joined = Reflection.joinIds(listOf(3L, 1L, 2L))
        assertEquals("1,2,3", joined)
        assertEquals(listOf(1L, 2L, 3L), reflection(joined).sourceIdList())
    }

    @Test
    fun `duplicates collapse rather than inflating the source count`() {
        assertEquals("1,2", Reflection.joinIds(listOf(1L, 2L, 1L, 2L)))
        assertEquals(2, reflection("1,2,1").sourceCount())
    }

    @Test
    fun `an empty list means no recorded sources, not a source of zero`() {
        assertEquals("", Reflection.joinIds(emptyList()))
        assertTrue(reflection("").sourceIdList().isEmpty())
        assertEquals(0, reflection("").sourceCount())
    }

    @Test
    fun `junk in the column is dropped instead of crashing the screen`() {
        // A hand-edited backup, or a column written by some future version.
        assertEquals(listOf(4L, 7L), reflection("4, ,abc,7,").sourceIdList())
    }

    @Test
    fun `whitespace around ids survives an import`() {
        assertEquals(listOf(1L, 2L), reflection(" 1 , 2 ").sourceIdList())
    }
}
