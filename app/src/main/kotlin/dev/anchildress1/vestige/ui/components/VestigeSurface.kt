package dev.anchildress1.vestige.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.anchildress1.vestige.ui.theme.Dim
import dev.anchildress1.vestige.ui.theme.Hair
import dev.anchildress1.vestige.ui.theme.Hair2
import dev.anchildress1.vestige.ui.theme.Ink
import dev.anchildress1.vestige.ui.theme.RadiusTokens
import dev.anchildress1.vestige.ui.theme.S1
import dev.anchildress1.vestige.ui.theme.S2

private val SurfaceHairline: Dp = 1.dp
internal val RowLabelColor: Color = Dim
internal val RowValueColor: Color = Ink

/** Spacing between tape-grain lines, in pixels — matches `poc/energy-tokens.jsx` TAPE_BG (4px). */
internal const val TAPE_GRAIN_PERIOD_PX: Float = 4f

/**
 * Horizontal printed-receipt grain per ADR-011 §"Surface texture". 1px line every 4px, low alpha.
 * Replaces the Mist noise-grain layer. Renders behind content; safe to chain after `.background`.
 */
fun Modifier.tapeGrain(color: Color = Hair): Modifier = drawWithCache {
    onDrawBehind {
        val h = size.height
        val w = size.width
        if (h <= 0f || w <= 0f) return@onDrawBehind
        var y = 0f
        while (y < h) {
            drawLine(
                color = color,
                start = Offset(0f, y),
                end = Offset(w, y),
                strokeWidth = 1f,
            )
            y += TAPE_GRAIN_PERIOD_PX
        }
    }
}

/**
 * Card primitive per ADR-011: warm-espresso fill, tape-grain overlay, sharp hairline. No noise.
 */
@Suppress("LongParameterList") // Primitive API is intentionally explicit at the call site.
@Composable
fun VestigeSurface(
    modifier: Modifier = Modifier,
    accentModifier: Modifier = Modifier,
    shape: Shape = RadiusTokens.XL,
    fill: Color = S1,
    contentPadding: PaddingValues = PaddingValues(0.dp),
    content: @Composable () -> Unit,
) {
    Box(
        modifier = modifier
            .clip(shape)
            .background(fill, shape)
            .tapeGrain(color = Hair2)
            .then(accentModifier)
            .border(width = SurfaceHairline, color = Hair, shape = shape)
            .padding(contentPadding),
    ) {
        content()
    }
}

/** Key/value line — label [RowLabelColor], value [RowValueColor]. */
@Composable
fun VestigeRow(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    labelStyle: TextStyle = dev.anchildress1.vestige.ui.theme.VestigeTextStyles.Eyebrow,
    valueStyle: TextStyle = dev.anchildress1.vestige.ui.theme.VestigeTextStyles.P,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text = label, style = labelStyle, color = RowLabelColor)
        Text(text = value, style = valueStyle, color = RowValueColor)
    }
}

/**
 * Selectable [VestigeSurface] variant. With [onClick] the fill raises to [S2] and the surface
 * takes a button role.
 */
@Suppress("LongParameterList") // Same primitive tradeoff: fewer wrappers, clearer call sites.
@Composable
fun VestigeListCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    accentModifier: Modifier = Modifier,
    shape: Shape = RadiusTokens.XL,
    contentPadding: PaddingValues = PaddingValues(horizontal = 16.dp, vertical = 14.dp),
    content: @Composable () -> Unit,
) {
    val interactionModifier = if (onClick != null) {
        Modifier
            .clip(shape)
            .clickable(role = Role.Button, onClick = onClick)
    } else {
        Modifier
    }
    VestigeSurface(
        modifier = modifier.then(interactionModifier),
        accentModifier = accentModifier,
        shape = shape,
        fill = vestigeListCardFill(onClick),
        contentPadding = contentPadding,
        content = content,
    )
}

internal fun vestigeListCardFill(onClick: Any?): Color = if (onClick != null) S2 else S1
