package dev.anchildress1.vestige.ui.onboarding

/** Sequential onboarding state — order matches `docs/ux-copy.md` §Onboarding. */
enum class OnboardingStep {
    PersonaPick,
    LocalExplainer,
    MicPermission,
    NotificationPermission,
    TypedFallback,
    WifiCheck,
    ModelDownload,
    Ready,
    ;

    fun next(): OnboardingStep? = entries.getOrNull(ordinal + 1)
    fun previous(): OnboardingStep? = entries.getOrNull(ordinal - 1)
}
