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

internal const val TOTAL_ONBOARDING_STEPS = 5

private val OnboardingStep.displayNumber: Int
    get() = ordinal + 1

private val OnboardingStep.leftEyebrow: String
    get() = if (this == OnboardingStep.Ready) {
        "ALL SET · 05 OF 0$TOTAL_ONBOARDING_STEPS"
    } else {
        "SETUP · 0$displayNumber OF 0$TOTAL_ONBOARDING_STEPS"
    }

/**
 * Persistent top chrome shared by every onboarding screen: the SETUP eyebrow on the left
 * (with the active-step lime dot), an optional status string on the right (e.g.
 * `WI-FI · GOOD`, `PULLING · LIVE`), and a 5-cell tick rule beneath that fills as the user
 * advances. Matches `poc/screenshots/onboarding-*.png`.
 */
@Composable
internal fun OnboardingChrome(step: OnboardingStep, rightStatus: String? = null) {
    val colors = VestigeTheme.colors
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
                StatusDot(color = colors.lime, blink = step != OnboardingStep.Ready)
                EyebrowE(text = step.leftEyebrow)
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
        val marks = (0 until step.displayNumber.coerceIn(0, TOTAL_ONBOARDING_STEPS)).toSet()
        TickRule(count = TOTAL_ONBOARDING_STEPS * TICK_DENSITY, marks = marks.expandTo(TICK_DENSITY))
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
