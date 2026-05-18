package dev.anchildress1.vestige.ui.onboarding

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import dev.anchildress1.vestige.ui.components.VestigeSurface
import dev.anchildress1.vestige.ui.theme.VestigeFonts
import dev.anchildress1.vestige.ui.theme.VestigeTheme
import kotlin.math.roundToInt

private const val PERCENT_SCALE = 100
private const val BYTES_PER_GB = 1_073_741_824.0 // 1024^3
private const val PROGRESS_BAR_HEIGHT_DP = 10

// Big percent (number + sign), small ETA — the percent is the hero; the clock is secondary.
// maxLines=1/softWrap=false + the percent column yielding first keep "100%" from clipping.
private const val PCT_NUM_SP = 88
private const val PCT_SIGN_SP = 72
private const val ETA_SP = 32
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
        DownloadCard(
            modelState = modelState,
            downloadMbps = downloadMbps,
            downloadStatus = downloadStatus,
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

@Composable
private fun DownloadCard(
    modelState: ModelArtifactState,
    downloadMbps: Float?,
    downloadStatus: DownloadStatus,
    wifiConnected: Boolean,
) {
    val colors = VestigeTheme.colors
    val fraction = modelState.downloadFraction
    val percent = if (fraction != null) (fraction * PERCENT_SCALE).roundToInt() else null
    val partial = modelState as? ModelArtifactState.Partial
    val totalGb = partial?.let { "%.2f GB".format(it.expectedBytes / BYTES_PER_GB) } ?: "—"
    VestigeSurface(contentPadding = PaddingValues(20.dp)) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            // Bottom-aligned: the ETA clock sits on the same baseline-ish bottom as the big
            // percent; the "ETA" label rides above it, within the number's height.
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Bottom,
            ) {
                Row(
                    modifier = Modifier.weight(1f, fill = false),
                    verticalAlignment = Alignment.Bottom,
                ) {
                    Text(
                        text = percent?.toString() ?: "—",
                        style = numberStyle(PCT_NUM_SP),
                        color = colors.lime,
                        maxLines = 1,
                        softWrap = false,
                    )
                    Text(
                        text = "%",
                        style = numberStyle(PCT_SIGN_SP),
                        color = colors.lime,
                        maxLines = 1,
                        softWrap = false,
                    )
                }
                Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    EyebrowE(text = stringResource(id = R.string.onboarding_download_eta_label))
                    Text(
                        text = etaClock(downloadStatus.etaSeconds),
                        style = numberStyle(ETA_SP),
                        color = colors.ink,
                        maxLines = 1,
                        softWrap = false,
                    )
                }
            }
            EyebrowE(text = stringResource(id = R.string.onboarding_download_of_total, totalGb))
            DownloadProgressBar(fraction = fraction)
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                EyebrowE(text = bytesLabel(partial))
                EyebrowE(text = speedLabel(modelState, downloadMbps, wifiConnected))
            }
        }
    }
}

private fun numberStyle(sizeSp: Int): TextStyle = TextStyle(
    fontFamily = VestigeFonts.Display,
    fontSize = sizeSp.sp,
    lineHeight = sizeSp.sp,
    letterSpacing = (-0.02).em,
    fontFeatureSettings = "tnum",
)

@Composable
private fun DownloadProgressBar(fraction: Float?) {
    val colors = VestigeTheme.colors
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

private fun bytesLabel(partial: ModelArtifactState.Partial?): String {
    if (partial == null) return "—"
    val current = partial.currentBytes / BYTES_PER_GB
    val total = partial.expectedBytes / BYTES_PER_GB
    return "%.2f / %.2f GB".format(current, total)
}

private fun speedLabel(modelState: ModelArtifactState, downloadMbps: Float?, wifiConnected: Boolean): String {
    val partial = modelState is ModelArtifactState.Partial
    val value = when {
        !partial -> "—"
        downloadMbps == null -> "—"
        downloadMbps < 0.1f -> "0"
        downloadMbps < 10f -> "%.1f".format(downloadMbps)
        else -> downloadMbps.toInt().toString()
    }
    val net = if (wifiConnected) "WI-FI" else "NO WI-FI"
    return "~$value MB/S · $net"
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
