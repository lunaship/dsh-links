package dev.deeplinks.native.util

import org.junit.Assert.assertEquals
import org.junit.Test

class TimeLabelsTest {
    @Test
    fun relativeTime_blankWhenMissing() {
        assertEquals("", relativeTime(0, now = 1_000_000))
        assertEquals("", relativeTime(-1, now = 1_000_000))
    }

    @Test
    fun relativeTime_buckets() {
        val now = 200_000_000L
        assertEquals(dev.deeplinks.core.L.justNowShort, relativeTime(now - 1_000, now))
        assertEquals(dev.deeplinks.core.L.minutesShort.format(3), relativeTime(now - 3 * 60_000, now))
        assertEquals(dev.deeplinks.core.L.hoursShort.format(2), relativeTime(now - 2 * 3_600_000, now))
        assertEquals(dev.deeplinks.core.L.daysShort.format(2), relativeTime(now - 2 * 86_400_000, now))
    }
}
