package dev.anchildress1.vestige.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.anchildress1.vestige.ui.theme.VestigeTheme

private val SurfaceHairline: Dp = 1.dp

/** Spacing between tape-grain lines, in pixels — matches `poc/energy-tokens.jsx` TAPE_BG (4px). */
internal const val TAPE_GRAIN_PERIOD_PX: Float = 4f

/**
 * Horizontal printed-receipt grain per ADR-011 §"Surface texture". 1px line every 4px, low alpha.
 * Color resolves from `VestigeTheme.colors.tapeGrain`; the optional override is the only seam
 * for tests / one-off visual demos and is not called from production code.
 *
 * The line geometry is cached by `drawWithCache` keyed on size — the per-frame `onDrawBehind`
 * just replays the cached y-positions, so static cards don't pay the line-loop cost every frame.
 */
@Composable
fun Modifier.tapeGrain(color: Color = VestigeTheme.colors.tapeGrain): Modifier = drawWithCache {
    val h = size.height
    val w = size.width
    val yPositions: FloatArray = if (h > 0f && w > 0f) {
        val count = ((h / TAPE_GRAIN_PERIOD_PX).toInt() + 1).coerceAtLeast(0)
        FloatArray(count) { it * TAPE_GRAIN_PERIOD_PX }
    } else {
        FloatArray(0)
    }
    onDrawBehind {
        for (y in yPositions) {
            if (y >= h) break
            drawLine(
                color = color,
                start = Offset(0f, y),
                end = Offset(w, y),
                strokeWidth = 1f,
            )
        }
    }
}

/**
 * Card primitive per ADR-011 — M3 [Surface] handles container fill + `LocalContentColor`
 * propagation. Vestige adds the tape-grain overlay and a hairline border in our brand outline
 * token. No call-site color overrides; theme owns it.
 */
@Composable
fun VestigeSurface(
    modifier: Modifier = Modifier,
    accentModifier: Modifier = Modifier,
    shape: Shape = VestigeTheme.shapes.xl,
    contentPadding: PaddingValues = PaddingValues(0.dp),
    content: @Composable () -> Unit,
) {
    val colors = VestigeTheme.colors
    Surface(
        modifier = modifier,
        shape = shape,
        color = colors.s1,
        contentColor = colors.ink,
        border = BorderStroke(SurfaceHairline, colors.hair),
    ) {
        Box(
            modifier = Modifier
                .tapeGrain()
                .then(accentModifier)
                .padding(contentPadding),
        ) {
            content()
        }
    }
}

/** Key/value line. Value inherits Ink via Surface's contentColor; label dims via `colors.dim`. */
@Composable
fun VestigeRow(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    labelStyle: TextStyle = VestigeTheme.typography.eyebrow,
    valueStyle: TextStyle = VestigeTheme.typography.p,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text = label, style = labelStyle, color = VestigeTheme.colors.dim)
        Text(text = value, style = valueStyle)
    }
}

/** Selectable [VestigeSurface] variant. With [onClick] the fill raises to `colors.s2`. */
@Composable
@Suppress("LongParameterList") // Same primitive tradeoff: fewer wrappers, clearer call sites.
fun VestigeListCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    accentModifier: Modifier = Modifier,
    shape: Shape = VestigeTheme.shapes.xl,
    contentPadding: PaddingValues = PaddingValues(horizontal = 16.dp, vertical = 14.dp),
    content: @Composable () -> Unit,
) {
    val colors = VestigeTheme.colors
    val rootModifier = if (onClick != null) {
        modifier.clickable(role = Role.Button, onClick = onClick)
    } else {
        modifier
    }
    Surface(
        modifier = rootModifier,
        shape = shape,
        color = if (onClick != null) colors.s2 else colors.s1,
        contentColor = colors.ink,
        border = BorderStroke(SurfaceHairline, colors.hair),
    ) {
        Box(
            modifier = Modifier
                .tapeGrain()
                .then(accentModifier)
                .padding(contentPadding),
        ) {
            content()
        }
    }
}
