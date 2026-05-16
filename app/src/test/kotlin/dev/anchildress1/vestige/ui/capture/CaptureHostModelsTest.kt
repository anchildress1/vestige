package dev.anchildress1.vestige.ui.capture

import dev.anchildress1.vestige.model.ModelArtifactState
import dev.anchildress1.vestige.model.PatternState
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneId

/**
 * Pos / neg / edge coverage for host-level capture derivation logic.
 *
 * Activity and Compose route wiring stay coverage-excluded; model readiness, stats, and date
 * metadata are business-facing screen inputs and stay tested here.
 */
class CaptureHostModelsTest {
    @Test
    fun `deriveModelReadiness maps artifact states to shell readiness`() {
        assertEquals(
            ModelReadiness.Ready,
            deriveModelReadiness(ModelArtifactState.Complete),
        )
        assertEquals(
            ModelReadiness.Loading,
            deriveModelReadiness(ModelArtifactState.Absent),
        )
        assertEquals(
            ModelReadiness.Paused,
            deriveModelReadiness(ModelArtifactState.Partial(currentBytes = 41L, expectedBytes = 42L)),
        )
        assertEquals(
            ModelReadiness.Loading,
            deriveModelReadiness(ModelArtifactState.Corrupt(expectedSha256 = "expected", actualSha256 = "actual")),
        )
    }

    @Test
    fun `deriveStatsFromInputs counts active patterns and source hits`() {
        val stats = deriveStatsFromInputs(
            kept = 7L,
            patterns = listOf(
                CapturePatternSummary(state = PatternState.ACTIVE, sourceCount = 2),
                CapturePatternSummary(state = PatternState.ACTIVE, sourceCount = 3),
                CapturePatternSummary(state = PatternState.SNOOZED, sourceCount = 5),
            ),
        )

        assertEquals(CaptureStats(kept = 7, active = 2, hitsThisMonth = 10, cloud = 0), stats)
    }

    @Test
    fun `deriveStatsFromInputs handles empty pattern list`() {
        val stats = deriveStatsFromInputs(kept = 0L, patterns = emptyList())

        assertEquals(CaptureStats(kept = 0, active = 0, hitsThisMonth = 0, cloud = 0), stats)
    }

    @Test
    fun `deriveMeta formats fixed clock in supplied zone`() {
        val clock = Clock.fixed(Instant.parse("2026-05-14T13:41:00Z"), ZoneId.of("UTC"))

        val meta = deriveMeta(clock = clock, zoneId = ZoneId.of("America/New_York"))

        assertEquals(
            CaptureMeta(
                weekdayLabel = "THU",
                monthDayLabel = "MAY 14",
                timeLabel = "9:41 AM",
                dayNumber = 1,
                streakDays = 0,
            ),
            meta,
        )
    }
}
