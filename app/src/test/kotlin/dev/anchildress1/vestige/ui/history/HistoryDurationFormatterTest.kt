package dev.anchildress1.vestige.ui.history

import org.junit.Assert.assertEquals
import org.junit.Test

class HistoryDurationFormatterTest {

    // pos — normal voice durations

    @Test
    fun `1 second formats as 0m 01s`() {
        assertEquals("0m 01s", HistoryDurationFormatter.format(1_000L))
    }

    @Test
    fun `242 seconds formats as 4m 02s`() {
        assertEquals("4m 02s", HistoryDurationFormatter.format(242_000L))
    }

    @Test
    fun `60 seconds formats as 1m 00s`() {
        assertEquals("1m 00s", HistoryDurationFormatter.format(60_000L))
    }

    @Test
    fun `61 seconds formats as 1m 01s`() {
        assertEquals("1m 01s", HistoryDurationFormatter.format(61_000L))
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
    fun `999 ms is below 1 second and rounds down to 0m 00s`() {
        assertEquals("0m 00s", HistoryDurationFormatter.format(999L))
    }

    // edge — boundary values

    @Test
    fun `Long MAX_VALUE does not throw`() {
        val result = HistoryDurationFormatter.format(Long.MAX_VALUE)
        // Any non-throwing, non-blank result is acceptable.
        assert(result.isNotBlank()) { "Expected non-blank result for Long.MAX_VALUE, got: $result" }
    }

    @Test
    fun `seconds column is always zero-padded to two digits`() {
        // 9 seconds → "0m 09s", not "0m 9s"
        assertEquals("0m 09s", HistoryDurationFormatter.format(9_000L))
    }
}
