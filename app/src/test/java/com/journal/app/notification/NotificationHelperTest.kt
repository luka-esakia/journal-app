package com.journal.app.notification

import com.journal.app.data.local.NotificationConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * Guards the two promises of the spaced-random engine: exactly one prompt per chunk, and no
 * clustering. These are pure-Kotlin assertions — no Android framework involved.
 */
class NotificationHelperTest {

    @Test
    fun `produces exactly one time per chunk`() {
        val start = 10 * 60
        val end = 22 * 60
        val count = 5

        val minutes = NotificationHelper.calculateSpacedRandomMinutes(
            startMinuteOfDay = start,
            endMinuteOfDay = end,
            count = count,
            random = Random(42)
        )

        assertEquals(count, minutes.size)

        val chunk = (end - start) / count
        minutes.forEachIndexed { index, minute ->
            val chunkStart = start + index * chunk
            val chunkEnd = chunkStart + chunk
            assertTrue(
                "slot $index ($minute) escaped chunk [$chunkStart, $chunkEnd]",
                minute in chunkStart..chunkEnd
            )
        }
    }

    @Test
    fun `times are strictly increasing and never cluster`() {
        repeat(200) { seed ->
            val minutes = NotificationHelper.calculateSpacedRandomMinutes(
                startMinuteOfDay = 9 * 60,
                endMinuteOfDay = 23 * 60,
                count = 8,
                random = Random(seed)
            )
            minutes.zipWithNext { a, b ->
                assertTrue("not increasing: $a -> $b", b > a)
                assertTrue(
                    "clustered: $a -> $b (seed $seed)",
                    b - a >= NotificationHelper.MIN_GAP_MINUTES
                )
            }
        }
    }

    @Test
    fun `stays inside the requested window`() {
        val start = 8 * 60
        val end = 20 * 60
        val minutes = NotificationHelper.calculateSpacedRandomMinutes(
            startMinuteOfDay = start,
            endMinuteOfDay = end,
            count = 6,
            random = Random(7)
        )
        assertTrue(minutes.first() >= start)
        assertTrue(minutes.last() <= end)
    }

    @Test
    fun `window wrapping past midnight is supported`() {
        val minutes = NotificationHelper.calculateSpacedRandomMinutes(
            startMinuteOfDay = 22 * 60,
            endMinuteOfDay = 2 * 60,
            count = 3,
            random = Random(11)
        )
        assertEquals(3, minutes.size)
        // A wrapping window keeps counting past 1440 so the epoch conversion lands on the next day.
        assertTrue(minutes.last() > NotificationConfig.MINUTES_PER_DAY)
    }

    @Test
    fun `zero or negative counts produce nothing`() {
        assertTrue(
            NotificationHelper.calculateSpacedRandomMinutes(600, 1320, 0, Random(1)).isEmpty()
        )
        assertTrue(
            NotificationHelper.calculateSpacedRandomMinutes(600, 1320, -3, Random(1)).isEmpty()
        )
    }

    @Test
    fun `epoch conversion is anchored to the supplied midnight`() {
        val midnight = 1_700_000_000_000L
        val times = NotificationHelper.calculateSpacedRandomTimes(
            startMinuteOfDay = 10 * 60,
            endMinuteOfDay = 22 * 60,
            count = 4,
            dayStartMillis = midnight,
            random = Random(3)
        )
        assertEquals(4, times.size)
        times.forEach { trigger ->
            val offsetMinutes = (trigger - midnight) / 60_000L
            assertTrue(offsetMinutes in (10 * 60).toLong()..(22 * 60).toLong())
        }
    }
}
