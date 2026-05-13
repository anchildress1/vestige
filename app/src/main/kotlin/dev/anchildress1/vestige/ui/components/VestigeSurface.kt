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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.anchildress1.vestige.ui.atmosphere.noiseGrain
import dev.anchildress1.vestige.ui.theme.Ink
import dev.anchildress1.vestige.ui.theme.Mist
import dev.anchildress1.vestige.ui.theme.RadiusTokens
import dev.anchildress1.vestige.ui.theme.S1
import dev.anchildress1.vestige.ui.theme.S2
import dev.anchildress1.vestige.ui.theme.S3
import dev.anchildress1.vestige.ui.theme.VestigeTextStyles

private const val SURFACE_GRAIN_OPACITY: Float = 0.10f
private val SurfaceHairline: Dp = 1.dp
internal val RowLabelColor: Color = Mist
internal val RowValueColor: Color = Ink

/** Glass card primitive per poc/design-review.md §7.3 — tinted fill + noise + hairline. */
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
            .noiseGrain(opacity = SURFACE_GRAIN_OPACITY)
            .then(accentModifier)
            .border(width = SurfaceHairline, color = S3, shape = shape)
            .padding(contentPadding),
    ) {
        content()
    }
}

/** Key/value line per poc/design-review.md §7.3 — label [RowLabelColor], value [RowValueColor]. */
@Composable
fun VestigeRow(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    labelStyle: TextStyle = VestigeTextStyles.Eyebrow,
    valueStyle: TextStyle = VestigeTextStyles.P,
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
 * Selectable [VestigeSurface] variant per poc/design-review.md §7.3.
 *
 * With [onClick] the fill raises to [S2] and the surface takes a button role.
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
