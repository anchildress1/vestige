package dev.anchildress1.vestige.ui.history

object HistoryDurationFormatter {

    /** Formats [durationMs] as `{n}s` under a minute, else `{m}m {ss}s`. */
    fun format(durationMs: Long): String {
        if (durationMs <= 0L) return "—"
        val totalSeconds = durationMs / 1_000L
        val minutes = totalSeconds / SECONDS_PER_MINUTE
        val seconds = totalSeconds % SECONDS_PER_MINUTE
        return if (minutes == 0L) "${totalSeconds}s" else "${minutes}m ${seconds.toString().padStart(2, '0')}s"
    }

    private const val SECONDS_PER_MINUTE = 60L
}
