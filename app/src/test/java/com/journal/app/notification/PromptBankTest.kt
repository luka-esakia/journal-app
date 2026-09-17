package com.journal.app.notification

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class PromptBankTest {

    @Test
    fun `allPrompts is exactly the four categories`() {
        val expected = PromptBank.growthPrompts +
            PromptBank.creativityPrompts +
            PromptBank.focusPrompts +
            PromptBank.oddPrompts
        assertEquals(expected, PromptBank.allPrompts)
        assertEquals(14, PromptBank.allPrompts.size)
    }

    @Test
    fun `no duplicate prompts across categories`() {
        assertEquals(
            PromptBank.allPrompts.size,
            PromptBank.allPrompts.distinct().size
        )
    }

    @Test
    fun `every prompt is non-blank Georgian text`() {
        PromptBank.allPrompts.forEach { prompt ->
            assertTrue("blank prompt", prompt.isNotBlank())
            // Mkhedruli block: a prompt that slipped through as Latin would be a bad paste.
            assertTrue("not Georgian: $prompt", prompt.any { it in 'Ⴀ'..'ჿ' })
        }
    }

    @Test
    fun `reroll never returns the current prompt`() {
        PromptBank.allPrompts.forEach { current ->
            repeat(50) { seed ->
                assertNotEquals(current, PromptBank.randomOtherThan(current, Random(seed)))
            }
        }
    }

    @Test
    fun `reroll from null or unknown prompt still returns a bank prompt`() {
        assertTrue(PromptBank.randomOtherThan(null, Random(1)) in PromptBank.allPrompts)
        assertTrue(PromptBank.randomOtherThan("not in the bank", Random(1)) in PromptBank.allPrompts)
    }

    @Test
    fun `a day of slots draws distinct prompts`() {
        // 14 prompts vs a 12-slot maximum, so a full day should never repeat a question.
        val bag = PromptBank.allPrompts.shuffled(Random(7)).take(12)
        assertEquals(12, bag.distinct().size)
    }
}
