package dev.anchildress1.vestige.ui.onboarding

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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
 * Anton-condensed poster headline. The headline string's trailing period — if any — is replaced
 * with a small lime square sitting at the baseline. Matches `poc/screenshots/onboarding-*.png`
 * where the period IS the lime accent block, not a separate glyph next to one.
 */
@Composable
internal fun OnboardingHeadline(text: String, modifier: Modifier = Modifier, accentSquare: Boolean = true) {
    val (body, periodIsSquare) = if (accentSquare && text.endsWith(".")) {
        text.dropLast(1) to true
    } else {
        text to false
    }
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Bottom,
    ) {
        Text(
            text = body,
            style = TextStyle(
                fontFamily = VestigeFonts.Display,
                fontSize = HEADLINE_SP.sp,
                lineHeight = HEADLINE_LINE_HEIGHT_SP.sp,
                letterSpacing = (-0.02).em,
            ),
            color = VestigeTheme.colors.ink,
        )
        if (periodIsSquare) {
            // 3dp gap is tight enough that the square reads as the period, not a separate
            // accent. 12dp square sits at the text baseline thanks to the Row's bottom align.
            Box(modifier = Modifier.width(3.dp))
            Box(
                modifier = Modifier
                    .padding(bottom = ACCENT_BASELINE_OFFSET.dp)
                    .size(ACCENT_SIZE.dp)
                    .background(VestigeTheme.colors.lime),
            )
        }
    }
}

private const val HEADLINE_SP = 56
private const val HEADLINE_LINE_HEIGHT_SP = 56
private const val ACCENT_SIZE = 12
private const val ACCENT_BASELINE_OFFSET = 4
