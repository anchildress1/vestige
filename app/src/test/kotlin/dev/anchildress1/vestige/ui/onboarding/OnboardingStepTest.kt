package dev.anchildress1.vestige.ui.onboarding

import dev.anchildress1.vestige.model.ModelArtifactState
import dev.anchildress1.vestige.model.Persona
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class OnboardingStepTest {

    @Test
    fun `step order matches the 3-screen hub flow`() {
        val expected = listOf(
            OnboardingStep.PersonaPick,
            OnboardingStep.Wiring,
            OnboardingStep.ModelDownload,
        )
        assertEquals(expected, OnboardingStep.entries.toList())
    }

    @Test
    fun `next walks forward and stops past ModelDownload`() {
        assertEquals(OnboardingStep.Wiring, OnboardingStep.PersonaPick.next())
        assertEquals(OnboardingStep.ModelDownload, OnboardingStep.Wiring.next())
        assertNull(OnboardingStep.ModelDownload.next())
    }

    @Test
    fun `previous walks back and stops before PersonaPick`() {
        assertNull(OnboardingStep.PersonaPick.previous())
        assertEquals(OnboardingStep.PersonaPick, OnboardingStep.Wiring.previous())
        assertEquals(OnboardingStep.Wiring, OnboardingStep.ModelDownload.previous())
    }

    @Test
    fun `wiring readiness only depends on local model completion`() {
        val modelReady = OnboardingStepState(
            step = OnboardingStep.Wiring,
            persona = Persona.WITNESS,
            micPermissionDenied = true,
            wifiConnected = false,
            modelState = ModelArtifactState.Complete,
            micGranted = false,
            notifGranted = false,
        )
        val modelMissing = modelReady.copy(modelState = ModelArtifactState.Absent)

        assertTrue(isWiringReadyToAdvance(modelReady))
        assertFalse(isWiringReadyToAdvance(modelMissing))
    }
}
