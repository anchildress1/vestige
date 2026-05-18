package dev.anchildress1.vestige.ui.onboarding

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.anchildress1.vestige.R
import dev.anchildress1.vestige.ui.components.EyebrowE
import dev.anchildress1.vestige.ui.components.StatusDot
import dev.anchildress1.vestige.ui.components.VestigeGroupedList
import dev.anchildress1.vestige.ui.theme.VestigeTheme

/**
 * State of one wiring row.
 * - [Granted]: ready, lime dot.
 * - [Pending]: not yet acted on (permission untouched, model still downloading).
 * - [Blocked]: actively prevented — denied permission, Wi-Fi unavailable, corrupt artifact.
 */
internal enum class WiringSwitchState { Granted, Pending, Blocked }

@Immutable
internal data class WiringSwitch(
    /** Left mono column — PERSONA / LOCAL / MIC / NOTIFY. */
    val label: String,
    /** Sentence-case identity / call-to-action line. */
    val title: String,
    val description: String,
    val state: WiringSwitchState,
    val pendingHint: String? = null,
    val onTap: (() -> Unit)? = null,
    // Nav rows (persona swap, model drill-in) read as "button"; permission rows keep
    // Role.Switch because the tap flips a binary permission.
    val role: Role = Role.Switch,
)

@Composable
internal fun WiringScreen(switches: List<WiringSwitch>, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        OnboardingHeadline(text = stringResource(id = R.string.onboarding_wiring_header))
        BodyParagraph(text = stringResource(id = R.string.onboarding_wiring_subhead), dim = true)
        VestigeGroupedList(itemCount = switches.size) { index ->
            WiringRow(switch = switches[index])
        }
    }
}

@Immutable
private data class RowVisuals(val dot: Color, val filled: Boolean, val blink: Boolean, val stateWord: String)

@Composable
private fun rowVisuals(state: WiringSwitchState): RowVisuals {
    val colors = VestigeTheme.colors
    return when (state) {
        WiringSwitchState.Granted -> RowVisuals(colors.lime, filled = true, blink = false, stateWord = "On")
        WiringSwitchState.Pending -> RowVisuals(colors.dim, filled = true, blink = false, stateWord = "Off")
        WiringSwitchState.Blocked -> RowVisuals(colors.coral, filled = true, blink = true, stateWord = "Blocked")
    }
}

@Composable
private fun WiringRow(switch: WiringSwitch) {
    val colors = VestigeTheme.colors
    val visuals = rowVisuals(switch.state)
    // Dot is decorative (StatusDot clears its own semantics); the row description carries the
    // state word so the indicator is never color-only — AGENTS.md band a11y rule.
    val a11y = "${switch.label}. ${switch.title}. ${visuals.stateWord}."
    val onTap = switch.onTap
    val interactionModifier = when {
        onTap == null -> Modifier.semantics(mergeDescendants = true) { contentDescription = a11y }

        switch.role == Role.Switch ->
            Modifier
                .semantics(mergeDescendants = true) { contentDescription = a11y }
                .toggleable(
                    value = switch.state == WiringSwitchState.Granted,
                    role = Role.Switch,
                    onValueChange = { onTap() },
                )

        else ->
            Modifier
                .semantics(mergeDescendants = true) { contentDescription = a11y }
                .clickable(role = switch.role, onClick = onTap)
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(interactionModifier)
            .padding(horizontal = 16.dp, vertical = 16.dp),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // Fixed column tuned to the longest label (PERSONA) so every title shares one left
        // edge. Tighter than the original 84dp — alignment without the dead gap.
        EyebrowE(
            text = switch.label,
            modifier = Modifier.width(WiringLabelWidth).padding(top = 4.dp),
            color = colors.dim,
        )
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(text = switch.title, style = VestigeTheme.typography.h2)
            Text(text = switch.description, style = VestigeTheme.typography.p, color = colors.dim)
            if (switch.state != WiringSwitchState.Granted && switch.pendingHint != null) {
                Text(
                    text = switch.pendingHint,
                    style = VestigeTheme.typography.eyebrow,
                    color = if (switch.state == WiringSwitchState.Blocked) colors.coral else colors.dim,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
        StatusDot(
            color = visuals.dot,
            filled = visuals.filled,
            blink = visuals.blink,
            modifier = Modifier.padding(top = 6.dp),
        )
    }
}

// Wide enough for the longest label ("PERSONA") at the mono eyebrow size; shared by every row
// so the titles form one vertical edge.
private val WiringLabelWidth = 68.dp
