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
import dev.anchildress1.vestige.ui.components.StatItem
import dev.anchildress1.vestige.ui.components.StatRibbon
import dev.anchildress1.vestige.ui.components.VestigeListCard
import dev.anchildress1.vestige.ui.components.limeLeftRuleForActive
import dev.anchildress1.vestige.ui.theme.VestigeTheme

/** State of one of the four wiring switches. ALWAYS_ON / OFF_USER_ACTION / ON_USER_ACTION. */
internal enum class WiringSwitchState { AlwaysOn, Pending, Granted }

@Immutable
internal data class WiringSwitch(
    val number: String,
    val title: String,
    val description: String,
    val state: WiringSwitchState,
    val pendingHint: String? = null,
    val onTap: (() -> Unit)? = null,
)

@Composable
internal fun WiringScreen(switches: List<WiringSwitch>, modifier: Modifier = Modifier) {
    val live = switches.count { it.state != WiringSwitchState.Pending }
    val pending = switches.count { it.state == WiringSwitchState.Pending }
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        EyebrowE(text = stringResource(id = R.string.onboarding_wiring_eyebrow))
        OnboardingHeadline(text = stringResource(id = R.string.onboarding_wiring_header))
        BodyParagraph(text = stringResource(id = R.string.onboarding_wiring_body))
        StatRibbon(
            items = listOf(
                StatItem(value = live.toString(), label = "LIVE", color = VestigeTheme.colors.lime),
                StatItem(value = pending.toString(), label = "PENDING", color = VestigeTheme.colors.ink),
                StatItem(value = "0", label = "BLOCKED", color = VestigeTheme.colors.dim),
                StatItem(value = "0", label = "CLOUD", color = VestigeTheme.colors.coral),
            ),
        )
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            switches.forEach { switch -> WiringSwitchCard(switch = switch) }
        }
    }
}

@Composable
private fun WiringSwitchCard(switch: WiringSwitch) {
    val colors = VestigeTheme.colors
    val live = switch.state != WiringSwitchState.Pending
    VestigeListCard(
        modifier = Modifier.fillMaxWidth(),
        onClick = switch.onTap,
        role = Role.Switch,
        accentModifier = if (live) Modifier.limeLeftRuleForActive() else Modifier,
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
                    Text(
                        text = "  " + switch.title,
                        style = VestigeTheme.typography.title,
                    )
                }
                Pill(
                    text = if (live) "ON" else "OFF",
                    color = if (live) colors.lime else colors.dim,
                    fill = live,
                    dot = live,
                    blink = false,
                )
            }
            Text(text = switch.description, style = VestigeTheme.typography.p, color = colors.ink)
            if (switch.state == WiringSwitchState.Pending && switch.pendingHint != null) {
                Text(
                    text = switch.pendingHint,
                    style = VestigeTheme.typography.eyebrow,
                    color = colors.dim,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
    }
}
