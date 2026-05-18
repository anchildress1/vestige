package dev.anchildress1.vestige.ui.capture

import dev.anchildress1.vestige.model.ModelArtifactState
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Pos / neg / edge coverage for host-level capture derivation logic.
 *
 * Activity and Compose route wiring stay coverage-excluded; model readiness is a business-facing
 * screen input and stays tested here.
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
}
