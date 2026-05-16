package dev.anchildress1.vestige

internal fun modelStatusBackTarget(origin: PostOnboardingScreen): PostOnboardingScreen = when (origin) {
    PostOnboardingScreen.Settings -> PostOnboardingScreen.Settings
    else -> PostOnboardingScreen.Capture
}
