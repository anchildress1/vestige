package dev.anchildress1.vestige.ui.history

private const val SNIPPET_MAX = 80

/** Immutable UI projection of a single history row. Equality-friendly — safe as LazyColumn key. */
data class HistorySummary(
    val id: Long,
    val timestampEpochMs: Long,
    val templateLabel: String?,
    val snippet: String,
    val durationMs: Long,
) {
    companion object {
        fun from(
            id: Long,
            timestampEpochMs: Long,
            templateLabelSerial: String?,
            entryText: String,
            durationMs: Long,
        ): HistorySummary = HistorySummary(
            id = id,
            timestampEpochMs = timestampEpochMs,
            templateLabel = templateLabelSerial,
            snippet = entryText.trim().take(SNIPPET_MAX),
            durationMs = durationMs,
        )
    }
}
