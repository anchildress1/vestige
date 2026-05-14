package dev.anchildress1.vestige.ui.capture

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.anchildress1.vestige.ui.theme.VestigeTheme

/**
 * Capture chunk progress — 6 dp coral fill with hairline ticks at every [tickIntervalSec], plus
 * the `0s · 10s · 20s · 30s` label row beneath. Decorative; the live timer above carries the
 * a11y announcement. Caller passes `elapsedMs / maxMs` as [progress] (clamped to `[0, 1]`).
 */
@Composable
fun ChunkProgressBar(
    progress: Float,
    modifier: Modifier = Modifier,
    barHeight: Dp = BAR_HEIGHT,
    chunkDurationSec: Int = DEFAULT_CHUNK_SEC,
    tickIntervalSec: Int = DEFAULT_TICK_SEC,
) {
    require(chunkDurationSec > 0) { "chunkDurationSec must be positive (got $chunkDurationSec)" }
    require(tickIntervalSec > 0) { "tickIntervalSec must be positive (got $tickIntervalSec)" }
    val clamped = progress.coerceIn(0f, 1f)
    val colors = VestigeTheme.colors
    Column(modifier = modifier) {
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(barHeight)
                // Bar is decorative — the timer above announces remaining seconds. Labels below
                // keep their own default semantics so TalkBack can still read "0s · 10s · 20s ·
                // 30s" as ruler text.
                .clearAndSetSemantics { },
        ) {
            drawRect(color = colors.s2, topLeft = Offset.Zero, size = size)
            drawRect(
                color = colors.coral,
                topLeft = Offset.Zero,
                size = Size(size.width * clamped, size.height),
            )
            drawRect(
                color = colors.hair,
                topLeft = Offset.Zero,
                size = size,
                style = Stroke(width = 1f),
            )
            val tickColor = colors.deep
            for (sec in tickIntervalSec until chunkDurationSec step tickIntervalSec) {
                val x = size.width * (sec.toFloat() / chunkDurationSec.toFloat())
                drawRect(
                    color = tickColor,
                    topLeft = Offset(x, 0f),
                    size = Size(width = 1f, height = size.height),
                )
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            ChunkLabel("0s")
            ChunkLabel("10s")
            ChunkLabel("20s")
            ChunkLabel("30s ▲")
        }
    }
}

@Composable
private fun ChunkLabel(text: String) {
    Text(
        text = text,
        style = VestigeTheme.typography.eyebrow.copy(fontSize = 8.sp),
        color = VestigeTheme.colors.faint,
    )
}

private val BAR_HEIGHT: Dp = 6.dp
private const val DEFAULT_CHUNK_SEC: Int = 30
private const val DEFAULT_TICK_SEC: Int = 5
