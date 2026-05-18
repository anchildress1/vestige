package dev.anchildress1.vestige.ui.patterns

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.anchildress1.vestige.R
import dev.anchildress1.vestige.ui.components.EyebrowE
import dev.anchildress1.vestige.ui.components.VestigeListCard
import dev.anchildress1.vestige.ui.components.VestigeListCardInteraction
import dev.anchildress1.vestige.ui.components.limeLeftRuleForActive
import dev.anchildress1.vestige.ui.theme.VestigeTheme

// Dropped cards stay legible but de-prioritized per spec-pattern-action-buttons.md §Visual.
private const val DROPPED_CARD_ALPHA = 0.6f

/**
 * The pattern card — the single shared card surface for the Patterns list (and any future
 * pattern-card surface, so the look stays identical everywhere). Structure mirrors
 * `poc/pattern-lifecycle-final.png`: a tone-colored uppercase category eyebrow on top, the
 * pattern name, the one-line observation, the 30-day TraceBar, then the source/last-seen meta.
 * Tone (lime / ember / teal) is the section tone — the comp's per-card colors are sample
 * variety, not a per-category palette.
 */
@Composable
@Suppress("LongMethod", "LongParameterList") // Compose layout cluster; call-site clarity wins.
fun PatternCard(
    card: PatternCardUi,
    onClick: () -> Unit,
    onDrop: () -> Unit,
    onSkip: () -> Unit,
    onRestart: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val cardAlpha = if (card.section == PatternSection.DROPPED) DROPPED_CARD_ALPHA else 1f
    val backDescription = card.backLabel?.let { stringResource(R.string.pattern_card_back_on, it) }
    val tone = cardSectionToneFor(card.section).themedStyle()
    VestigeListCard(
        modifier = modifier
            .fillMaxWidth()
            .alpha(cardAlpha)
            .semantics {
                role = Role.Button
                contentDescription = listOfNotNull(card.title, card.observation, backDescription).joinToString(". ")
            },
        interaction = VestigeListCardInteraction.Click(onClick = onClick),
        accentModifier = if (card.section == PatternSection.ACTIVE) {
            Modifier.limeLeftRuleForActive(color = VestigeTheme.colors.lime)
        } else {
            Modifier
        },
    ) {
        Row(modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                card.templateLabel?.let {
                    EyebrowE(text = it.uppercase(), color = tone.accent)
                }
                Text(text = card.title, style = MaterialTheme.typography.titleMedium)
                Text(text = card.observation, style = MaterialTheme.typography.bodyMedium)
                Spacer(modifier = Modifier.height(2.dp))
                TraceBarE(hits = card.traceHits, accent = tone.accent, peak = tone.peak)
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = stringResource(
                        R.string.pattern_card_meta,
                        card.supportingCount,
                        card.totalEntryCount,
                        card.lastSeenLabel,
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = VestigeTheme.colors.dim,
                )
                card.backLabel?.let { back ->
                    Text(
                        text = stringResource(R.string.pattern_card_back_on, back),
                        style = MaterialTheme.typography.labelSmall,
                        color = VestigeTheme.colors.dim,
                    )
                }
            }
            OverflowMenu(
                availableActions = card.availableActions,
                onDrop = onDrop,
                onSkip = onSkip,
                onRestart = onRestart,
            )
        }
    }
}

@Composable
private fun OverflowMenu(
    availableActions: Set<PatternAction>,
    onDrop: () -> Unit,
    onSkip: () -> Unit,
    onRestart: () -> Unit,
) {
    if (availableActions.isEmpty()) return
    var expanded by remember { mutableStateOf(false) }
    val overflowDescription = stringResource(R.string.pattern_actions_overflow_description)
    Box {
        IconButton(
            onClick = { expanded = true },
            modifier = Modifier.semantics { contentDescription = overflowDescription },
        ) {
            Text(text = stringResource(R.string.pattern_overflow_glyph), style = MaterialTheme.typography.titleLarge)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            if (PatternAction.DROP in availableActions) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.pattern_action_dismiss)) },
                    onClick = {
                        expanded = false
                        onDrop()
                    },
                )
            }
            if (PatternAction.SKIP in availableActions) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.pattern_action_snooze_7_days)) },
                    onClick = {
                        expanded = false
                        onSkip()
                    },
                )
            }
            if (PatternAction.RESTART in availableActions) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.pattern_action_restart)) },
                    onClick = {
                        expanded = false
                        onRestart()
                    },
                )
            }
        }
    }
}
