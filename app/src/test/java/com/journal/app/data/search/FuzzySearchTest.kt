package com.journal.app.data.search

import com.journal.app.data.local.JournalEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The contract: forgiving enough to survive Georgian inflection and a mistyped letter, strict
 * enough that a search does not simply return the whole journal.
 */
class FuzzySearchTest {

    private fun entry(id: Long, content: String, prompt: String? = null, tags: String = "") =
        JournalEntry(
            id = id,
            content = content,
            createdAt = 1_759_000_000_000L + id,
            prompt = prompt,
            tags = tags
        )

    // ------------------------------------------------------------------ scoring

    @Test
    fun `exact word beats prefix beats fuzzy`() {
        val exact = FuzzySearch.score("დღეს ბევრი მუშაობა იყო", "მუშაობა")
        val prefix = FuzzySearch.score("დღეს ბევრი მუშაობდა იყო", "მუშაობ")
        val fuzzy = FuzzySearch.score("დღეს ბევრი მუშაობა იყო", "მუშაობბ")

        assertNotNull(exact)
        assertNotNull(prefix)
        assertNotNull(fuzzy)
        assertTrue("$exact should beat $prefix", exact!! > prefix!!)
        assertTrue("$prefix should beat $fuzzy", prefix!! > fuzzy!!)
    }

    @Test
    fun `a Georgian suffix does not hide the word`() {
        // The entry holds the base form, the user typed the inflected one.
        assertNotNull(FuzzySearch.score("გუშინ დაღლილობა მაწუხებდა", "დაღლილობამ"))
        // …and the other way round.
        assertNotNull(FuzzySearch.score("გუშინ დაღლილობამ დამძლია", "დაღლილობა"))
    }

    @Test
    fun `one mistyped letter still matches a long word`() {
        assertNotNull(FuzzySearch.score("საღამოს ვიფიქრე ოჯახზე", "ოჯახზზე"))
    }

    @Test
    fun `short tokens must match exactly`() {
        // "ძი" vs "ძე" is a different word, not a typo — a 2-char token gets no edit budget.
        assertNull(FuzzySearch.score("ძე ჩემი", "ძი"))
    }

    @Test
    fun `every token must be present`() {
        val text = "დღეს ბევრი მუშაობა იყო"
        assertNotNull(FuzzySearch.score(text, "მუშაობა დღეს"))
        assertNull(FuzzySearch.score(text, "მუშაობა ზღვა"))
    }

    @Test
    fun `an exact phrase outranks the same words scattered`() {
        val together = FuzzySearch.score("ბევრი მუშაობა დღეს", "ბევრი მუშაობა")
        val apart = FuzzySearch.score("ბევრი რამ მოხდა და მუშაობა გაგრძელდა", "ბევრი მუშაობა")
        assertTrue("$together should beat $apart", together!! > apart!!)
    }

    @Test
    fun `a blank query matches everything`() {
        assertEquals(0, FuzzySearch.score("რაღაც ტექსტი", "   ") ?: -1)
    }

    @Test
    fun `nonsense matches nothing`() {
        assertNull(FuzzySearch.score("დღეს ბევრი მუშაობა იყო", "ველოსიპედი"))
    }

    // ------------------------------------------------------------------ haystack

    @Test
    fun `the prompt and the tags are searchable too`() {
        val tagged = entry(
            id = 1,
            content = "ვერ დავიძინე",
            prompt = "რამ წაგართვა ენერგია დღეს?",
            tags = "#ძილი,#დაღლილობა"
        )
        val haystack = FuzzySearch.searchableText(tagged)
        assertNotNull(FuzzySearch.score(haystack, "ენერგია"))
        assertNotNull(FuzzySearch.score(haystack, "#ძილი"))
        // The separator must not glue two tags into one unsearchable token.
        assertNotNull(FuzzySearch.score(haystack, "#დაღლილობა"))
    }

    // --------------------------------------------------------------------- rank

    @Test
    fun `rank drops non-matches and puts the best first`() {
        val entries = listOf(
            entry(1, "ზღვაზე წავედით"),
            entry(2, "დღეს ბევრი მუშაობა იყო"),
            entry(3, "მუშაობდა ის მთელი დღე")
        )
        val ranked = FuzzySearch.rank(entries, "მუშაობა")

        assertEquals(listOf(2L, 3L), ranked.map { it.id })
    }

    @Test
    fun `rank leaves the feed untouched for a blank query`() {
        val entries = listOf(entry(1, "ა"), entry(2, "ბ"))
        assertEquals(entries, FuzzySearch.rank(entries, ""))
    }

    @Test
    fun `equally relevant entries stay newest first`() {
        val older = entry(1, "მუშაობა")
        val newer = entry(2, "მუშაობა")
        assertEquals(listOf(2L, 1L), FuzzySearch.rank(listOf(older, newer), "მუშაობა").map { it.id })
    }

    // ----------------------------------------------------------------- distance

    @Test
    fun `bounded distance bails out instead of computing a large distance`() {
        assertEquals(0, FuzzySearch.boundedDistance("გამარჯობა", "გამარჯობა", 2) ?: -1)
        assertEquals(1, FuzzySearch.boundedDistance("კატა", "კატი", 2) ?: -1)
        assertNull(FuzzySearch.boundedDistance("კატა", "ძაღლი", 2))
        // Length difference alone can exceed the budget.
        assertNull(FuzzySearch.boundedDistance("ა", "აბგდე", 2))
    }
}
