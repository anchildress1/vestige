package dev.anchildress1.vestige.ui.onboarding

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import dev.anchildress1.vestige.ui.components.EyebrowE
import dev.anchildress1.vestige.ui.components.StatusDot
import dev.anchildress1.vestige.ui.components.TickRule
import dev.anchildress1.vestige.ui.theme.VestigeFonts
import dev.anchildress1.vestige.ui.theme.VestigeTheme

/**
 * Persistent top chrome shared by every onboarding screen. The left eyebrow tracks enabled
 * wiring switches, not a screen ordinal; `ALL SET` simply means all five switches read ready.
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
 * Anton-condensed poster headline. The headline string's trailing period — if any — is
 * rendered as a lime square character (`■`) at a smaller size, replacing the period glyph.
 * Because the square is a *real text character*, it sits on the baseline by definition —
 * no Placeholder, no padding offsets, no font-metrics math.
 */
@Composable
internal fun OnboardingHeadline(text: String, modifier: Modifier = Modifier, accentSquare: Boolean = true) {
    val (body, replacePeriod) = if (accentSquare && text.endsWith(".")) {
        text.dropLast(1) to true
    } else {
        text to false
    }
    val annotated = buildAnnotatedString {
        append(body)
        if (replacePeriod) {
            withStyle(
                SpanStyle(
                    color = VestigeTheme.colors.lime,
                    fontSize = ACCENT_GLYPH_SP.sp,
                    letterSpacing = (-0.04).em,
                ),
            ) {
                // U+25A0 BLACK SQUARE. Sits on the baseline like any glyph.
                append("■")
            }
        }
    }
    Text(
        text = annotated,
        modifier = modifier.fillMaxWidth(),
        style = TextStyle(
            fontFamily = VestigeFonts.Display,
            fontSize = HEADLINE_SP.sp,
            lineHeight = HEADLINE_LINE_HEIGHT_SP.sp,
            letterSpacing = (-0.02).em,
        ),
        color = VestigeTheme.colors.ink,
    )
}

private const val HEADLINE_SP = 56
private const val HEADLINE_LINE_HEIGHT_SP = 56

// Square glyph (U+25A0) at this sp value reads as a chunky period: ~30% of the headline
// cap height. Glyph is text so it inherits baseline alignment from the layout, no padding.
private const val ACCENT_GLYPH_SP = 28
