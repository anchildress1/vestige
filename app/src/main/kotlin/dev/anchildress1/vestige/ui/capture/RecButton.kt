package dev.anchildress1.vestige.ui.capture

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.anchildress1.vestige.ui.components.coralHaloOnRecording
import dev.anchildress1.vestige.ui.motion.rememberSbPulse
import dev.anchildress1.vestige.ui.theme.VestigeTheme

/**
 * 168 dp circular REC button — coral hairline border + outer halo, pulsing inner dot, condensed
 * "REC" display, mono "TAP · TALK · 30s" hint stack. Tap target is the full 168 dp circle, well
 * past the 48 dp Material guideline. Stateless — the caller passes [enabled] (e.g. false while
 * the model is loading) and handles the click.
 *
 * The recording (STOP · FILE IT) action is a wide press-bar in `LiveLayout`, not this button —
 * tap-to-stop is large, square, and ink-filled per `capture-running.png`.
 */
@Composable
fun RecButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    contentDescription: String = DEFAULT_CD,
) {
    val colors = VestigeTheme.colors
    val coralOrDim = if (enabled) colors.coral else colors.faint
    val pulse by rememberSbPulse()
    val dotAlpha = if (enabled) DOT_ALPHA_MIN + pulse * (1f - DOT_ALPHA_MIN) else DOT_ALPHA_DISABLED
    Box(
        modifier = modifier
            .size(BUTTON_SIZE)
            .semantics(mergeDescendants = true) {
                role = Role.Button
                this.contentDescription = contentDescription
            }
            // Halo paints behind the body — `level=0.6f` matches the screenshot's idle bloom. Live
            // halo amplitude is owned by `LiveLayout`; here we want a steady visual ring.
            .coralHaloOnRecording(level = if (enabled) IDLE_HALO_LEVEL else 0f, color = coralOrDim)
            .clip(CircleShape)
            .background(colors.deep, CircleShape)
            .border(width = BORDER_WIDTH, color = coralOrDim, shape = CircleShape)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(DOT_SIZE)
                    .alpha(dotAlpha)
                    .clip(CircleShape)
                    .background(coralOrDim, CircleShape),
            )
            Text(
                text = "REC",
                style = VestigeTheme.typography.displayBig.copy(fontSize = 38.sp, lineHeight = 38.sp),
                color = if (enabled) colors.ink else colors.dim,
            )
            Text(
                text = HINT_TEXT,
                style = VestigeTheme.typography.eyebrow.copy(fontSize = 8.sp),
                color = colors.dim,
            )
        }
    }
}

private const val DEFAULT_CD: String = "Record"
private const val HINT_TEXT: String = "TAP · TALK · 30s"
private val BUTTON_SIZE: Dp = 168.dp
private val BORDER_WIDTH: Dp = 2.dp
private val DOT_SIZE: Dp = 18.dp
private const val IDLE_HALO_LEVEL: Float = 0.6f
private const val DOT_ALPHA_MIN: Float = 0.4f
private const val DOT_ALPHA_DISABLED: Float = 0.25f
