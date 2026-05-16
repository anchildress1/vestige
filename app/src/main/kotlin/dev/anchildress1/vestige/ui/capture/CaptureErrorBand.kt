package dev.anchildress1.vestige.ui.capture

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.anchildress1.vestige.ui.theme.VestigeTheme

/**
 * Idle-state diagnostic band. Read-only — REC-tap clears the underlying state, which clears
 * the band. `resolveBandKind` enumerates the rendered conditions.
 */
@Composable
fun CaptureErrorBand(
    error: CaptureError?,
    readiness: ModelReadiness,
    modifier: Modifier = Modifier,
    onUseTyped: (() -> Unit)? = null,
) {
    val kind = resolveBandKind(error = error, readiness = readiness) ?: return
    val colors = VestigeTheme.colors
    val accent = if (kind.isError) colors.coral else colors.dim
    Column(modifier = modifier) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(IntrinsicSize.Min)
                .border(width = 1.dp, color = colors.hair)
                .background(colors.s1)
                .semantics(mergeDescendants = true) {
                    liveRegion = LiveRegionMode.Polite
                    contentDescription = kind.contentDescription
                },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AccentRule(color = accent)
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = kind.eyebrow,
                    style = VestigeTheme.typography.eyebrow,
                    color = accent,
                )
                Text(
                    text = kind.body,
                    style = VestigeTheme.typography.p,
                    color = colors.ink,
                )
            }
        }
        // Permanently-blocked mic is the one band with a recovery affordance: a real Button
        // (own role + click), kept OUT of the band's merged status region so a screen reader
        // exposes it as a distinct, actionable node.
        if (kind is BandKind.MicBlocked && onUseTyped != null) {
            Text(
                text = CaptureCopy.USE_TYPED_INSTEAD,
                style = VestigeTheme.typography.eyebrow,
                color = colors.lime,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(role = Role.Button, onClick = onUseTyped)
                    .padding(horizontal = 12.dp, vertical = 10.dp)
                    .semantics { contentDescription = CaptureCopy.USE_TYPED_INSTEAD },
            )
        }
    }
}

@Composable
private fun AccentRule(color: Color) {
    Box(
        modifier = Modifier
            .width(AccentRuleWidth)
            .fillMaxHeight()
            .background(color),
    )
}

private val AccentRuleWidth = 3.dp

internal sealed interface BandKind {
    val eyebrow: String
    val body: String
    val isError: Boolean
    val contentDescription: String

    object MicDenied : BandKind {
        override val eyebrow = CaptureCopy.BAND_LABEL_MIC
        override val body = CaptureCopy.MIC_DENIED_LINE
        override val isError = true
        override val contentDescription = "Mic permission denied. $body"
    }

    data object MicBlocked : BandKind {
        override val eyebrow = CaptureCopy.BAND_LABEL_MIC
        override val body = "${CaptureCopy.MIC_BLOCKED_LINE}\n${CaptureCopy.MIC_BLOCKED_SETTINGS_LINE}"
        override val isError = true
        override val contentDescription =
            "${CaptureCopy.MIC_BLOCKED_LINE} ${CaptureCopy.MIC_BLOCKED_SETTINGS_LINE}"
    }

    data object MicUnavailable : BandKind {
        override val eyebrow = CaptureCopy.BAND_LABEL_MIC
        override val body = CaptureCopy.MIC_UNAVAILABLE_LINE
        override val isError = true
        override val contentDescription = "Mic unavailable. $body"
    }

    data class Inference(val reason: CaptureError.InferenceFailed.Reason) : BandKind {
        override val eyebrow: String = CaptureCopy.BAND_LABEL_MODEL
        override val body: String = when (reason) {
            CaptureError.InferenceFailed.Reason.PARSE_FAILED -> CaptureCopy.INFERENCE_PARSE_FAILED_LINE
            CaptureError.InferenceFailed.Reason.TIMED_OUT -> CaptureCopy.INFERENCE_TIMED_OUT_LINE
            CaptureError.InferenceFailed.Reason.ENGINE_FAILED -> CaptureCopy.INFERENCE_ENGINE_FAILED_LINE
        }
        override val isError = true
        override val contentDescription: String = "Last reading failed. $body"
    }

    object ModelLoading : BandKind {
        override val eyebrow = CaptureCopy.BAND_LABEL_MODEL_LOADING
        override val body = CaptureCopy.MODEL_LOADING_LINE
        override val isError = false
        override val contentDescription = "Model warming up. $body"
    }

    object ModelPaused : BandKind {
        override val eyebrow = CaptureCopy.BAND_LABEL_MODEL_PAUSED
        override val body = CaptureCopy.MODEL_PAUSED_LINE
        override val isError = false
        override val contentDescription = "Model paused. $body"
    }

    data class ModelDownloading(val percent: Int) : BandKind {
        init {
            require(percent in PERCENT_RANGE) { "Downloading percent must be in 0..100 (got $percent)" }
        }

        override val eyebrow: String = CaptureCopy.BAND_LABEL_MODEL_DOWNLOADING_FMT.format(percent)
        override val body: String = CaptureCopy.MODEL_DOWNLOADING_LINE_FMT.format(percent)
        override val isError = false
        override val contentDescription: String = "Model downloading $percent percent."

        private companion object {
            val PERCENT_RANGE = 0..100
        }
    }
}

internal fun resolveBandKind(error: CaptureError?, readiness: ModelReadiness): BandKind? {
    if (error != null) {
        return when (error) {
            CaptureError.MicDenied -> BandKind.MicDenied
            CaptureError.MicBlocked -> BandKind.MicBlocked
            CaptureError.MicUnavailable -> BandKind.MicUnavailable
            is CaptureError.InferenceFailed -> BandKind.Inference(error.reason)
        }
    }
    return when (readiness) {
        ModelReadiness.Loading -> BandKind.ModelLoading
        ModelReadiness.Paused -> BandKind.ModelPaused
        is ModelReadiness.Downloading -> BandKind.ModelDownloading(readiness.percent)
        ModelReadiness.Ready -> null
    }
}
