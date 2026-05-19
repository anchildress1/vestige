package dev.anchildress1.vestige.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import dev.anchildress1.vestige.ui.theme.VestigeTheme

/**
 * Scoreboard destructive-confirm modal — matches `poc/settings-confirm-destructive-final.png`:
 * `● CONFIRM` coral eyebrow, a display headline, dim body, then CANCEL (hairline outline) and
 * the destructive action (coral outline) side by side. [extra] hangs optional content (e.g. the
 * typed-DELETE field for the irreversible wipe) between the body and the buttons; gate the
 * action with [confirmEnabled].
 */
@Composable
@Suppress("LongParameterList") // Modal seam: copy x3 + enabled + 2 callbacks + optional slot.
fun VestigeConfirmCard( // NOSONAR kotlin:S107
    title: String,
    body: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    confirmEnabled: Boolean = true,
    cancelLabel: String = "CANCEL",
    extra: (@Composable ColumnScope.() -> Unit)? = null,
) {
    val colors = VestigeTheme.colors
    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(colors.deep)
                .border(width = 1.dp, color = colors.hair)
                .padding(horizontal = 20.dp, vertical = 20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            EyebrowE(text = "● CONFIRM", color = colors.coral, maxLines = 1, softWrap = false)
            Text(text = title.uppercase(), style = VestigeTheme.typography.displayBig, color = colors.ink)
            Text(text = body, style = VestigeTheme.typography.p, color = colors.dim)
            extra?.invoke(this)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                ConfirmButton(
                    label = cancelLabel,
                    color = colors.dim,
                    enabled = true,
                    onClick = onDismiss,
                    modifier = Modifier.weight(1f),
                )
                ConfirmButton(
                    label = confirmLabel,
                    color = if (confirmEnabled) colors.coral else colors.dim,
                    enabled = confirmEnabled,
                    onClick = onConfirm,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun ConfirmButton(label: String, color: Color, enabled: Boolean, onClick: () -> Unit, modifier: Modifier) {
    Box(
        modifier = modifier
            .border(width = 1.dp, color = color)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .semantics {
                contentDescription = label
                if (!enabled) disabled()
            }
            .padding(vertical = 14.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text = label, style = VestigeTheme.typography.personaLabel, color = color)
    }
}
