package dev.anchildress1.vestige.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.anchildress1.vestige.ui.theme.VestigeTheme

/**
 * Shared bottom navigation — three tabs, the active one lit lime with a lime top-segment over
 * it. State-driven and reused on every primary screen (Capture / Patterns / History) so the
 * chrome is identical everywhere. [active] `null` lights none — used on menu destinations like
 * Settings that aren't one of the three tabs. The owning nav layer maps [onSelect] to routes.
 */
@Composable
fun VestigeBottomNav(active: BottomTab?, onSelect: (BottomTab) -> Unit, modifier: Modifier = Modifier) {
    val colors = VestigeTheme.colors
    Column(modifier = modifier.fillMaxWidth()) {
        Box(modifier = Modifier.fillMaxWidth().height(ACTIVE_SEGMENT_H)) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(1.dp)
                    .align(Alignment.TopCenter)
                    .background(colors.hair),
            )
            if (active != null) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(1f / BottomTab.entries.size)
                        .height(ACTIVE_SEGMENT_H)
                        .align(
                            when (active) {
                                BottomTab.CAPTURE -> Alignment.TopStart
                                BottomTab.PATTERNS -> Alignment.TopCenter
                                BottomTab.HISTORY -> Alignment.TopEnd
                            },
                        )
                        .background(colors.lime),
                )
            }
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.navigationBars)
                .padding(vertical = 10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BottomTab.entries.forEach { tab ->
                NavItem(
                    tab = tab,
                    selected = tab == active,
                    onClick = { onSelect(tab) },
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun NavItem(tab: BottomTab, selected: Boolean, onClick: () -> Unit, modifier: Modifier) {
    val colors = VestigeTheme.colors
    val tint = if (selected) colors.lime else colors.dim
    Column(
        modifier = modifier
            .selectable(selected = selected, role = Role.Tab, onClick = onClick)
            .heightIn(min = MIN_TAP_TARGET)
            .padding(vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterVertically),
    ) {
        Box(modifier = Modifier.height(ICON_BOX), contentAlignment = Alignment.Center) {
            NavIcon(tab = tab, tint = tint)
        }
        Text(
            text = tab.label(),
            style = VestigeTheme.typography.eyebrow,
            color = tint,
        )
    }
}

// Lucide geometry, drawn (no icon-font dependency): coords are the verbatim 24-unit viewBox
// `d` data from lucide.dev (`history`, `chart-no-axes-column-increasing`) scaled into the box
// at Lucide's 2-unit round stroke. Capture stays a filled record dot.
private const val LUCIDE_VIEWBOX: Float = 24f
private const val LUCIDE_STROKE: Float = 2f
private const val LUCIDE_PATTERNS_PATH: String = "M5 21v-6 M12 21V9 M19 21V3"
private const val LUCIDE_HISTORY_PATH: String =
    "M3 12a9 9 0 1 0 9-9 9.75 9.75 0 0 0-6.74 2.74L3 8 M3 3v5h5 M12 7v5l4 2"

@Composable
private fun NavIcon(tab: BottomTab, tint: Color) {
    // Decorative — the tab's label + selected semantics carry the announcement.
    val deco = Modifier.clearAndSetSemantics { }
    when (tab) {
        BottomTab.CAPTURE -> Box(modifier = deco.size(DOT).clip(CircleShape).background(tint))
        BottomTab.PATTERNS -> LucideGlyph(LUCIDE_PATTERNS_PATH, tint, deco)
        BottomTab.HISTORY -> LucideGlyph(LUCIDE_HISTORY_PATH, tint, deco)
    }
}

@Composable
private fun LucideGlyph(pathData: String, tint: Color, modifier: Modifier) {
    Canvas(modifier = modifier.size(ICON_BOX)) {
        val path = PathParser().parsePathString(pathData).toPath()
        scale(size.minDimension / LUCIDE_VIEWBOX, pivot = Offset.Zero) {
            drawPath(
                path = path,
                color = tint,
                style = Stroke(width = LUCIDE_STROKE, cap = StrokeCap.Round, join = StrokeJoin.Round),
            )
        }
    }
}

/** The three primary destinations. Capture is the recording/idle surface. */
enum class BottomTab { CAPTURE, PATTERNS, HISTORY }

private fun BottomTab.label(): String = when (this) {
    BottomTab.CAPTURE -> "CAPTURE"
    BottomTab.PATTERNS -> "PATTERNS"
    BottomTab.HISTORY -> "HISTORY"
}

private val MIN_TAP_TARGET: Dp = 56.dp
private val ACTIVE_SEGMENT_H: Dp = 2.dp
private val ICON_BOX: Dp = 20.dp
private val DOT: Dp = 12.dp
