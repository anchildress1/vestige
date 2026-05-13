package dev.anchildress1.vestige.ui.onboarding

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.anchildress1.vestige.R
import dev.anchildress1.vestige.model.Persona
import dev.anchildress1.vestige.ui.components.Pill
import dev.anchildress1.vestige.ui.theme.VestigeTheme

@Composable
internal fun LocalExplainerScreen(onContinue: () -> Unit, modifier: Modifier = Modifier) {
    OnboardingScaffold(
        modifier = modifier,
        header = stringResource(id = R.string.onboarding_local_header),
        primaryActionLabel = stringResource(id = R.string.onboarding_got_it),
        onPrimary = onContinue,
    ) {
        BodyParagraph(text = stringResource(id = R.string.onboarding_local_body))
        BodyParagraph(
            text = stringResource(id = R.string.onboarding_local_detail),
            dim = true,
        )
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
        primaryActionLabel = stringResource(id = R.string.onboarding_mic_allow),
        onPrimary = onAllow,
        secondaryActionLabel = stringResource(id = R.string.onboarding_mic_skip),
        onSecondary = onSkip,
    ) {
        BodyParagraph(text = stringResource(id = R.string.onboarding_mic_body))
        if (showDeniedNotice) {
            BodyParagraph(
                text = stringResource(id = R.string.onboarding_mic_denied),
                dim = false,
            )
        }
    }
}

@Composable
internal fun NotificationPermissionScreen(onAllow: () -> Unit, onSkip: () -> Unit, modifier: Modifier = Modifier) {
    OnboardingScaffold(
        modifier = modifier,
        header = stringResource(id = R.string.onboarding_notif_header),
        primaryActionLabel = stringResource(id = R.string.onboarding_notif_allow),
        onPrimary = onAllow,
        secondaryActionLabel = stringResource(id = R.string.onboarding_notif_skip),
        onSecondary = onSkip,
    ) {
        BodyParagraph(text = stringResource(id = R.string.onboarding_notif_body))
        // Skip consequence note — the app keeps reading entries only while foregrounded
        // without the status notification. Spec leans toward "no degraded copy on skip" but
        // a quiet operational note clears the surprise on the first long-running entry.
        BodyParagraph(text = stringResource(id = R.string.onboarding_notif_skip_note), dim = true)
    }
}

@Composable
internal fun TypedFallbackScreen(onContinue: () -> Unit, modifier: Modifier = Modifier) {
    OnboardingScaffold(
        modifier = modifier,
        header = stringResource(id = R.string.onboarding_type_header),
        primaryActionLabel = stringResource(id = R.string.onboarding_continue),
        onPrimary = onContinue,
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
            primaryActionLabel = stringResource(id = R.string.onboarding_wifi_ok_action),
            onPrimary = onContinue,
        ) {
            BodyParagraph(text = stringResource(id = R.string.onboarding_wifi_ok_body))
        }
    } else {
        OnboardingScaffold(
            modifier = modifier,
            header = stringResource(id = R.string.onboarding_wifi_missing_header),
            primaryActionLabel = stringResource(id = R.string.onboarding_wifi_open_settings),
            onPrimary = onOpenWifiSettings,
            secondaryActionLabel = stringResource(id = R.string.onboarding_wifi_come_back),
            onSecondary = onComeBackLater,
        ) {
            BodyParagraph(text = stringResource(id = R.string.onboarding_wifi_missing_body))
        }
    }
}

@Composable
internal fun ModelDownloadPlaceholderScreen(
    modelReady: Boolean,
    onContinue: () -> Unit,
    modifier: Modifier = Modifier,
) {
    OnboardingScaffold(
        modifier = modifier,
        header = stringResource(id = R.string.onboarding_download_header),
        primaryActionLabel = stringResource(id = R.string.onboarding_continue),
        onPrimary = onContinue,
        primaryEnabled = modelReady,
    ) {
        BodyParagraph(text = stringResource(id = R.string.onboarding_download_body))
        ModelReadinessBanner(modelReady = modelReady)
    }
}

@Composable
private fun ModelReadinessBanner(modelReady: Boolean) {
    val colors = VestigeTheme.colors
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (modelReady) {
                Pill(
                    text = stringResource(id = R.string.onboarding_download_ready_pill),
                    color = colors.lime,
                    dot = true,
                    blink = false,
                )
            } else {
                // Coral + blinking dot is the same "live work in progress" semantic as the
                // capture-screen ON AIR pill (ADR-011 §"Accent system"). Reuses the shared
                // primitive so the loading state stays consistent across the app.
                Pill(
                    text = stringResource(id = R.string.onboarding_download_loading_pill),
                    color = colors.coral,
                    dot = true,
                    blink = true,
                )
            }
        }
        if (!modelReady) {
            BodyParagraph(
                text = stringResource(id = R.string.onboarding_download_loading_note),
                dim = true,
            )
        }
    }
}

@Composable
internal fun ReadyScreen(persona: Persona, onOpenApp: () -> Unit, modifier: Modifier = Modifier) {
    val personaName = stringResource(id = personaNameRes(persona))
    OnboardingScaffold(
        modifier = modifier,
        header = stringResource(id = R.string.onboarding_ready_header),
        primaryActionLabel = stringResource(id = R.string.onboarding_ready_action),
        onPrimary = onOpenApp,
    ) {
        BodyParagraph(text = stringResource(id = R.string.onboarding_ready_body, personaName))
    }
}

@Composable
private fun BodyParagraph(text: String, dim: Boolean = false) {
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
