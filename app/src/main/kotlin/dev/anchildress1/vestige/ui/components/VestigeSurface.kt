package dev.anchildress1.vestige.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
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
import androidx.compose.ui.semantics.semantics
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

/**
 * Interaction contract for [VestigeListCard].
 *
 * The variants encode the card's semantic model directly instead of exposing loosely-related
 * click, role, selected, and checked knobs that can drift out of sync at call sites.
 */
sealed interface VestigeListCardInteraction {
    data object Static : VestigeListCardInteraction

    data class Click(val onClick: () -> Unit, val role: Role = Role.Button) : VestigeListCardInteraction

    data class Selectable(val selected: Boolean, val onClick: () -> Unit, val role: Role = Role.Button) :
        VestigeListCardInteraction

    data class Toggleable(val checked: Boolean, val onToggle: (() -> Unit)? = null, val role: Role = Role.Switch) :
        VestigeListCardInteraction
}

private val VestigeListCardInteraction.isInteractive: Boolean
    get() = when (this) {
        VestigeListCardInteraction.Static -> false
        is VestigeListCardInteraction.Click -> true
        is VestigeListCardInteraction.Selectable -> true
        is VestigeListCardInteraction.Toggleable -> onToggle != null
    }

/**
 * Selectable [VestigeSurface] variant. Interactive variants raise the fill to `colors.s2`.
 *
 * `Selectable` routes through `Modifier.selectable` — the correct Compose primitive for
 * radio-button / checkbox card patterns. `Toggleable` uses `Modifier.toggleable`, including
 * disabled checked-state semantics when the row is informative but not currently tappable.
 */
@Composable
fun VestigeListCard(
    modifier: Modifier = Modifier,
    interaction: VestigeListCardInteraction = VestigeListCardInteraction.Static,
    accentModifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(horizontal = 16.dp, vertical = 14.dp),
    content: @Composable () -> Unit,
) {
    val colors = VestigeTheme.colors
    val rootModifier = when (interaction) {
        VestigeListCardInteraction.Static -> modifier

        is VestigeListCardInteraction.Click -> modifier.clickable(
            role = interaction.role,
            onClick = interaction.onClick,
        )

        is VestigeListCardInteraction.Selectable -> modifier.selectable(
            selected = interaction.selected,
            role = interaction.role,
            onClick = interaction.onClick,
        )

        is VestigeListCardInteraction.Toggleable -> {
            modifier
                .semantics(mergeDescendants = true) {}
                .toggleable(
                    value = interaction.checked,
                    enabled = interaction.onToggle != null,
                    role = interaction.role,
                    onValueChange = { interaction.onToggle?.invoke() },
                )
        }
    }
    Surface(
        modifier = rootModifier,
        shape = VestigeTheme.shapes.xl,
        color = if (interaction.isInteractive) colors.s2 else colors.s1,
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
