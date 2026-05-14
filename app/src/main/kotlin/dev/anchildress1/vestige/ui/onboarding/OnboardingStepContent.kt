package dev.anchildress1.vestige.ui.onboarding

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import dev.anchildress1.vestige.R
import dev.anchildress1.vestige.model.ModelArtifactState
import dev.anchildress1.vestige.model.Persona

@Immutable
internal data class OnboardingStepState(
    val step: OnboardingStep,
    val persona: Persona,
    val micPermissionDenied: Boolean,
    val wifiConnected: Boolean,
    val modelState: ModelArtifactState,
    val micGranted: Boolean,
    val notifGranted: Boolean,
    val downloadMbps: Float? = null,
) {
    /** Count of switches that should read GREEN in the chrome counter. */
    val enabledCount: Int
        get() = listOf(
            true, // persona — always set (default Witness or whatever the user picked)
            modelState is ModelArtifactState.Complete,
            micGranted,
            notifGranted,
            true, // type fallback — always on
        ).count { it }
}

@Immutable
internal data class OnboardingStepCallbacks(
    val onPersonaChange: (Persona) -> Unit,
    val advance: () -> Unit,
    val onMicAllow: () -> Unit,
    val onNotificationAllow: () -> Unit,
    val onOpenWifiSettings: () -> Unit,
    val onComeBackLater: () -> Unit,
    val onOpenApp: () -> Unit,
    /** Wiring → ModelDownload drill-in. The download screen auto-unwinds back to Wiring. */
    val onOpenModelDownload: () -> Unit,
    /** ModelDownload → Wiring return path (fired automatically once state goes Complete). */
    val onDownloadReturn: () -> Unit,
    /** Wiring persona card → PersonaPick. Lets the user change their voice mid-flow. */
    val onChangePersona: () -> Unit,
)

@Composable
internal fun OnboardingStepContent(
    state: OnboardingStepState,
    callbacks: OnboardingStepCallbacks,
    @Suppress("UNUSED_PARAMETER") context: Context,
    modifier: Modifier,
) {
    when (state.step) {
        OnboardingStep.PersonaPick -> PersonaPickScreen(
            modifier = modifier,
            selected = state.persona,
            onSelect = callbacks.onPersonaChange,
            onContinue = callbacks.advance,
            enabledCount = state.enabledCount,
        )

        OnboardingStep.Wiring -> WiringHostScreen(
            modifier = modifier,
            state = state,
            callbacks = callbacks,
        )

        OnboardingStep.ModelDownload -> ModelDownloadPlaceholderScreen(
            modifier = modifier,
            modelState = state.modelState,
            downloadMbps = state.downloadMbps,
            wifiConnected = state.wifiConnected,
            enabledCount = state.enabledCount,
            // Auto-unwinds back to Wiring once Complete; the screen is intentionally not a stop.
            onContinue = callbacks.onDownloadReturn,
        )
    }
}

@Composable
private fun WiringHostScreen(modifier: Modifier, state: OnboardingStepState, callbacks: OnboardingStepCallbacks) {
    val switches = listOf(
        personaSwitch(persona = state.persona, onTap = callbacks.onChangePersona),
        localSwitch(
            modelState = state.modelState,
            wifiConnected = state.wifiConnected,
            onTap = callbacks.onOpenModelDownload,
        ),
        micSwitch(
            granted = state.micGranted,
            denied = state.micPermissionDenied,
            onAllow = callbacks.onMicAllow,
        ),
        notifSwitch(granted = state.notifGranted, onAllow = callbacks.onNotificationAllow),
        typedFallbackSwitch(),
    )
    val granted = switches.count { it.state == WiringSwitchState.Granted }
    val readyToAdvance = isWiringReadyToAdvance(state)
    OnboardingScaffold(
        enabledCount = state.enabledCount,
        modifier = modifier,
        rightStatus = "$granted / ${switches.size} LIVE",
        // Wiring is the terminal hub — Next opens the app once the model is on disk. Mic +
        // Notify stay optional per the design (granted, pending, or blocked all let the user
        // proceed; only `modelState.isReady` gates Next).
        primary = OnboardingAction(
            label = stringResource(id = R.string.onboarding_open_vestige),
            onAction = callbacks.onOpenApp,
            enabled = readyToAdvance,
        ),
        footerHelper = if (readyToAdvance) {
            null
        } else {
            stringResource(id = R.string.onboarding_wiring_waiting_on_model)
        },
    ) {
        WiringScreen(switches = switches)
    }
}

internal fun isWiringReadyToAdvance(state: OnboardingStepState): Boolean =
    state.modelState is ModelArtifactState.Complete

@Composable
private fun personaSwitch(persona: Persona, onTap: () -> Unit): WiringSwitch {
    val name = stringResource(id = personaNameRes(persona))
    return WiringSwitch(
        number = stringResource(id = R.string.onboarding_wiring_persona_number),
        title = stringResource(id = R.string.onboarding_wiring_persona_title, name.uppercase()),
        description = stringResource(id = R.string.onboarding_wiring_persona_desc, name),
        state = WiringSwitchState.Granted,
        onTap = onTap,
        role = Role.Button, // Navigation back to persona pick — not a toggle.
    )
}

private fun personaNameRes(persona: Persona): Int = when (persona) {
    Persona.WITNESS -> R.string.onboarding_persona_witness_name
    Persona.HARDASS -> R.string.onboarding_persona_hardass_name
    Persona.EDITOR -> R.string.onboarding_persona_editor_name
}

@Composable
private fun localSwitch(modelState: ModelArtifactState, wifiConnected: Boolean, onTap: () -> Unit): WiringSwitch {
    val (state, hint) = when {
        modelState is ModelArtifactState.Complete ->
            WiringSwitchState.Granted to null

        modelState is ModelArtifactState.Corrupt ->
            // Corrupt is retryable — let the user tap to re-download. "Network down" is the wrong
            // hint here because the artifact itself, not the network, is the blocker.
            WiringSwitchState.Blocked to stringResource(id = R.string.onboarding_wiring_local_corrupt_hint)

        !wifiConnected ->
            WiringSwitchState.Blocked to stringResource(id = R.string.onboarding_wiring_local_blocked_hint)

        modelState is ModelArtifactState.Partial ->
            WiringSwitchState.Pending to stringResource(id = R.string.onboarding_wiring_local_partial_hint)

        else -> // Absent — download hasn't started yet
            WiringSwitchState.Pending to stringResource(id = R.string.onboarding_wiring_local_absent_hint)
    }
    // Network-blocked tap would route into ModelDownload and start a 3.66 GB fetch on an invalid
    // network path; only Corrupt + Pending are tappable. Granted needs no tap.
    val tapEnabled = state == WiringSwitchState.Pending ||
        (state == WiringSwitchState.Blocked && modelState is ModelArtifactState.Corrupt)
    return WiringSwitch(
        number = stringResource(id = R.string.onboarding_wiring_local_number),
        title = stringResource(id = R.string.onboarding_wiring_local_title),
        description = stringResource(id = R.string.onboarding_wiring_local_desc),
        state = state,
        pendingHint = hint,
        onTap = if (tapEnabled) onTap else null,
        role = Role.Button, // Drill-in to ModelDownload — not a toggle.
    )
}

@Composable
private fun micSwitch(granted: Boolean, denied: Boolean, onAllow: () -> Unit): WiringSwitch {
    val state = when {
        granted -> WiringSwitchState.Granted
        denied -> WiringSwitchState.Blocked
        else -> WiringSwitchState.Pending
    }
    return WiringSwitch(
        number = stringResource(id = R.string.onboarding_wiring_mic_number),
        title = stringResource(id = R.string.onboarding_wiring_mic_title),
        description = stringResource(id = R.string.onboarding_wiring_mic_desc),
        state = state,
        pendingHint = when (state) {
            WiringSwitchState.Blocked -> stringResource(id = R.string.onboarding_wiring_mic_blocked_hint)
            WiringSwitchState.Pending -> stringResource(id = R.string.onboarding_wiring_mic_hint)
            else -> null
        },
        onTap = if (granted) null else onAllow,
    )
}

@Composable
private fun notifSwitch(granted: Boolean, onAllow: () -> Unit): WiringSwitch {
    val state = if (granted) WiringSwitchState.Granted else WiringSwitchState.Pending
    return WiringSwitch(
        number = stringResource(id = R.string.onboarding_wiring_notif_number),
        title = stringResource(id = R.string.onboarding_wiring_notif_title),
        description = stringResource(id = R.string.onboarding_wiring_notif_desc),
        state = state,
        pendingHint = if (granted) null else stringResource(id = R.string.onboarding_wiring_notif_hint),
        onTap = if (granted) null else onAllow,
    )
}

@Composable
private fun typedFallbackSwitch(): WiringSwitch = WiringSwitch(
    number = stringResource(id = R.string.onboarding_wiring_type_number),
    title = stringResource(id = R.string.onboarding_wiring_type_title),
    description = stringResource(id = R.string.onboarding_wiring_type_desc),
    state = WiringSwitchState.Granted,
)
