package dev.anchildress1.vestige.ui.history

import org.junit.Assert.assertEquals
import org.junit.Test

class HistoryDurationFormatterTest {

    // pos — normal voice durations (clips cap at 30 s)

    @Test
    fun `1 second formats as 1s`() {
        assertEquals("1s", HistoryDurationFormatter.format(1_000L))
    }

    @Test
    fun `30 seconds formats as 30s`() {
        assertEquals("30s", HistoryDurationFormatter.format(30_000L))
    }

    @Test
    fun `15 seconds formats as 15s`() {
        assertEquals("15s", HistoryDurationFormatter.format(15_000L))
    }

    @Test
    fun `59 seconds stays in seconds instead of zero minutes`() {
        assertEquals("59s", HistoryDurationFormatter.format(59_000L))
    }

    @Test
    fun `62 seconds formats as 1m 02s`() {
        assertEquals("1m 02s", HistoryDurationFormatter.format(62_000L))
    }

    // neg — typed entries and zero-duration rows

    @Test
    fun `zero durationMs returns em dash`() {
        assertEquals("—", HistoryDurationFormatter.format(0L))
    }

    @Test
    fun `negative durationMs returns em dash`() {
        assertEquals("—", HistoryDurationFormatter.format(-1L))
    }

    // err — sub-second precision is truncated, not rounded

    @Test
    fun `999 ms truncates to 0s`() {
        assertEquals("0s", HistoryDurationFormatter.format(999L))
    }

    // edge — boundary values

    @Test
    fun `Long MAX_VALUE does not throw`() {
        val result = HistoryDurationFormatter.format(Long.MAX_VALUE)
        assert(result.isNotBlank()) { "Expected non-blank result for Long.MAX_VALUE, got: $result" }
    }
}
