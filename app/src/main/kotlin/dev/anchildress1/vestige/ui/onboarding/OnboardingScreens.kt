package dev.anchildress1.vestige.ui.onboarding

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import dev.anchildress1.vestige.R
import dev.anchildress1.vestige.model.ModelArtifactState
import dev.anchildress1.vestige.ui.components.StatItem
import dev.anchildress1.vestige.ui.components.StatRibbon
import dev.anchildress1.vestige.ui.theme.VestigeTheme

// Persona + Type are always enabled = 2. Adding Local (model) when Complete = 3.
private const val PREVIEW_ENABLED_WITHOUT_MODEL = 2
private const val PREVIEW_ENABLED_WITH_MODEL = 3

@Composable
@Suppress("LongParameterList") // Optional preview defaults — screen-host call site only passes 3.
internal fun ModelDownloadPlaceholderScreen(
    modelState: ModelArtifactState,
    onContinue: () -> Unit,
    modifier: Modifier = Modifier,
    downloadMbps: Float? = null,
    wifiConnected: Boolean = true,
    enabledCount: Int = if (modelState.isReady) PREVIEW_ENABLED_WITH_MODEL else PREVIEW_ENABLED_WITHOUT_MODEL,
) {
    OnboardingScaffold(
        enabledCount = enabledCount,
        modifier = modifier,
        rightStatus = if (modelState.isReady) "PULLED · DONE" else "PULLING · LIVE",
        primary = OnboardingAction(
            label = stringResource(id = R.string.onboarding_continue),
            onAction = onContinue,
            enabled = modelState.isReady,
        ),
    ) {
        ModelReadinessBanner(modelState = modelState)
        DownloadStatsRibbon(
            modelState = modelState,
            downloadMbps = downloadMbps,
            wifiConnected = wifiConnected,
        )
    }
}

@Composable
private fun DownloadStatsRibbon(modelState: ModelArtifactState, downloadMbps: Float?, wifiConnected: Boolean) {
    val isPartial = modelState is ModelArtifactState.Partial
    val mbpsValue = when {
        modelState.isReady -> "✓"
        !isPartial -> "—"
        downloadMbps == null -> "—"
        downloadMbps < 0.1f -> "0"
        downloadMbps < 10f -> "%.1f".format(downloadMbps)
        else -> downloadMbps.toInt().toString()
    }
    StatRibbon(
        items = listOf(
            StatItem(value = mbpsValue, label = "MB/S", color = VestigeTheme.colors.lime),
            StatItem(value = "1", label = "STREAM", color = VestigeTheme.colors.ink),
            StatItem(value = "0", label = "STALLS", color = VestigeTheme.colors.ink),
            StatItem(
                value = if (wifiConnected) "✓" else "✗",
                label = "WI-FI",
                color = if (wifiConnected) VestigeTheme.colors.lime else VestigeTheme.colors.coral,
            ),
        ),
    )
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
