package dev.anchildress1.vestige.ui.onboarding

/**
 * 4-screen flow. Wiring is the hub: it shows 5 switches (Persona, Local, Mic, Notify, Type)
 * and is gated on all 5 being green before advancing to Ready. Tapping the Local switch
 * navigates forward to [ModelDownload], whose Continue action returns to [Wiring]. The "X OF 05"
 * chrome counter is the enabled-switch tally, not the screen ordinal.
 */
enum class OnboardingStep {
    PersonaPick,
    Wiring,
    ModelDownload,
    Ready,
    ;

    fun next(): OnboardingStep? = entries.getOrNull(ordinal + 1)
    fun previous(): OnboardingStep? = entries.getOrNull(ordinal - 1)
}

internal const val TOTAL_WIRING_SWITCHES = 5
