package dev.anchildress1.vestige.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.indication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import dev.anchildress1.vestige.ui.motion.VestigeMotion
import dev.anchildress1.vestige.ui.motion.rememberSbBlink
import dev.anchildress1.vestige.ui.theme.Coral
import dev.anchildress1.vestige.ui.theme.Deep
import dev.anchildress1.vestige.ui.theme.Dim
import dev.anchildress1.vestige.ui.theme.Hair
import dev.anchildress1.vestige.ui.theme.Ink
import dev.anchildress1.vestige.ui.theme.Lime
import dev.anchildress1.vestige.ui.theme.RadiusTokens
import dev.anchildress1.vestige.ui.theme.S1
import dev.anchildress1.vestige.ui.theme.VestigeFonts
import dev.anchildress1.vestige.ui.theme.VestigeTextStyles

/**
 * Big Anton-condensed stat for hero numbers (capture stats, pattern detail count, scorecard).
 * Tabular nums via the style override on caller side; this primitive defers font selection.
 */
@Composable
fun BigStat(
    value: String,
    modifier: Modifier = Modifier,
    label: String? = null,
    color: Color = Ink,
    size: Int = BIG_STAT_DEFAULT_SP,
) {
    require(size > 0) { "BigStat size must be positive (was $size)" }
    val lineHeightSp = size * BIG_STAT_LINE_HEIGHT_RATIO
    Column(modifier = modifier, horizontalAlignment = Alignment.Start) {
        Text(
            text = value,
            style = VestigeTextStyles.DisplayBig.copy(fontSize = size.sp, lineHeight = lineHeightSp.sp),
            color = color,
        )
        if (label != null) {
            Text(
                text = label,
                modifier = Modifier.padding(top = 4.dp),
                style = VestigeTextStyles.Eyebrow,
                color = Dim,
            )
        }
    }
}

private const val BIG_STAT_DEFAULT_SP: Int = 56
private const val BIG_STAT_LINE_HEIGHT_RATIO: Float = 0.85f

/** Mono uppercase eyebrow row — single token color, tracking from [VestigeTextStyles.Eyebrow]. */
@Composable
fun EyebrowE(text: String, modifier: Modifier = Modifier, color: Color = Dim) {
    Text(text = text, modifier = modifier, style = VestigeTextStyles.Eyebrow, color = color)
}

/** Status dot, optionally blinking. Default lime; coral when recording. */
@Composable
fun StatusDot(
    modifier: Modifier = Modifier,
    color: Color = Lime,
    blink: Boolean = false,
    size: Dp = DefaultStatusDotSize,
) {
    val alpha = if (blink) rememberSbBlink(periodMs = VestigeMotion.BLINK_MS).value else 1f
    Box(
        modifier = modifier
            // Decorative — the surrounding pill announces its label and the dot is purely visual.
            // Hiding it from a11y keeps TalkBack from announcing a meaningless region.
            .clearAndSetSemantics { }
            .size(size)
            .alpha(alpha)
            .drawBehind {
                drawRect(
                    brush = Brush.radialGradient(
                        colors = listOf(color.copy(alpha = STATUS_DOT_GLOW_ALPHA), Color.Transparent),
                        center = Offset(this.size.width / 2f, this.size.height / 2f),
                        radius = maxOf(this.size.width, this.size.height),
                    ),
                )
                drawCircle(color = color, radius = this.size.minDimension / 2f)
            },
    )
}

internal val DefaultStatusDotSize: Dp = 7.dp
private const val STATUS_DOT_GLOW_ALPHA: Float = 0.55f

/**
 * Capsule pill with optional [dot] and mono label. Filled when [fill] is true (used for ON AIR);
 * outlined otherwise. Colors default to the lime "signal" semantic.
 */
@Composable
@Suppress("LongParameterList") // primitive
fun Pill(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = Lime,
    fill: Boolean = false,
    dot: Boolean = false,
    blink: Boolean = false,
) {
    val fg = if (fill) Deep else color
    Row(
        modifier = modifier
            .clip(RadiusTokens.Pill)
            .background(if (fill) color else Color.Transparent, RadiusTokens.Pill)
            .border(width = 1.dp, color = color, shape = RadiusTokens.Pill)
            .padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (dot) StatusDot(color = if (fill) Deep else color, blink = blink, size = PillDotSize)
        Text(
            text = text,
            style = VestigeTextStyles.PersonaLabel.copy(fontWeight = FontWeight.Bold, letterSpacing = 0.16.em),
            color = fg,
        )
    }
}

private val PillDotSize: Dp = 6.dp

/** ▲N / ▼N delta tag. Lime for positive, coral for negative. Zero renders as `—`. */
@Composable
fun Delta(value: Int, modifier: Modifier = Modifier, label: String? = null) {
    val positive = value > 0
    val negative = value < 0
    val color = when {
        positive -> Lime
        negative -> Coral
        else -> Dim
    }
    val glyph = when {
        positive -> "▲$value"
        negative -> "▼${-value}"
        else -> "—"
    }
    val a11y = when {
        positive -> "up $value${label?.let { " $it" }.orEmpty()}"
        negative -> "down ${-value}${label?.let { " $it" }.orEmpty()}"
        else -> "no change${label?.let { ", $it" }.orEmpty()}"
    }
    Row(
        modifier = modifier.semantics(mergeDescendants = true) { contentDescription = a11y },
        verticalAlignment = Alignment.Bottom,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            text = glyph,
            style = TextStyle(
                fontFamily = VestigeFonts.Display,
                fontSize = 14.sp,
                lineHeight = 14.sp,
                letterSpacing = 0.02.em,
            ),
            color = color,
        )
        if (label != null) {
            Text(
                text = label,
                style = VestigeTextStyles.Eyebrow.copy(fontWeight = FontWeight.Medium),
                color = Dim,
            )
        }
    }
}

/**
 * Newsroom mini-stat row — [BigStat]-style numbers grouped under mono eyebrow labels, divided by
 * hairline columns. Tape-grain backdrop reads as a printed scoreboard ribbon.
 */
data class StatItem(val value: String, val label: String, val color: Color = Ink)

@Composable
fun StatRibbon(items: List<StatItem>, modifier: Modifier = Modifier) {
    require(items.isNotEmpty()) { "StatRibbon items must not be empty" }
    Row(
        modifier = modifier
            .clip(RadiusTokens.M)
            .background(S1)
            .tapeGrain()
            .border(1.dp, Hair, RadiusTokens.M),
    ) {
        items.forEachIndexed { index, item ->
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 8.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = item.value,
                    style = VestigeTextStyles.DisplayBig.copy(fontSize = 28.sp, lineHeight = 24.sp),
                    color = item.color,
                )
                EyebrowE(text = item.label, color = Dim)
            }
            if (index < items.lastIndex) {
                Box(
                    modifier = Modifier
                        .padding(vertical = 6.dp)
                        .size(width = 1.dp, height = 28.dp)
                        .background(Hair),
                )
            }
        }
    }
}

/**
 * Ruler tick row — `count` cells, ticks at indices in `marks` rise to full height; others are
 * short hairlines. Used for the 30s chunk countdown on Capture.
 */
@Composable
@Suppress("LongParameterList") // primitive
fun TickRule(
    count: Int,
    marks: Set<Int>,
    modifier: Modifier = Modifier,
    height: Dp = 8.dp,
    tickColor: Color = Ink,
    railColor: Color = Hair,
) {
    require(count > 0) { "TickRule count must be > 0 (got $count)" }
    Row(
        modifier = modifier.height(height),
        horizontalArrangement = Arrangement.spacedBy(1.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        repeat(count) { index ->
            val lit = index in marks
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight(if (lit) 1f else TICK_RAIL_HEIGHT)
                    .background(if (lit) tickColor else railColor),
            )
        }
    }
}

private const val TICK_RAIL_HEIGHT: Float = 0.40f

/** Minimum touch target per Material accessibility guideline (Android 48dp guidance). */
private val MinTapTarget: Dp = 48.dp

/**
 * App shell top — LOCAL · GEMMA 4 status pill (or ON AIR · LIVE while recording) on the left,
 * persona switcher chrome on the right. Used by Capture and any screen that wants the chrome.
 */
@Composable
@Suppress("LongParameterList") // primitive
fun AppTop(
    persona: String,
    modifier: Modifier = Modifier,
    recording: Boolean = false,
    onPersonaTap: () -> Unit = {},
    onStatusTap: () -> Unit = {},
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .windowInsetsPadding(WindowInsets.statusBars)
            .padding(horizontal = 18.dp, vertical = 8.dp)
            .drawBehind {
                val y = size.height
                drawLine(
                    color = Hair,
                    start = Offset(0f, y),
                    end = Offset(size.width, y),
                    strokeWidth = Stroke.HairlineWidth,
                )
            },
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val statusLabel = if (recording) "Recording. Local model active." else "Local model ready: Gemma 4."
        TappableChrome(
            onClick = onStatusTap,
            alignment = Alignment.CenterStart,
            a11yLabel = statusLabel,
        ) {
            if (recording) {
                Pill(text = "ON AIR · LIVE", color = Coral, dot = true, blink = true, fill = false)
            } else {
                Pill(text = "LOCAL · GEMMA 4", color = Lime, dot = true, blink = true, fill = false)
            }
        }
        TappableChrome(
            onClick = onPersonaTap,
            alignment = Alignment.CenterEnd,
            a11yLabel = "Active persona $persona. Change persona.",
        ) {
            Pill(text = "$persona ▾", color = Ink, fill = false)
        }
    }
}

/**
 * 48dp-minimum tap target that shapes its ripple to the content (intended for [Pill]s in
 * [AppTop]). The outer [Box] reserves the touch slop with no indication; the inner [Box] hosts
 * the ripple clipped to [RadiusTokens.Pill], and both share an [MutableInteractionSource] so a
 * tap anywhere in the slop animates the pill-shaped ripple.
 */
@Composable
private fun TappableChrome(
    onClick: () -> Unit,
    alignment: Alignment,
    a11yLabel: String,
    content: @Composable () -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    Box(
        modifier = Modifier
            .sizeIn(minHeight = MinTapTarget)
            .semantics(mergeDescendants = true) {
                role = Role.Button
                contentDescription = a11yLabel
            }
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick,
            ),
        contentAlignment = alignment,
    ) {
        Box(
            modifier = Modifier
                .clip(RadiusTokens.Pill)
                .indication(interactionSource, ripple(bounded = true)),
        ) {
            content()
        }
    }
}
