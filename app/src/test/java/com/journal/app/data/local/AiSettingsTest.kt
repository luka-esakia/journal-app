package com.journal.app.data.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-data guarantees that the rest of the app leans on: the shipped defaults, the model slugs
 * the picker offers, and the canonical form of a hand-typed tag.
 */
class AiSettingsTest {

    // ------------------------------------------------------------- daily count

    @Test
    fun `a fresh install asks three times a day`() {
        assertEquals(3, NotificationConfig.DEFAULT_DAILY_COUNT)
        assertEquals(3, NotificationConfig().dailyCount)
    }

    @Test
    fun `the default count sits inside the allowed range`() {
        assertTrue(
            NotificationConfig.DEFAULT_DAILY_COUNT in
                NotificationConfig.MIN_DAILY_COUNT..NotificationConfig.MAX_DAILY_COUNT
        )
    }

    // ----------------------------------------------------------- model routing

    @Test
    fun `tagging and reflection default to different models`() {
        val settings = AiSettings()
        assertEquals("openai/gpt-4o-mini", settings.tagModel)
        assertEquals("anthropic/claude-haiku-4.5", settings.reflectionModel)
        assertNotEquals(settings.tagModel, settings.reflectionModel)
    }

    @Test
    fun `each task routes to its configured slug`() {
        val settings = AiSettings(tagModel = "a/fast", reflectionModel = "b/deep")

        assertEquals("a/fast", settings.modelFor(AiTask.TAGS))
        // Prompt generation is the same shape of work as tagging, so it shares that slot.
        assertEquals("a/fast", settings.modelFor(AiTask.PROMPT))
        assertEquals("b/deep", settings.modelFor(AiTask.REFLECTION))
    }

    @Test
    fun `every curated slug is a provider-qualified OpenRouter id`() {
        AiSettings.CURATED_MODELS.forEach { option ->
            assertTrue(option.slug, option.slug.count { it == '/' } == 1)
            assertTrue(option.slug, !option.slug.contains(':'))
            assertTrue(option.label, option.label.isNotBlank())
        }
    }

    @Test
    fun `the curated list matches the verified catalogue`() {
        assertEquals(
            listOf(
                "anthropic/claude-haiku-4.5",
                "google/gemini-2.5-flash",
                "openai/gpt-5-mini",
                "openai/gpt-4o-mini",
                // Mistral Small 4, replacing the superseded 3.2-24b-instruct entry.
                "mistralai/mistral-small-2603"
            ),
            AiSettings.CURATED_MODELS.map { it.slug }
        )
    }

    @Test
    fun `both defaults are slugs the picker actually offers`() {
        val offered = AiSettings.CURATED_MODELS.map { it.slug }
        assertTrue(AiSettings.DEFAULT_TAG_MODEL in offered)
        assertTrue(AiSettings.DEFAULT_REFLECTION_MODEL in offered)
    }

    @Test
    fun `a custom slug joins the list without displacing the curated ones`() {
        val settings = AiSettings(customModels = listOf("my/model"))
        val slugs = settings.availableModels().map { it.slug }

        assertEquals(AiSettings.CURATED_MODELS.size + 1, slugs.size)
        assertEquals("my/model", slugs.last())
    }

    // ------------------------------------------------------- tag normalisation

    @Test
    fun `a hand-typed tag is normalised the same way a generated one is`() {
        assertEquals("#ძილი", JournalEntry.normalizeTag("ძილი"))
        assertEquals("#ძილი", JournalEntry.normalizeTag("  #ძილი  "))
        assertEquals("#ძილი", JournalEntry.normalizeTag("##ძილი"))
        assertEquals("#ცუდი_ძილი", JournalEntry.normalizeTag("ცუდი ძილი"))
    }

    @Test
    fun `a comma can never sneak into a tag and split it in two`() {
        // The comma is the storage separator; a tag containing one would come back as two.
        val tag = JournalEntry.normalizeTag("ძილი,მუშაობა")
        assertEquals("#ძილი_მუშაობა", tag)
        assertEquals(1, JournalEntry(content = "x", createdAt = 1, tags = tag!!).tagList().size)
    }

    @Test
    fun `nothing usable yields no tag`() {
        assertNull(JournalEntry.normalizeTag(""))
        assertNull(JournalEntry.normalizeTag("   "))
        assertNull(JournalEntry.normalizeTag("#"))
        assertNull(JournalEntry.normalizeTag("---"))
    }

    @Test
    fun `an over-long tag is cut rather than stored whole`() {
        val long = JournalEntry.normalizeTag("ა".repeat(80))!!
        // The cap applies to the text; the '#' is added afterwards.
        assertEquals(JournalEntry.MAX_TAG_LENGTH + 1, long.length)
    }
}
