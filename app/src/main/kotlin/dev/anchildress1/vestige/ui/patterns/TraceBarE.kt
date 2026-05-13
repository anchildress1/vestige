package dev.anchildress1.vestige.ui.patterns

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.anchildress1.vestige.ui.theme.VestigeTheme

/**
 * Scoreboard TraceBar — replaces the Mist TraceBar. 30-day intensity glyph from
 * `poc/energy-tokens.jsx` §TraceBarE: lit cells are full-height with optional peak glow; unlit
 * cells are 18 % short hairlines. Bars stack newest-on-the-right; index 0 is the oldest day.
 */
@Suppress("LongParameterList") // Compose layout primitive.
@Composable
fun TraceBarE(
    hits: Set<Int>,
    modifier: Modifier = Modifier,
    days: Int = TRACE_BAR_DEFAULT_DAYS,
    height: Dp = TraceBarDefaults.Height,
    accent: Color = VestigeTheme.colors.lime,
    rail: Color = VestigeTheme.colors.hair,
    peak: Boolean = true,
) {
    require(days > 0) { "TraceBarE days must be > 0 (got $days)" }
    val peakShape = VestigeTheme.shapes.xs
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(height),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        repeat(days) { index ->
            val lit = isHit(hits, index)
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight(if (lit) 1f else UNLIT_HEIGHT_FRACTION)
                    .then(
                        if (lit && peak) Modifier.shadow(elevation = 2.dp, shape = peakShape) else Modifier,
                    )
                    .background(if (lit) accent else rail),
            )
        }
    }
}

/** Layout-only defaults; color defaults flow from `VestigeTheme.colors` on the composable params. */
object TraceBarDefaults {
    /** Card glyph default height. */
    val Height: Dp = 28.dp
}

private const val UNLIT_HEIGHT_FRACTION = 0.18f
