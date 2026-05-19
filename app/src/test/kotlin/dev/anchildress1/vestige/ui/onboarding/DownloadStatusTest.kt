package dev.anchildress1.vestige.ui.onboarding

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadStatusTest {

    @Test
    fun `isStalled is false strictly under the 30s threshold`() {
        assertFalse(isStalled(lastProgressAtMs = 0L, nowMs = 29_999L))
    }

    @Test
    fun `isStalled is true exactly at and beyond the 30s threshold`() {
        assertTrue(isStalled(lastProgressAtMs = 0L, nowMs = 30_000L))
        assertTrue(isStalled(lastProgressAtMs = 0L, nowMs = 90_000L))
    }

    @Test
    fun `etaClock returns the unknown clock for null and negative input`() {
        assertEquals("--:--", etaClock(null))
        assertEquals("--:--", etaClock(-1L))
    }

    @Test
    fun `etaClock zero-pads seconds under a minute`() {
        assertEquals("00:00", etaClock(0L))
        assertEquals("00:05", etaClock(5L))
        assertEquals("00:59", etaClock(59L))
    }

    @Test
    fun `etaClock renders minutes and seconds`() {
        assertEquals("01:00", etaClock(60L))
        assertEquals("04:18", etaClock(4L * 60L + 18L))
        assertEquals("59:59", etaClock(59L * 60L + 59L))
    }

    @Test
    fun `etaClock keeps counting minutes past an hour`() {
        assertEquals("60:00", etaClock(3_600L))
        assertEquals("125:05", etaClock(125L * 60L + 5L))
    }
}
