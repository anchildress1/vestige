package dev.anchildress1.vestige.ui.patterns

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.anchildress1.vestige.ui.theme.VestigeTheme

/** Purple `#A855F7` left-rule per design-guidelines §"Pattern List / Pattern card". */
private val PatternAccent = Color(0xFFA855F7)

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun PatternsListScreen(
    viewModel: PatternsListViewModel,
    onOpenPattern: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.state.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            // Long ≈ 10s — Story 3.8 wants the undo affordance alive for ≥5s.
            val result = snackbarHostState.showSnackbar(
                message = actionSnackbarMessage(event.action),
                actionLabel = undoLabelFor(event.undo),
                duration = SnackbarDuration.Long,
            )
            if (result == SnackbarResult.ActionPerformed && event.undo != null) {
                viewModel.undo(event.undo)
            }
        }
    }

    Scaffold(
        modifier = modifier,
        topBar = { TopAppBar(title = { Text("Patterns") }) },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        PatternsListBody(
            state = state,
            padding = padding,
            onCardClick = onOpenPattern,
            actions = PatternActionCallbacks(
                onDismiss = viewModel::dismiss,
                onSnooze = viewModel::snooze,
                onMarkResolved = viewModel::markResolved,
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
                    )
                }
            }
        }
    }
}

@Composable
private fun SectionHeader(section: PatternSection) {
    Text(
        text = section.headerLabel.uppercase(),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 4.dp, bottom = 4.dp),
    )
}

@Composable
private fun EmptyState(reason: PatternsListUiState.EmptyReason, modifier: Modifier = Modifier) {
    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(
            text = emptyStateCopy(reason),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun PatternCard(
    card: PatternCardUi,
    onClick: () -> Unit,
    onDismiss: () -> Unit,
    onSnooze: () -> Unit,
    onMarkResolved: () -> Unit,
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .semantics {
                role = Role.Button
                contentDescription = "${card.title}. ${card.observation}"
            },
        color = MaterialTheme.colorScheme.surfaceContainer,
        shape = RoundedCornerShape(12.dp),
    ) {
        Row(modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
            // Rule must stretch the full card height so observation wrapping doesn't strand it.
            Box(
                modifier = Modifier
                    .width(3.dp)
                    .fillMaxHeight()
                    .background(PatternAccent),
            )
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 12.dp, top = 12.dp, bottom = 12.dp, end = 4.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(text = card.title, style = MaterialTheme.typography.titleMedium)
                card.templateLabel?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(text = card.observation, style = MaterialTheme.typography.bodyMedium)
                Spacer(modifier = Modifier.height(2.dp))
                TraceBar(
                    hits = card.traceHits,
                    accent = if (card.section == PatternSection.ACTIVE) PatternAccent else TraceBarDefaults.Muted,
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = "${card.supportingCount} of ${card.totalEntryCount} entries · " +
                        "Last seen ${card.lastSeenLabel}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            OverflowMenu(
                onDismiss = onDismiss,
                onSnooze = onSnooze,
                onMarkResolved = onMarkResolved,
            )
        }
    }
}

@Composable
private fun OverflowMenu(onDismiss: () -> Unit, onSnooze: () -> Unit, onMarkResolved: () -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        IconButton(
            onClick = { expanded = true },
            modifier = Modifier.semantics { contentDescription = "Pattern actions" },
        ) {
            Text(text = "⋮", style = MaterialTheme.typography.titleLarge)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(
                text = { Text("Dismiss") },
                onClick = {
                    expanded = false
                    onDismiss()
                },
            )
            DropdownMenuItem(
                text = { Text("Snooze 7 days") },
                onClick = {
                    expanded = false
                    onSnooze()
                },
            )
            DropdownMenuItem(
                text = { Text("Mark resolved") },
                onClick = {
                    expanded = false
                    onMarkResolved()
                },
            )
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
                    ),
                ),
            ),
            padding = PaddingValues(0.dp),
            onCardClick = {},
            actions = PatternActionCallbacks(onDismiss = {}, onSnooze = {}, onMarkResolved = {}),
        )
    }
}

// Mirrors the POC's `traceHits` for the Tuesday Meetings sample so the @Preview matches.
private val PREVIEW_TRACE_HITS = setOf(3, 10, 17, 24, 26, 28)
