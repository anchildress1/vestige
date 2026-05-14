package dev.anchildress1.vestige.ui.onboarding

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.anchildress1.vestige.R
import dev.anchildress1.vestige.model.ModelArtifactState
import dev.anchildress1.vestige.model.Persona
import dev.anchildress1.vestige.ui.components.EyebrowE
import dev.anchildress1.vestige.ui.components.StatItem
import dev.anchildress1.vestige.ui.components.StatRibbon
import dev.anchildress1.vestige.ui.components.VestigeListCard
import dev.anchildress1.vestige.ui.theme.VestigeTheme

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
            step = OnboardingStep.WifiCheck,
            modifier = modifier,
            rightStatus = "WI-FI · GOOD",
            primary = OnboardingAction(stringResource(id = R.string.onboarding_wifi_ok_action), onContinue),
            secondary = OnboardingAction(stringResource(id = R.string.onboarding_wifi_come_back), onComeBackLater),
        ) {
            EyebrowE(text = stringResource(id = R.string.onboarding_wifi_ok_eyebrow))
            OnboardingHeadline(text = stringResource(id = R.string.onboarding_wifi_ok_header))
            BodyParagraph(text = stringResource(id = R.string.onboarding_wifi_ok_body))
            ArtifactPackageCard()
        }
    } else {
        OnboardingScaffold(
            step = OnboardingStep.WifiCheck,
            modifier = modifier,
            rightStatus = "WI-FI · DOWN",
            primary = OnboardingAction(
                stringResource(id = R.string.onboarding_wifi_open_settings),
                onOpenWifiSettings,
            ),
            secondary = OnboardingAction(
                stringResource(id = R.string.onboarding_wifi_come_back),
                onComeBackLater,
            ),
        ) {
            EyebrowE(text = stringResource(id = R.string.onboarding_wifi_missing_eyebrow))
            OnboardingHeadline(text = stringResource(id = R.string.onboarding_wifi_missing_header))
            BodyParagraph(text = stringResource(id = R.string.onboarding_wifi_missing_body))
        }
    }
}

@Composable
private fun ArtifactPackageCard() {
    val colors = VestigeTheme.colors
    VestigeListCard(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                EyebrowE(text = "PACKAGE")
                EyebrowE(text = "VERIFIED")
            }
            Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(text = "3.66", style = VestigeTheme.typography.displayBig)
                Text(
                    text = "GB",
                    style = VestigeTheme.typography.title,
                    color = colors.dim,
                    modifier = Modifier,
                )
            }
            EyebrowE(text = "GEMMA 4 E4B · INT4 · ON-DEVICE")
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
        step = OnboardingStep.ModelDownload,
        modifier = modifier,
        rightStatus = if (modelState.isReady) "PULLED · DONE" else "PULLING · LIVE",
        primary = OnboardingAction(
            label = stringResource(id = R.string.onboarding_continue),
            onAction = onContinue,
            enabled = modelState.isReady,
        ),
    ) {
        ModelReadinessBanner(modelState = modelState)
        DownloadStatsRibbon(modelState = modelState)
    }
}

@Composable
private fun DownloadStatsRibbon(modelState: ModelArtifactState) {
    val isPartial = modelState is ModelArtifactState.Partial
    StatRibbon(
        items = listOf(
            StatItem(
                value = if (isPartial) "—" else "0",
                label = "MB/S",
                color = VestigeTheme.colors.lime,
            ),
            StatItem(value = "1", label = "STREAM", color = VestigeTheme.colors.ink),
            StatItem(value = "0", label = "STALLS", color = VestigeTheme.colors.ink),
            StatItem(value = "✓", label = "WI-FI", color = VestigeTheme.colors.lime),
        ),
    )
}

@Composable
internal fun ReadyScreen(persona: Persona, onOpenApp: () -> Unit, modifier: Modifier = Modifier) {
    val personaName = stringResource(id = personaNameRes(persona))
    OnboardingScaffold(
        step = OnboardingStep.Ready,
        modifier = modifier,
        rightStatus = "DAY 00",
        primary = OnboardingAction(stringResource(id = R.string.onboarding_ready_action), onOpenApp),
        footerHelper = stringResource(id = R.string.onboarding_ready_footer),
    ) {
        EyebrowE(text = stringResource(id = R.string.onboarding_ready_eyebrow, personaName.uppercase()))
        OnboardingHeadline(text = stringResource(id = R.string.onboarding_ready_header))
        BodyParagraph(text = stringResource(id = R.string.onboarding_ready_body, personaName))
        ReadyScoreboard()
        ReadyFirstPromptCard()
    }
}

@Composable
private fun ReadyScoreboard() {
    val colors = VestigeTheme.colors
    Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
        EyebrowE(text = "HOW THIS WORKS")
        StatRibbon(
            items = listOf(
                StatItem(value = "30s", label = "CHUNKS", color = colors.coral),
                StatItem(value = "10", label = "TO PATTERN", color = colors.lime),
                StatItem(value = "0", label = "CLOUD", color = colors.ink),
            ),
        )
    }
}

@Composable
private fun ReadyFirstPromptCard() {
    val colors = VestigeTheme.colors
    Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
        EyebrowE(text = "FIRST PROMPT · WAITING")
        VestigeListCard(
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                EyebrowE(text = "PROMPT 01")
                Text(
                    text = stringResource(id = R.string.onboarding_ready_first_prompt),
                    style = VestigeTheme.typography.h1,
                    color = colors.lime,
                )
                EyebrowE(text = "TAP REC · TALK 30S · DONE")
            }
        }
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
