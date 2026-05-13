package dev.anchildress1.vestige.ui.patterns

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.anchildress1.vestige.ui.theme.Hair
import dev.anchildress1.vestige.ui.theme.Vapor

/**
 * 30-day intensity glyph from `poc/screens-patterns.jsx` / `poc/tokens.jsx` §TraceBar.
 * Lit columns are full-height in [accent]; unlit columns are short and use the muted [muted] tone.
 * Bars stack newest-on-the-right — index 0 is the oldest day in the window.
 */
@Suppress("LongParameterList") // Compose layout primitive; bundling into a config object hurts call-site clarity.
@Composable
fun TraceBar(
    hits: Set<Int>,
    modifier: Modifier = Modifier,
    days: Int = TRACE_BAR_DEFAULT_DAYS,
    height: Dp = TraceBarDefaults.Height,
    accent: Color = TraceBarDefaults.Accent,
    muted: Color = TraceBarDefaults.Muted,
) {
    require(days > 0) { "TraceBar days must be > 0 (got $days)" }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(height),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalAlignment = androidx.compose.ui.Alignment.Bottom,
    ) {
        repeat(days) { index ->
            val lit = isHit(hits, index)
            androidx.compose.foundation.layout.Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight(if (lit) 1f else UNLIT_HEIGHT_FRACTION)
                    .clip(RoundedCornerShape(1.5.dp))
                    .background(if (lit) accent else muted),
            )
        }
    }
}

/** POC token mirror. Kept here so designers tweak in one place rather than per call-site. */
object TraceBarDefaults {
    /** [Vapor] — matches the blue lit cells in `poc/screenshots/patterns.png`. */
    val Accent: Color = Vapor

    /** [Hair] — mist @ 18 % alpha over the deep background. */
    val Muted: Color = Hair

    /** Card glyph default — POC uses `22`. Detail screen passes a larger value. */
    val Height: Dp = 22.dp
}

private const val UNLIT_HEIGHT_FRACTION = 0.34f
