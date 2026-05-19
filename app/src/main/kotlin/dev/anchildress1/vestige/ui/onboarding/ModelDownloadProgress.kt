package dev.anchildress1.vestige.ui.onboarding

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.anchildress1.vestige.R
import dev.anchildress1.vestige.model.ModelArtifactState
import dev.anchildress1.vestige.ui.components.ModelDownloadCard
import dev.anchildress1.vestige.ui.components.ModelDownloadProgress
import dev.anchildress1.vestige.ui.components.Pill
import dev.anchildress1.vestige.ui.theme.VestigeTheme

private const val BAND_RULE_WIDTH_DP = 3
private const val BAND_PAD_DP = 12

@Composable
internal fun ModelReadinessBanner(
    modelState: ModelArtifactState,
    downloadMbps: Float?,
    downloadStatus: DownloadStatus,
    wifiConnected: Boolean,
) {
    if (modelState.isReady) {
        ModelReadyBanner()
    } else {
        val partial = modelState as? ModelArtifactState.Partial
        ModelDownloadCard(
            progress = ModelDownloadProgress(
                fraction = modelState.downloadFraction,
                currentBytes = partial?.currentBytes,
                expectedBytes = partial?.expectedBytes,
                etaSeconds = downloadStatus.etaSeconds,
                mbps = downloadMbps,
            ),
            wifiConnected = wifiConnected,
        )
        DownloadPhaseBand(phase = downloadStatus.phase)
    }
}

@Composable
private fun ModelReadyBanner() {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Pill(
            text = stringResource(id = R.string.onboarding_download_ready_pill),
            color = VestigeTheme.colors.lime,
            dot = true,
            blink = false,
        )
    }
}

/**
 * Inline status band for the non-Active download phases. Status semantics only — no role, no
 * click action (recovery is the scaffold's Retry/Try-again button); polite live region so a
 * screen reader announces the transition without interrupting (`AGENTS.md` band a11y rule).
 */
@Composable
private fun DownloadPhaseBand(phase: DownloadPhase) {
    val colors = VestigeTheme.colors
    val (message, ruleColor) = when (phase) {
        DownloadPhase.Active -> return
        DownloadPhase.Stalled -> stringResource(id = R.string.onboarding_download_stalled) to colors.coral
        DownloadPhase.Failed -> stringResource(id = R.string.onboarding_download_failed) to colors.coral
        DownloadPhase.Reacquiring -> stringResource(id = R.string.onboarding_download_reacquiring) to colors.dim
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = true) {
                liveRegion = LiveRegionMode.Polite
                contentDescription = message
            },
    ) {
        Row(
            modifier = Modifier
                .background(ruleColor)
                .width(BAND_RULE_WIDTH_DP.dp)
                .height(BAND_PAD_DP.dp),
        ) {}
        Text(
            text = message,
            style = VestigeTheme.typography.p,
            color = colors.ink,
            modifier = Modifier.padding(start = BAND_PAD_DP.dp),
        )
    }
}
