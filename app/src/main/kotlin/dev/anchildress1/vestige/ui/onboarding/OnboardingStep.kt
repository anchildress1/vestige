package dev.anchildress1.vestige.ui.onboarding

/**
 * 3-screen flow. Wiring is the hub: it shows 5 switches (Persona, Local, Mic, Notify, Type).
 * Next is gated only on the model being downloaded — mic + notify are optional. Tapping Local
 * navigates forward to [ModelDownload]; the download screen auto-returns to Wiring when the
 * artifact verifies. There is no separate "Ready" screen — Wiring's Next is the open-the-app
 * action once the model is on disk.
 */
enum class OnboardingStep {
    PersonaPick,
    Wiring,
    ModelDownload,
    ;

    fun next(): OnboardingStep? = entries.getOrNull(ordinal + 1)
    fun previous(): OnboardingStep? = entries.getOrNull(ordinal - 1)
}

internal const val TOTAL_WIRING_SWITCHES = 5
