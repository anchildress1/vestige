package dev.anchildress1.vestige.ui.capture

import android.util.Log
import dev.anchildress1.vestige.AppContainer
import dev.anchildress1.vestige.model.ModelArtifactState
import dev.anchildress1.vestige.model.PatternState
import dev.anchildress1.vestige.ui.history.HistoryDurationFormatter
import dev.anchildress1.vestige.ui.patterns.traceBarHitsFromEntries
import java.time.Instant
import java.time.ZoneId
import java.time.format.TextStyle
import java.util.Locale

internal fun deriveInitialReadiness(container: AppContainer): ModelReadiness {
    val store = container.mainModelArtifactStore
    val file = store.artifactFile
    return runCatching {
        when {
            file.exists() && file.length() == store.manifest.expectedByteSize -> ModelReadiness.Ready
            file.exists() -> ModelReadiness.Paused
            else -> ModelReadiness.Loading
        }
    }.onFailure { Log.e(TAG, "Failed to probe model artifact at ${file.path}; defaulting to Loading", it) }
        .getOrDefault(ModelReadiness.Loading)
}

internal fun deriveModelReadiness(state: ModelArtifactState): ModelReadiness = when (state) {
    ModelArtifactState.Absent -> ModelReadiness.Loading
    ModelArtifactState.Complete -> ModelReadiness.Ready
    is ModelArtifactState.Corrupt -> ModelReadiness.Loading
    is ModelArtifactState.Partial -> ModelReadiness.Paused
}

/**
 * Idle patterns-peek payload. Null ⇒ no active patterns, so the idle screen shows the
 * empty-state line instead. [traceHits] is the union 30-day glyph across the active patterns.
 */
data class CapturePatternsPeek(val activeCount: Int, val names: List<String>, val traceHits: Set<Int>)

internal fun derivePatternsPeek(container: AppContainer): CapturePatternsPeek? {
    val active = container.patternStore.findVisibleSortedByLastSeen()
        .filter { it.state == PatternState.ACTIVE }
    if (active.isEmpty()) return null
    val supporting = active.flatMap { it.supportingEntries.toList() }
    return CapturePatternsPeek(
        activeCount = active.size,
        names = active.take(PEEK_NAME_LIMIT).map { it.title },
        traceHits = traceBarHitsFromEntries(supporting, System.currentTimeMillis()),
    )
}

private const val PEEK_NAME_LIMIT = 3

/** Derived metadata for the Capture footer's "Last entry" strip. Null hides the footer. */
data class LastEntryFooter(val monthLabel: String, val dayLabel: String, val durationLabel: String)

internal fun deriveLastEntryFooter(container: AppContainer, zoneId: ZoneId): LastEntryFooter? {
    val last = container.entryStore.lastCompleted() ?: return null
    val date = Instant.ofEpochMilli(last.timestampEpochMs).atZone(zoneId)
    return LastEntryFooter(
        monthLabel = date.month.getDisplayName(TextStyle.SHORT, Locale.US).uppercase(Locale.US),
        dayLabel = date.dayOfMonth.toString(),
        durationLabel = HistoryDurationFormatter.format(last.durationMs),
    )
}

private const val TAG = "VestigeCaptureHost"
