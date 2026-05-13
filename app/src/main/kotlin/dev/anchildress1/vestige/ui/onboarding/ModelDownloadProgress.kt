package dev.anchildress1.vestige.ui.onboarding

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ProgressIndicatorDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.anchildress1.vestige.R
import dev.anchildress1.vestige.model.ModelArtifactState
import dev.anchildress1.vestige.ui.components.Pill
import dev.anchildress1.vestige.ui.theme.VestigeTheme
import kotlin.math.roundToInt

private const val PERCENT_SCALE = 100

@Composable
internal fun ModelReadinessBanner(modelState: ModelArtifactState) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        DownloadStatusPill(modelState = modelState)
        DownloadProgressBar(modelState = modelState)
        if (!modelState.isReady) {
            BodyParagraph(
                text = stringResource(id = R.string.onboarding_download_loading_note),
                dim = true,
            )
        }
    }
}

@Composable
private fun DownloadStatusPill(modelState: ModelArtifactState) {
    val colors = VestigeTheme.colors
    val fraction = modelState.downloadFraction
    Row(verticalAlignment = Alignment.CenterVertically) {
        when {
            modelState.isReady -> Pill(
                text = stringResource(id = R.string.onboarding_download_ready_pill),
                color = colors.lime,
                dot = true,
                blink = false,
            )

            fraction != null -> Pill(
                // Coral matches the capture-screen ON AIR pill — same "live work" semantic.
                text = stringResource(
                    id = R.string.onboarding_download_loading_pill_percent,
                    (fraction * PERCENT_SCALE).roundToInt(),
                ),
                color = colors.coral,
                dot = true,
                blink = true,
            )

            else -> Pill(
                text = stringResource(id = R.string.onboarding_download_loading_pill),
                color = colors.coral,
                dot = true,
                blink = true,
            )
        }
    }
}

@Composable
private fun DownloadProgressBar(modelState: ModelArtifactState) {
    if (modelState.isReady) return
    val colors = VestigeTheme.colors
    val fraction = modelState.downloadFraction
    val barModifier = Modifier
        .fillMaxWidth()
        .height(4.dp)
        .semantics { contentDescription = "Download progress" }
    if (fraction != null) {
        LinearProgressIndicator(
            progress = { fraction },
            modifier = barModifier,
            color = colors.coral,
            trackColor = colors.hair,
            gapSize = 0.dp,
            drawStopIndicator = {},
        )
    } else {
        // Indeterminate while we wait for the first Partial state — the artifact store has
        // not reported an expected size yet. Coral keeps the visual signal coherent with the
        // pill.
        LinearProgressIndicator(
            modifier = barModifier,
            color = colors.coral,
            trackColor = colors.hair,
            strokeCap = ProgressIndicatorDefaults.LinearStrokeCap,
            gapSize = 0.dp,
        )
    }
}
