package dev.anchildress1.vestige.ui.history

object HistoryDurationFormatter {

    /** Formats [durationMs] as `{n}s`. Zero/negative returns `"—"` for typed entries. */
    fun format(durationMs: Long): String {
        if (durationMs <= 0L) return "—"
        val totalSeconds = durationMs / 1_000L
        return "${totalSeconds}s"
    }
}
