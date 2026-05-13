package dev.anchildress1.vestige.ui.patterns

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.anchildress1.vestige.R
import dev.anchildress1.vestige.ui.components.VestigeListCard
import dev.anchildress1.vestige.ui.components.limeLeftRuleForActive
import dev.anchildress1.vestige.ui.theme.VestigeTheme

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun PatternsListScreen(
    viewModel: PatternsListViewModel,
    onOpenPattern: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.state.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }

    // Pre-resolve copy at composition so the LaunchedEffect's non-composable scope can use it.
    val dismissedMessage = stringResource(R.string.snackbar_dismissed)
    val snoozedMessage = stringResource(R.string.snackbar_snoozed_7_days)
    val resolvedMessage = stringResource(R.string.snackbar_marked_resolved)
    val restartMessage = stringResource(R.string.snackbar_pattern_back)
    val undoLabel = stringResource(R.string.pattern_undo)

    LaunchedEffect(viewModel, dismissedMessage, snoozedMessage, resolvedMessage, restartMessage, undoLabel) {
        viewModel.events.collect { event ->
            val message = when (event.action) {
                PatternAction.DISMISSED -> dismissedMessage
                PatternAction.SNOOZED -> snoozedMessage
                PatternAction.MARKED_RESOLVED -> resolvedMessage
                PatternAction.RESTART -> restartMessage
            }
            // Long ≈ 10s — Story 3.8 wants the undo affordance alive for ≥5s.
            val result = snackbarHostState.showSnackbar(
                message = message,
                actionLabel = if (event.undo != null) undoLabel else null,
                duration = SnackbarDuration.Long,
            )
            if (result == SnackbarResult.ActionPerformed && event.undo != null) {
                viewModel.undo(event.undo)
            }
        }
    }

    // No containerColor override: M3 defaults to `colorScheme.background` (= Floor) and
    // `contentColor` flows from `onBackground` (= Ink). Theme owns it.
    Scaffold(
        modifier = modifier,
        topBar = { TopAppBar(title = { Text(stringResource(R.string.patterns_title)) }) },
        snackbarHost = { PatternSnackbarHost(snackbarHostState) },
    ) { padding ->
        PatternsListBody(
            state = state,
            padding = padding,
            onCardClick = onOpenPattern,
            actions = PatternActionCallbacks(
                onDismiss = viewModel::dismiss,
                onSnooze = viewModel::snooze,
                onMarkResolved = viewModel::markResolved,
                onRestart = viewModel::restart,
            ),
        )
    }
}

@Composable
private fun PatternsListBody(
    state: PatternsListUiState,
    padding: PaddingValues,
    onCardClick: (String) -> Unit,
    actions: PatternActionCallbacks<String>,
) {
    when (state) {
        PatternsListUiState.Loading -> Unit

        is PatternsListUiState.Empty -> EmptyState(state.reason, Modifier.padding(padding))

        is PatternsListUiState.Loaded -> LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // Group cards into the POC's four sections; preserve last-seen ordering within each.
            val grouped = state.cards.groupBy { it.section }
            PatternSection.entries.forEach { section ->
                val cards = grouped[section].orEmpty()
                if (cards.isEmpty()) return@forEach
                item(key = "header-${section.name}") {
                    SectionHeader(section = section)
                }
                items(cards, key = { it.patternId }) { card ->
                    PatternCard(
                        card = card,
                        onClick = { onCardClick(card.patternId) },
                        onDismiss = { actions.onDismiss(card.patternId) },
                        onSnooze = { actions.onSnooze(card.patternId) },
                        onMarkResolved = { actions.onMarkResolved(card.patternId) },
                        onRestart = { actions.onRestart(card.patternId) },
                    )
                }
            }
        }
    }
}

@Composable
private fun SectionHeader(section: PatternSection) {
    // String resources already carry the uppercase form, removing the Turkish-i locale risk
    // that bit us when we called `uppercase()` at the call site.
    Text(
        text = stringResource(sectionHeaderRes(section)),
        style = MaterialTheme.typography.labelSmall,
        color = VestigeTheme.colors.dim,
        modifier = Modifier.padding(top = 4.dp, bottom = 4.dp),
    )
}

@Composable
private fun EmptyState(reason: PatternsListUiState.EmptyReason, modifier: Modifier = Modifier) {
    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(
            text = stringResource(emptyStateCopyRes(reason)),
            style = MaterialTheme.typography.bodyLarge,
            color = VestigeTheme.colors.dim,
        )
    }
}

@Composable
@Suppress("LongMethod", "LongParameterList") // Compose layout cluster; call-site clarity wins.
private fun PatternCard(
    card: PatternCardUi,
    onClick: () -> Unit,
    onDismiss: () -> Unit,
    onSnooze: () -> Unit,
    onMarkResolved: () -> Unit,
    onRestart: () -> Unit,
) {
    VestigeListCard(
        modifier = Modifier
            .fillMaxWidth()
            .semantics {
                role = Role.Button
                contentDescription = "${card.title}. ${card.observation}"
            },
        onClick = onClick,
        accentModifier = if (card.section == PatternSection.ACTIVE) {
            Modifier.limeLeftRuleForActive(color = VestigeTheme.colors.lime)
        } else {
            Modifier
        },
    ) {
        Row(modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
            Column(
                modifier = Modifier
                    .weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(text = card.title, style = MaterialTheme.typography.titleMedium)
                card.templateLabel?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.labelMedium,
                        color = VestigeTheme.colors.dim,
                    )
                }
                Text(text = card.observation, style = MaterialTheme.typography.bodyMedium)
                Spacer(modifier = Modifier.height(2.dp))
                val traceBarStyle = cardSectionToneFor(card.section).themedStyle()
                TraceBarE(
                    hits = card.traceHits,
                    accent = traceBarStyle.accent,
                    peak = traceBarStyle.peak,
                )
                Spacer(modifier = Modifier.height(2.dp))
                // POC card meta is a single eyebrow line — no "Seen in:" label. (Two-eyebrow
                // SpaceBetween treatment is Phase 4 polish.)
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
            }
            OverflowMenu(
                availableActions = card.availableActions,
                onDismiss = onDismiss,
                onSnooze = onSnooze,
                onMarkResolved = onMarkResolved,
                onRestart = onRestart,
            )
        }
    }
}

internal fun cardSectionToneFor(section: PatternSection): IntensityTone = when (section) {
    PatternSection.ACTIVE -> IntensityTone.ACTIVE_PEAK
    PatternSection.SNOOZED -> IntensityTone.SNOOZED
    PatternSection.RESOLVED, PatternSection.DISMISSED -> IntensityTone.SETTLED
}

@Composable
@Suppress("LongParameterList") // primitive UI dispatch — one callback per action.
private fun OverflowMenu(
    availableActions: Set<PatternAction>,
    onDismiss: () -> Unit,
    onSnooze: () -> Unit,
    onMarkResolved: () -> Unit,
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
            if (PatternAction.DISMISSED in availableActions) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.pattern_action_dismiss)) },
                    onClick = {
                        expanded = false
                        onDismiss()
                    },
                )
            }
            if (PatternAction.SNOOZED in availableActions) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.pattern_action_snooze_7_days)) },
                    onClick = {
                        expanded = false
                        onSnooze()
                    },
                )
            }
            if (PatternAction.MARKED_RESOLVED in availableActions) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.pattern_action_mark_resolved)) },
                    onClick = {
                        expanded = false
                        onMarkResolved()
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

@androidx.compose.ui.tooling.preview.Preview
@Composable
private fun PatternsListPreview() {
    VestigeTheme {
        PatternsListBody(
            state = PatternsListUiState.Loaded(
                listOf(
                    PatternCardUi(
                        patternId = "abc",
                        title = "Tuesday Meetings",
                        templateLabel = "Aftermath",
                        observation = "Fourth entry mentions Tuesday meetings. State before: cruising. After: crashed.",
                        supportingCount = 4,
                        totalEntryCount = 12,
                        lastSeenLabel = "May 7",
                        section = PatternSection.ACTIVE,
                        traceHits = PREVIEW_TRACE_HITS,
                        availableActions = setOf(
                            PatternAction.DISMISSED,
                            PatternAction.SNOOZED,
                            PatternAction.MARKED_RESOLVED,
                        ),
                    ),
                ),
            ),
            padding = PaddingValues(0.dp),
            onCardClick = {},
            actions = PatternActionCallbacks(onDismiss = {}, onSnooze = {}, onMarkResolved = {}, onRestart = {}),
        )
    }
}

// Mirrors the POC's `traceHits` for the Tuesday Meetings sample so the @Preview matches.
private val PREVIEW_TRACE_HITS = setOf(3, 10, 17, 24, 26, 28)
