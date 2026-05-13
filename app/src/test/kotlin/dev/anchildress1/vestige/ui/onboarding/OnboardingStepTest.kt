package dev.anchildress1.vestige.ui.onboarding

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class OnboardingStepTest {

    @Test
    fun `step order matches the locked 8-screen ux-copy sequence`() {
        val expected = listOf(
            OnboardingStep.PersonaPick,
            OnboardingStep.LocalExplainer,
            OnboardingStep.MicPermission,
            OnboardingStep.NotificationPermission,
            OnboardingStep.TypedFallback,
            OnboardingStep.WifiCheck,
            OnboardingStep.ModelDownload,
            OnboardingStep.Ready,
        )
        assertEquals(expected, OnboardingStep.entries.toList())
    }

    @Test
    fun `next walks forward and stops past Ready`() {
        assertEquals(OnboardingStep.LocalExplainer, OnboardingStep.PersonaPick.next())
        assertEquals(OnboardingStep.Ready, OnboardingStep.ModelDownload.next())
        assertNull(OnboardingStep.Ready.next())
    }

    @Test
    fun `previous walks back and stops before PersonaPick`() {
        assertNull(OnboardingStep.PersonaPick.previous())
        assertEquals(OnboardingStep.PersonaPick, OnboardingStep.LocalExplainer.previous())
        assertEquals(OnboardingStep.ModelDownload, OnboardingStep.Ready.previous())
    }
}
