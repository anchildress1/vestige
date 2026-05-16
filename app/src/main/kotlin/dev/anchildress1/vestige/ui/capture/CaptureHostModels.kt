package dev.anchildress1.vestige.ui.capture

import android.util.Log
import dev.anchildress1.vestige.AppContainer
import dev.anchildress1.vestige.model.ModelArtifactState
import dev.anchildress1.vestige.model.PatternState
import dev.anchildress1.vestige.ui.history.HistoryDurationFormatter
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
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

internal fun deriveStats(container: AppContainer): CaptureStats = deriveStatsFromInputs(
    kept = container.entryStore.countCompleted(),
    patterns = container.patternStore.findVisibleSortedByLastSeen().map {
        CapturePatternSummary(state = it.state, sourceCount = it.supportingEntries.size)
    },
)

internal fun deriveStatsFromInputs(kept: Long, patterns: List<CapturePatternSummary>): CaptureStats {
    val active = patterns.count { it.state == PatternState.ACTIVE }
    val hitsThisMonth = patterns.sumOf { it.sourceCount }
    return CaptureStats(kept = kept.toInt(), active = active, hitsThisMonth = hitsThisMonth, cloud = 0)
}

internal fun deriveMeta(clock: Clock, zoneId: ZoneId): CaptureMeta {
    val now = clock.instant().atZone(zoneId)
    return CaptureMeta(
        weekdayLabel = now.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.US).uppercase(Locale.US),
        monthDayLabel = now.format(MONTH_DAY_FORMATTER).uppercase(Locale.US),
        timeLabel = now.format(TIME_FORMATTER),
        dayNumber = 1,
        streakDays = 0,
    )
}

internal data class CapturePatternSummary(val state: PatternState, val sourceCount: Int)

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
private val MONTH_DAY_FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern("MMM d", Locale.US)
private val TIME_FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern("h:mm a", Locale.US)
