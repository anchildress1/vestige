package dev.anchildress1.vestige.ui.onboarding

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import dev.anchildress1.vestige.ui.components.EyebrowE
import dev.anchildress1.vestige.ui.components.StatusDot
import dev.anchildress1.vestige.ui.components.TickRule
import dev.anchildress1.vestige.ui.theme.VestigeFonts
import dev.anchildress1.vestige.ui.theme.VestigeTheme

/**
 * Persistent top chrome shared by every onboarding screen. The left eyebrow reads
 * `SETUP · NN OF 05` (or `ALL SET · 05 OF 05` on Ready) where `NN` is the count of *enabled
 * wiring switches*, not the screen ordinal. The tick rule fills proportionally.
 */
@Composable
internal fun OnboardingChrome(
    enabledCount: Int,
    totalCount: Int = TOTAL_WIRING_SWITCHES,
    rightStatus: String? = null,
) {
    val colors = VestigeTheme.colors
    val allEnabled = enabledCount >= totalCount
    val leftEyebrow = if (allEnabled) {
        "ALL SET · 0$totalCount OF 0$totalCount"
    } else {
        "SETUP · ${enabledCount.toString().padStart(2, '0')} OF 0$totalCount"
    }
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatusDot(color = colors.lime, blink = !allEnabled)
                EyebrowE(text = leftEyebrow)
            }
            if (rightStatus != null) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    StatusDot(color = colors.lime, blink = false)
                    EyebrowE(text = rightStatus)
                }
            }
        }
        val marks = (0 until enabledCount.coerceIn(0, totalCount)).toSet()
        TickRule(count = totalCount * TICK_DENSITY, marks = marks.expandTo(TICK_DENSITY))
    }
}

private const val TICK_DENSITY = 6

private fun Set<Int>.expandTo(density: Int): Set<Int> =
    flatMap { step -> (step * density until (step + 1) * density) }.toSet()

/**
 * Anton-condensed poster headline ending in a period and (optionally) a small lime accent
 * square. Used as the hero on every onboarding screen.
 */
@Composable
internal fun OnboardingHeadline(text: String, modifier: Modifier = Modifier, accentSquare: Boolean = true) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Bottom,
    ) {
        Text(
            text = text,
            style = TextStyle(
                fontFamily = VestigeFonts.Display,
                fontSize = HEADLINE_SP.sp,
                lineHeight = HEADLINE_LINE_HEIGHT_SP.sp,
                letterSpacing = (-0.02).em,
            ),
            color = VestigeTheme.colors.ink,
        )
        if (accentSquare) {
            Box(modifier = Modifier.width(6.dp))
            Box(
                modifier = Modifier
                    .size(width = ACCENT_SIZE.dp, height = ACCENT_SIZE.dp),
                contentAlignment = Alignment.BottomStart,
            ) {
                Box(
                    modifier = Modifier
                        .size(ACCENT_SIZE.dp)
                        .background(VestigeTheme.colors.lime),
                )
            }
        }
    }
}

private const val HEADLINE_SP = 56
private const val HEADLINE_LINE_HEIGHT_SP = 56
private const val ACCENT_SIZE = 10
