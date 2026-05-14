package dev.anchildress1.vestige.ui.onboarding

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import dev.anchildress1.vestige.R
import dev.anchildress1.vestige.ui.components.EyebrowE
import dev.anchildress1.vestige.ui.components.Pill
import dev.anchildress1.vestige.ui.components.VestigeListCard
import dev.anchildress1.vestige.ui.components.VestigeListCardInteraction
import dev.anchildress1.vestige.ui.components.limeLeftRuleForActive
import dev.anchildress1.vestige.ui.theme.VestigeTheme

/**
 * State of one wiring switch.
 * - [Granted]: ready, green.
 * - [Pending]: not yet acted on (e.g. permission untouched, model still downloading).
 * - [Blocked]: actively prevented — denied permission, Wi-Fi unavailable, corrupt artifact.
 */
internal enum class WiringSwitchState { Granted, Pending, Blocked }

@Immutable
internal data class WiringSwitch(
    val number: String,
    val title: String,
    val description: String,
    val state: WiringSwitchState,
    val pendingHint: String? = null,
    val onTap: (() -> Unit)? = null,
    // Navigation rows (persona swap, model drill-in) read as "button" to screen readers;
    // toggles (mic, notify) keep Role.Switch because tapping them flips a binary permission.
    val role: Role = Role.Switch,
)

@Composable
internal fun WiringScreen(switches: List<WiringSwitch>, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        EyebrowE(text = stringResource(id = R.string.onboarding_wiring_eyebrow))
        OnboardingHeadline(text = stringResource(id = R.string.onboarding_wiring_header))
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            switches.forEach { switch -> WiringSwitchCard(switch = switch) }
        }
    }
}

private data class SwitchVisuals(
    val pillText: String,
    val pillColor: androidx.compose.ui.graphics.Color,
    val showDot: Boolean,
    val showAccent: Boolean,
)

private fun WiringSwitch.toCardInteraction(): VestigeListCardInteraction = when (role) {
    Role.Switch -> VestigeListCardInteraction.Toggleable(
        checked = state == WiringSwitchState.Granted,
        onToggle = onTap,
        role = role,
    )

    else -> if (onTap != null) {
        VestigeListCardInteraction.Click(onClick = onTap, role = role)
    } else {
        VestigeListCardInteraction.Static
    }
}

@Composable
private fun WiringSwitchCard(switch: WiringSwitch) {
    val colors = VestigeTheme.colors
    val visuals = when (switch.state) {
        WiringSwitchState.Granted -> SwitchVisuals("ON", colors.lime, showDot = true, showAccent = true)
        WiringSwitchState.Pending -> SwitchVisuals("OFF", colors.dim, showDot = false, showAccent = false)
        WiringSwitchState.Blocked -> SwitchVisuals("BLOCKED", colors.coral, showDot = true, showAccent = false)
    }
    VestigeListCard(
        modifier = Modifier.fillMaxWidth(),
        interaction = switch.toCardInteraction(),
        accentModifier = if (visuals.showAccent) Modifier.limeLeftRuleForActive() else Modifier,
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    EyebrowE(text = switch.number)
                    Text(text = "  " + switch.title, style = VestigeTheme.typography.title)
                }
                Pill(
                    text = visuals.pillText,
                    color = visuals.pillColor,
                    fill = switch.state == WiringSwitchState.Granted,
                    dot = visuals.showDot,
                    blink = switch.state == WiringSwitchState.Blocked,
                )
            }
            Text(text = switch.description, style = VestigeTheme.typography.p, color = colors.ink)
            if (switch.state != WiringSwitchState.Granted && switch.pendingHint != null) {
                Text(
                    text = switch.pendingHint,
                    style = VestigeTheme.typography.eyebrow,
                    color = if (switch.state == WiringSwitchState.Blocked) colors.coral else colors.dim,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
    }
}
