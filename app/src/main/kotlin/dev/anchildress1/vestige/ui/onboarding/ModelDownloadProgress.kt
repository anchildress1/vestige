package dev.anchildress1.vestige.ui.onboarding

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ProgressIndicatorDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import dev.anchildress1.vestige.R
import dev.anchildress1.vestige.model.ModelArtifactState
import dev.anchildress1.vestige.ui.components.EyebrowE
import dev.anchildress1.vestige.ui.components.Pill
import dev.anchildress1.vestige.ui.theme.VestigeFonts
import dev.anchildress1.vestige.ui.theme.VestigeTheme
import kotlin.math.roundToInt

private const val PERCENT_SCALE = 100
private const val BYTES_PER_GB = 1_073_741_824.0 // 1024^3
private const val PROGRESS_BAR_HEIGHT_DP = 10
private const val DOWNLOADING_NUM_SP = 128
private const val DOWNLOADING_PCT_SP = 72
private const val BAND_RULE_WIDTH_DP = 3
private const val BAND_PAD_DP = 12

@Composable
internal fun ModelReadinessBanner(modelState: ModelArtifactState, downloadStatus: DownloadStatus) {
    if (modelState.isReady) {
        ModelReadyBanner()
    } else {
        DownloadingHero(modelState = modelState, downloadStatus = downloadStatus)
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

@Composable
private fun DownloadingHero(modelState: ModelArtifactState, downloadStatus: DownloadStatus) {
    val colors = VestigeTheme.colors
    val fraction = modelState.downloadFraction
    val percent = if (fraction != null) (fraction * PERCENT_SCALE).roundToInt() else null
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        EyebrowE(text = stringResource(id = R.string.onboarding_download_loading_pill))
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                text = percent?.toString() ?: "—",
                style = TextStyle(
                    fontFamily = VestigeFonts.Display,
                    fontSize = DOWNLOADING_NUM_SP.sp,
                    lineHeight = DOWNLOADING_NUM_SP.sp,
                    letterSpacing = (-0.02).em,
                    fontFeatureSettings = "tnum",
                ),
                color = colors.ink,
            )
            Text(
                text = "%",
                style = TextStyle(
                    fontFamily = VestigeFonts.Display,
                    fontSize = DOWNLOADING_PCT_SP.sp,
                    lineHeight = DOWNLOADING_PCT_SP.sp,
                    letterSpacing = 0.em,
                ),
                color = colors.lime,
            )
        }
        DownloadProgressBar(modelState = modelState)
        BytesLine(modelState = modelState, etaSeconds = downloadStatus.etaSeconds)
        DownloadPhaseBand(phase = downloadStatus.phase)
        // ux-copy.md §Onboarding Screen 3 body line 2 — verbatim, no editorializing.
        BodyParagraph(
            text = stringResource(id = R.string.onboarding_download_body),
            dim = true,
        )
    }
}

@Composable
private fun DownloadProgressBar(modelState: ModelArtifactState) {
    val colors = VestigeTheme.colors
    val fraction = modelState.downloadFraction
    val barModifier = Modifier
        .fillMaxWidth()
        .height(PROGRESS_BAR_HEIGHT_DP.dp)
        .semantics { contentDescription = "Download progress" }
    if (fraction != null) {
        LinearProgressIndicator(
            progress = { fraction },
            modifier = barModifier,
            color = colors.lime,
            trackColor = colors.hair,
            gapSize = 0.dp,
            drawStopIndicator = {},
        )
    } else {
        LinearProgressIndicator(
            modifier = barModifier,
            color = colors.lime,
            trackColor = colors.hair,
            strokeCap = ProgressIndicatorDefaults.LinearStrokeCap,
            gapSize = 0.dp,
        )
    }
}

@Composable
private fun BytesLine(modelState: ModelArtifactState, etaSeconds: Long?) {
    val partial = modelState as? ModelArtifactState.Partial ?: return
    val current = partial.currentBytes / BYTES_PER_GB
    val total = partial.expectedBytes / BYTES_PER_GB
    // ux-copy.md §Onboarding Screen 3 body line 1: `{bytes downloaded} / {total} · {ETA}`.
    val progressLabel = "%.2f / %.2f GB".format(current, total)
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        EyebrowE(text = progressLabel)
        EyebrowE(text = formatEta(etaSeconds))
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
