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
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
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

@Composable
private fun NavIcon(tab: BottomTab, tint: Color) {
    // Decorative — the tab's label + selected semantics carry the announcement.
    val deco = Modifier.clearAndSetSemantics { }
    when (tab) {
        BottomTab.CAPTURE -> Box(modifier = deco.size(DOT).clip(CircleShape).background(tint))

        BottomTab.PATTERNS -> Row(
            modifier = deco,
            horizontalArrangement = Arrangement.spacedBy(BAR_GAP),
        ) {
            Box(modifier = Modifier.size(width = BAR_W, height = ICON_BOX).background(tint))
            Box(modifier = Modifier.size(width = BAR_W, height = ICON_BOX).background(tint))
        }

        // Drawn, not a glyph — the U+21BA history char isn't in the system font and rendered
        // blank on device. A near-full circular stroke + a chevron arrowhead reads as "history".
        BottomTab.HISTORY -> Canvas(modifier = deco.size(ICON_BOX)) {
            val stroke = size.minDimension * HISTORY_STROKE_FRAC
            val inset = stroke
            drawArc(
                color = tint,
                startAngle = HISTORY_ARC_START,
                sweepAngle = HISTORY_ARC_SWEEP,
                useCenter = false,
                topLeft = Offset(inset, inset),
                size = Size(size.width - inset * 2f, size.height - inset * 2f),
                style = Stroke(width = stroke, cap = StrokeCap.Round),
            )
            // Arrowhead at the arc's open (top) end.
            val tip = Offset(size.width / 2f, inset)
            val a = size.minDimension * HISTORY_HEAD_FRAC
            val notch = a * HISTORY_HEAD_NOTCH
            drawPath(
                path = Path().apply {
                    moveTo(tip.x, tip.y)
                    lineTo(tip.x - a, tip.y - notch)
                    moveTo(tip.x, tip.y)
                    lineTo(tip.x - notch, tip.y + a)
                },
                color = tint,
                style = Stroke(width = stroke, cap = StrokeCap.Round),
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

private val MIN_TAP_TARGET: Dp = 48.dp
private val ACTIVE_SEGMENT_H: Dp = 2.dp
private val ICON_BOX: Dp = 14.dp
private val DOT: Dp = 9.dp
private val BAR_W: Dp = 3.dp
private val BAR_GAP: Dp = 3.dp
private const val HISTORY_STROKE_FRAC: Float = 0.12f
private const val HISTORY_HEAD_FRAC: Float = 0.34f
private const val HISTORY_HEAD_NOTCH: Float = 0.4f
private const val HISTORY_ARC_START: Float = 70f
private const val HISTORY_ARC_SWEEP: Float = 280f
