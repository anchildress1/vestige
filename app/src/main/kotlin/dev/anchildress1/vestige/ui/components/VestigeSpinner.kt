package dev.anchildress1.vestige.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.anchildress1.vestige.ui.theme.VestigeTheme

/**
 * Indeterminate spinner — a lime arc spinning at a steady rate. Decorative: the surrounding
 * band's text + live region carries the announcement, so semantics are cleared. Used for
 * background work with no measurable progress (e.g. the entry-detail extraction state).
 */
@Composable
fun VestigeSpinner(modifier: Modifier = Modifier, diameter: Dp = DEFAULT_DIAMETER, color: Color = Color.Unspecified) {
    val arcColor = if (color == Color.Unspecified) VestigeTheme.colors.lime else color
    val transition = rememberInfiniteTransition(label = "spinner")
    val angle by transition.animateFloat(
        initialValue = 0f,
        targetValue = FULL_TURN,
        animationSpec = infiniteRepeatable(tween(durationMillis = PERIOD_MS, easing = LinearEasing)),
        label = "spinner-rotation",
    )
    Canvas(modifier = modifier.size(diameter).clearAndSetSemantics { }) {
        val stroke = size.minDimension * STROKE_FRAC
        val inset = stroke
        rotate(angle) {
            drawArc(
                color = arcColor,
                startAngle = 0f,
                sweepAngle = SWEEP,
                useCenter = false,
                topLeft = Offset(inset, inset),
                size = Size(size.width - inset * 2f, size.height - inset * 2f),
                style = Stroke(width = stroke, cap = StrokeCap.Round),
            )
        }
    }
}

private val DEFAULT_DIAMETER: Dp = 16.dp

/** Size for a spinner that stands alone in the middle of a page (Submitting, model-not-ready). */
val PageSpinnerDiameter: Dp = 64.dp
private const val FULL_TURN: Float = 360f
private const val SWEEP: Float = 270f
private const val PERIOD_MS: Int = 900
private const val STROKE_FRAC: Float = 0.14f
