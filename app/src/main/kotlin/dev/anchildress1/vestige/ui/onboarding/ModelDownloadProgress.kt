package dev.anchildress1.vestige.ui.onboarding

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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

@Composable
internal fun ModelReadinessBanner(modelState: ModelArtifactState) {
    if (modelState.isReady) {
        ModelReadyBanner()
    } else {
        DownloadingHero(modelState = modelState)
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
private fun DownloadingHero(modelState: ModelArtifactState) {
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
        BytesLine(modelState = modelState)
        BodyParagraph(
            text = stringResource(id = R.string.onboarding_download_loading_note),
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
private fun BytesLine(modelState: ModelArtifactState) {
    val partial = modelState as? ModelArtifactState.Partial ?: return
    val current = partial.currentBytes / BYTES_PER_GB
    val total = partial.expectedBytes / BYTES_PER_GB
    val remaining = (total - current).coerceAtLeast(0.0)
    val currentLabel = "%.2f GB".format(current)
    val remainingLabel = "%.2f GB LEFT".format(remaining)
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        EyebrowE(text = currentLabel)
        EyebrowE(text = remainingLabel)
    }
}
