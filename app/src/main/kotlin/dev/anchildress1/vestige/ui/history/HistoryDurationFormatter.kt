package dev.anchildress1.vestige.ui.history

object HistoryDurationFormatter {

    /**
     * Formats [durationMs] as `{m}m {ss}s` (seconds zero-padded).
     * Zero duration returns `"—"` — used for typed entries and pre-duration rows.
     */
    fun format(durationMs: Long): String {
        if (durationMs <= 0L) return "—"
        val totalSeconds = durationMs / 1_000L
        val minutes = totalSeconds / 60L
        val seconds = totalSeconds % 60L
        return "${minutes}m ${seconds.toString().padStart(2, '0')}s"
    }
}
