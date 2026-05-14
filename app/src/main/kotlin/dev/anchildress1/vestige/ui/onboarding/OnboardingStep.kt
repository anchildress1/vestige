package dev.anchildress1.vestige.ui.onboarding

/**
 * 5-step onboarding flow per `poc/screenshots/onboarding-*.png`. Replaces the original 8-step
 * sequence — `LocalExplainer`, `MicPermission`, `NotificationPermission`, and `TypedFallback`
 * are folded into the single [Wiring] screen, which renders all four "switches" as toggle
 * cards on one page.
 */
enum class OnboardingStep {
    PersonaPick,
    Wiring,
    WifiCheck,
    ModelDownload,
    Ready,
    ;

    fun next(): OnboardingStep? = entries.getOrNull(ordinal + 1)
    fun previous(): OnboardingStep? = entries.getOrNull(ordinal - 1)
}
