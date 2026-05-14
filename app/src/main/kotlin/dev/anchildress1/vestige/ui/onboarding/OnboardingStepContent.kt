package dev.anchildress1.vestige.ui.onboarding

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
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
)

@Immutable
internal data class OnboardingStepCallbacks(
    val onPersonaChange: (Persona) -> Unit,
    val advance: () -> Unit,
    val onMicAllow: () -> Unit,
    val onNotificationAllow: () -> Unit,
    val onOpenWifiSettings: () -> Unit,
    val onComeBackLater: () -> Unit,
    val onOpenApp: () -> Unit,
)

@Composable
internal fun OnboardingStepContent(
    state: OnboardingStepState,
    callbacks: OnboardingStepCallbacks,
    context: Context,
    modifier: Modifier,
) {
    when (state.step) {
        OnboardingStep.PersonaPick -> PersonaPickScreen(
            modifier = modifier,
            selected = state.persona,
            onSelect = callbacks.onPersonaChange,
            onContinue = callbacks.advance,
        )

        OnboardingStep.Wiring -> WiringHostScreen(
            modifier = modifier,
            context = context,
            callbacks = callbacks,
        )

        OnboardingStep.WifiCheck -> WifiCheckScreen(
            modifier = modifier,
            isWifiConnected = state.wifiConnected,
            onContinue = callbacks.advance,
            onOpenWifiSettings = callbacks.onOpenWifiSettings,
            onComeBackLater = callbacks.onComeBackLater,
        )

        OnboardingStep.ModelDownload -> ModelDownloadPlaceholderScreen(
            modifier = modifier,
            modelState = state.modelState,
            onContinue = callbacks.advance,
        )

        OnboardingStep.Ready -> ReadyScreen(
            modifier = modifier,
            persona = state.persona,
            onOpenApp = callbacks.onOpenApp,
        )
    }
}

@Composable
private fun WiringHostScreen(modifier: Modifier, context: Context, callbacks: OnboardingStepCallbacks) {
    val micGranted = hasRecordAudio(context)
    val notifGranted = hasNotificationPermission(context)
    val switches = listOf(
        WiringSwitch(
            number = stringResource(id = R.string.onboarding_wiring_local_number),
            title = stringResource(id = R.string.onboarding_wiring_local_title),
            description = stringResource(id = R.string.onboarding_wiring_local_desc),
            state = WiringSwitchState.AlwaysOn,
        ),
        WiringSwitch(
            number = stringResource(id = R.string.onboarding_wiring_mic_number),
            title = stringResource(id = R.string.onboarding_wiring_mic_title),
            description = stringResource(id = R.string.onboarding_wiring_mic_desc),
            state = if (micGranted) WiringSwitchState.Granted else WiringSwitchState.Pending,
            pendingHint = stringResource(id = R.string.onboarding_wiring_mic_hint),
            onTap = if (micGranted) null else callbacks.onMicAllow,
        ),
        WiringSwitch(
            number = stringResource(id = R.string.onboarding_wiring_notif_number),
            title = stringResource(id = R.string.onboarding_wiring_notif_title),
            description = stringResource(id = R.string.onboarding_wiring_notif_desc),
            state = if (notifGranted) WiringSwitchState.Granted else WiringSwitchState.Pending,
            pendingHint = stringResource(id = R.string.onboarding_wiring_notif_hint),
            onTap = if (notifGranted) null else callbacks.onNotificationAllow,
        ),
        WiringSwitch(
            number = stringResource(id = R.string.onboarding_wiring_type_number),
            title = stringResource(id = R.string.onboarding_wiring_type_title),
            description = stringResource(id = R.string.onboarding_wiring_type_desc),
            state = WiringSwitchState.AlwaysOn,
        ),
    )
    val pending = switches.count { it.state == WiringSwitchState.Pending }
    OnboardingScaffold(
        step = OnboardingStep.Wiring,
        modifier = modifier,
        rightStatus = "${switches.size - pending} / ${switches.size} LIVE",
        primary = OnboardingAction(stringResource(id = R.string.onboarding_wiring_next), callbacks.advance),
        footerHelper = if (pending == 0) {
            null
        } else {
            stringResource(id = R.string.onboarding_wiring_footer)
        },
    ) {
        WiringScreen(switches = switches)
    }
}
