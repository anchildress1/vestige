package dev.anchildress1.vestige.ui.capture

import dev.anchildress1.vestige.AppContainer
import dev.anchildress1.vestige.model.PatternState
import java.time.Clock
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

internal fun deriveInitialReadiness(container: AppContainer): ModelReadiness {
    val store = container.mainModelArtifactStore
    val file = store.artifactFile
    return runCatching {
        deriveModelReadiness(
            fileExists = file.exists(),
            actualBytes = file.length(),
            expectedBytes = store.manifest.expectedByteSize,
        )
    }.getOrDefault(ModelReadiness.Loading)
}

internal fun deriveModelReadiness(fileExists: Boolean, actualBytes: Long, expectedBytes: Long): ModelReadiness =
    if (fileExists && actualBytes == expectedBytes) ModelReadiness.Ready else ModelReadiness.Loading

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

private val MONTH_DAY_FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern("MMM d", Locale.US)
private val TIME_FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern("h:mm a", Locale.US)
