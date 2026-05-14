package dev.anchildress1.vestige.ui.onboarding

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import dev.anchildress1.vestige.R
import dev.anchildress1.vestige.model.ModelArtifactState
import dev.anchildress1.vestige.model.Persona
import dev.anchildress1.vestige.ui.theme.VestigeTheme

@Composable
internal fun LocalExplainerScreen(onContinue: () -> Unit, modifier: Modifier = Modifier) {
    OnboardingScaffold(
        modifier = modifier,
        header = stringResource(id = R.string.onboarding_local_header),
        primary = OnboardingAction(stringResource(id = R.string.onboarding_got_it), onContinue),
    ) {
        BodyParagraph(text = stringResource(id = R.string.onboarding_local_body))
        BodyParagraph(text = stringResource(id = R.string.onboarding_local_detail), dim = true)
    }
}

@Composable
internal fun MicPermissionScreen(
    showDeniedNotice: Boolean,
    onAllow: () -> Unit,
    onSkip: () -> Unit,
    modifier: Modifier = Modifier,
) {
    OnboardingScaffold(
        modifier = modifier,
        header = stringResource(id = R.string.onboarding_mic_header),
        primary = OnboardingAction(stringResource(id = R.string.onboarding_mic_allow), onAllow),
        secondary = OnboardingAction(stringResource(id = R.string.onboarding_mic_skip), onSkip),
    ) {
        BodyParagraph(text = stringResource(id = R.string.onboarding_mic_body))
        if (showDeniedNotice) {
            BodyParagraph(text = stringResource(id = R.string.onboarding_mic_denied))
        }
    }
}

@Composable
internal fun NotificationPermissionScreen(onAllow: () -> Unit, onSkip: () -> Unit, modifier: Modifier = Modifier) {
    OnboardingScaffold(
        modifier = modifier,
        header = stringResource(id = R.string.onboarding_notif_header),
        primary = OnboardingAction(stringResource(id = R.string.onboarding_notif_allow), onAllow),
        secondary = OnboardingAction(stringResource(id = R.string.onboarding_notif_skip), onSkip),
    ) {
        BodyParagraph(text = stringResource(id = R.string.onboarding_notif_body))
        // Skip consequence note — the app keeps reading entries only while foregrounded without
        // the status notification. Spec leans toward "no degraded copy on skip" but a quiet
        // operational note clears the surprise on the first long-running entry.
        BodyParagraph(text = stringResource(id = R.string.onboarding_notif_skip_note), dim = true)
    }
}

@Composable
internal fun TypedFallbackScreen(onContinue: () -> Unit, modifier: Modifier = Modifier) {
    OnboardingScaffold(
        modifier = modifier,
        header = stringResource(id = R.string.onboarding_type_header),
        primary = OnboardingAction(stringResource(id = R.string.onboarding_continue), onContinue),
    ) {
        BodyParagraph(text = stringResource(id = R.string.onboarding_type_body))
    }
}

@Composable
internal fun WifiCheckScreen(
    isWifiConnected: Boolean,
    onContinue: () -> Unit,
    onOpenWifiSettings: () -> Unit,
    onComeBackLater: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (isWifiConnected) {
        OnboardingScaffold(
            modifier = modifier,
            header = stringResource(id = R.string.onboarding_wifi_ok_header),
            primary = OnboardingAction(stringResource(id = R.string.onboarding_wifi_ok_action), onContinue),
        ) {
            BodyParagraph(text = stringResource(id = R.string.onboarding_wifi_ok_body))
        }
    } else {
        OnboardingScaffold(
            modifier = modifier,
            header = stringResource(id = R.string.onboarding_wifi_missing_header),
            primary = OnboardingAction(
                stringResource(id = R.string.onboarding_wifi_open_settings),
                onOpenWifiSettings,
            ),
            secondary = OnboardingAction(
                stringResource(id = R.string.onboarding_wifi_come_back),
                onComeBackLater,
            ),
        ) {
            BodyParagraph(text = stringResource(id = R.string.onboarding_wifi_missing_body))
        }
    }
}

@Composable
internal fun ModelDownloadPlaceholderScreen(
    modelState: ModelArtifactState,
    onContinue: () -> Unit,
    modifier: Modifier = Modifier,
) {
    OnboardingScaffold(
        modifier = modifier,
        header = stringResource(id = R.string.onboarding_download_header),
        primary = OnboardingAction(
            label = stringResource(id = R.string.onboarding_continue),
            onAction = onContinue,
            enabled = modelState.isReady,
        ),
    ) {
        BodyParagraph(text = stringResource(id = R.string.onboarding_download_body))
        ModelReadinessBanner(modelState = modelState)
    }
}

@Composable
internal fun ReadyScreen(persona: Persona, onOpenApp: () -> Unit, modifier: Modifier = Modifier) {
    val personaName = stringResource(id = personaNameRes(persona))
    OnboardingScaffold(
        modifier = modifier,
        header = stringResource(id = R.string.onboarding_ready_header),
        primary = OnboardingAction(stringResource(id = R.string.onboarding_ready_action), onOpenApp),
    ) {
        BodyParagraph(text = stringResource(id = R.string.onboarding_ready_body, personaName))
    }
}

@Composable
internal fun BodyParagraph(text: String, dim: Boolean = false) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = text,
            style = VestigeTheme.typography.p,
            color = if (dim) VestigeTheme.colors.dim else VestigeTheme.colors.ink,
        )
    }
}

private fun personaNameRes(persona: Persona): Int = when (persona) {
    Persona.WITNESS -> R.string.onboarding_persona_witness_name
    Persona.HARDASS -> R.string.onboarding_persona_hardass_name
    Persona.EDITOR -> R.string.onboarding_persona_editor_name
}
