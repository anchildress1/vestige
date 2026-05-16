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
    fun `formatEta returns the unknown dash for null and negative input`() {
        assertEquals("—", formatEta(null))
        assertEquals("—", formatEta(-1L))
    }

    @Test
    fun `formatEta renders seconds under a minute`() {
        assertEquals("~0s", formatEta(0L))
        assertEquals("~45s", formatEta(45L))
        assertEquals("~59s", formatEta(59L))
    }

    @Test
    fun `formatEta renders whole minutes between one minute and one hour`() {
        assertEquals("~1 min", formatEta(60L))
        assertEquals("~12 min", formatEta(12L * 60L + 30L))
        assertEquals("~59 min", formatEta(59L * 60L))
    }

    @Test
    fun `formatEta renders hours and minutes at and beyond one hour`() {
        assertEquals("~1h 0m", formatEta(3_600L))
        assertEquals("~2h 5m", formatEta(2L * 3_600L + 5L * 60L))
    }
}
