package dev.anchildress1.vestige.ui.onboarding

import androidx.compose.runtime.Immutable

/** Footer action (button label + click + enabled). Primary or optional secondary slot. */
@Immutable
internal data class OnboardingAction(val label: String, val onAction: () -> Unit, val enabled: Boolean = true)
