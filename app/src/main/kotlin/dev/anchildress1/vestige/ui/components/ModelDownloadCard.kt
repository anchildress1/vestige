package dev.anchildress1.vestige.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ProgressIndicatorDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import dev.anchildress1.vestige.R
import dev.anchildress1.vestige.ui.theme.VestigeFonts
import dev.anchildress1.vestige.ui.theme.VestigeTheme
import kotlin.math.roundToInt

private const val PERCENT_SCALE = 100
private const val BYTES_PER_GB = 1_073_741_824.0 // 1024^3
private const val PROGRESS_BAR_HEIGHT_DP = 10
private const val SECONDS_PER_MINUTE = 60L
private const val MBPS_DECIMAL_CUTOFF = 10f
private const val MBPS_ZERO_CUTOFF = 0.1f

// Big percent (number + sign), small ETA — the percent is the hero, the clock is secondary.
// maxLines=1/softWrap=false + the percent column yielding first keep "100%" from clipping.
private const val PCT_NUM_SP = 88
private const val PCT_SIGN_SP = 72
private const val ETA_SP = 32

/**
 * One immutable snapshot of an in-flight model pull, shared by every download surface so the
 * onboarding and Model Status views can never drift. `null` fields render as `—` / `--:--`
 * rather than fabricating a value.
 */
data class ModelDownloadProgress(
    val fraction: Float?,
    val currentBytes: Long?,
    val expectedBytes: Long?,
    val etaSeconds: Long?,
    val mbps: Float?,
)

/**
 * The one model-download progress block, shared by onboarding Screen 3 and the Settings →
 * Model Status downloading state so both render identically (`poc/onboarding-download-final.png`
 * == `poc/model-detail-downloading-final.png`). Hero percent + ETA over a lime bar over the
 * byte/speed line. All formatting lives here so the two hosts can never drift.
 */
@Composable
fun ModelDownloadCard(progress: ModelDownloadProgress?, wifiConnected: Boolean, modifier: Modifier = Modifier) {
    val colors = VestigeTheme.colors
    val fraction = progress?.fraction
    val expectedBytes = progress?.expectedBytes
    val percent = if (fraction != null) (fraction * PERCENT_SCALE).roundToInt() else null
    val totalGb = expectedBytes?.let { "%.2f GB".format(it / BYTES_PER_GB) } ?: "—"
    VestigeSurface(modifier = modifier, contentPadding = PaddingValues(20.dp)) {
        Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Bottom,
            ) {
                Row(modifier = Modifier.weight(1f, fill = false), verticalAlignment = Alignment.Bottom) {
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
                        text = etaClock(progress?.etaSeconds),
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
                EyebrowE(text = bytesLabel(progress?.currentBytes, expectedBytes))
                EyebrowE(text = speedLabel(progress?.mbps, wifiConnected))
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

private fun bytesLabel(currentBytes: Long?, expectedBytes: Long?): String {
    if (currentBytes == null || expectedBytes == null) return "—"
    return "%.2f / %.2f GB".format(currentBytes / BYTES_PER_GB, expectedBytes / BYTES_PER_GB)
}

private fun speedLabel(mbps: Float?, wifiConnected: Boolean): String {
    val value = when {
        mbps == null -> "—"
        mbps < MBPS_ZERO_CUTOFF -> "0"
        mbps < MBPS_DECIMAL_CUTOFF -> "%.1f".format(mbps)
        else -> mbps.toInt().toString()
    }
    return "~$value MB/S · ${if (wifiConnected) "WI-FI" else "NO WI-FI"}"
}

/** mm:ss for the ETA slot. Unknown ⇒ `--:--` rather than a fabricated clock. */
internal fun etaClock(seconds: Long?): String {
    if (seconds == null || seconds < 0L) return "--:--"
    return "%02d:%02d".format(seconds / SECONDS_PER_MINUTE, seconds % SECONDS_PER_MINUTE)
}
