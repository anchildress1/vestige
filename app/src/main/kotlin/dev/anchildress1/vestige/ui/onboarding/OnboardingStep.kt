package dev.anchildress1.vestige.ui.onboarding

/**
 * Sequential onboarding state. Eight screens per `docs/ux-copy.md` §Onboarding.
 * Order is hard-coded; `next` / `previous` advance the gate. Final step is [Ready], whose
 * primary action marks onboarding complete via [OnboardingPrefs.markComplete].
 *
 * Screen 6 (Download) hands off to the model-download UX in Story 4.3 — the screen here is the
 * onboarding-side trampoline only.
 */
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
