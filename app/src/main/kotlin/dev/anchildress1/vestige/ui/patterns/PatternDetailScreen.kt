package dev.anchildress1.vestige.ui.patterns

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.anchildress1.vestige.R

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun PatternDetailScreen(
    viewModel: PatternDetailViewModel,
    onBack: () -> Unit,
    onOpenEntry: (Long) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val state by viewModel.state.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }

    val dismissedMessage = stringResource(R.string.snackbar_dismissed)
    val snoozedMessage = stringResource(R.string.snackbar_snoozed_7_days)
    val resolvedMessage = stringResource(R.string.snackbar_marked_resolved)
    val undoLabel = stringResource(R.string.pattern_undo)

    LaunchedEffect(viewModel, dismissedMessage, snoozedMessage, resolvedMessage, undoLabel) {
        viewModel.events.collect { event ->
            val message = when (event.action) {
                PatternAction.DISMISSED -> dismissedMessage
                PatternAction.SNOOZED -> snoozedMessage
                PatternAction.MARKED_RESOLVED -> resolvedMessage
            }
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

    val backDescription = stringResource(R.string.pattern_back_description)
    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text("") },
                navigationIcon = {
                    IconButton(
                        onClick = onBack,
                        modifier = Modifier.semantics { contentDescription = backDescription },
                    ) {
                        Text(
                            text = stringResource(R.string.pattern_back_glyph),
                            style = MaterialTheme.typography.titleLarge,
                        )
                    }
                },
            )
        },
        snackbarHost = { PatternSnackbarHost(snackbarHostState) },
    ) { padding ->
        PatternDetailBody(
            state = state,
            padding = padding,
            onOpenEntry = onOpenEntry,
            actions = PatternActionCallbacks(
                onDismiss = { viewModel.dismiss() },
                onSnooze = { viewModel.snooze() },
                onMarkResolved = { viewModel.markResolved() },
            ),
        )
    }
}

@Composable
private fun PatternDetailBody(
    state: PatternDetailUiState,
    padding: PaddingValues,
    onOpenEntry: (Long) -> Unit,
    actions: PatternActionCallbacks<Unit>,
) {
    when (state) {
        PatternDetailUiState.Loading -> Unit

        PatternDetailUiState.NotFound -> Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = stringResource(R.string.pattern_detail_not_found),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        is PatternDetailUiState.Loaded -> LoadedBody(
            loaded = state,
            padding = padding,
            onOpenEntry = onOpenEntry,
            actions = actions,
        )
    }
}

@Composable
private fun LoadedBody(
    loaded: PatternDetailUiState.Loaded,
    padding: PaddingValues,
    onOpenEntry: (Long) -> Unit,
    actions: PatternActionCallbacks<Unit>,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(padding)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(text = loaded.title, style = MaterialTheme.typography.headlineSmall)
        loaded.templateLabel?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(text = loaded.observation, style = MaterialTheme.typography.bodyLarge)
        Text(
            text = stringResource(
                R.string.pattern_card_meta,
                loaded.supportingCount,
                loaded.totalEntryCount,
                loaded.lastSeenLabel,
            ),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        // POC: "Intensity · 30 days" trace strip. Hero element of the detail screen.
        Text(
            text = stringResource(R.string.pattern_detail_intensity_eyebrow),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        TraceBar(hits = loaded.traceHits, height = 28.dp)

        HorizontalDivider(color = MaterialTheme.colorScheme.outline)

        Text(text = stringResource(R.string.pattern_detail_seen_in), style = MaterialTheme.typography.titleSmall)
        loaded.sources.forEach { source ->
            SourceRow(source = source, onClick = { onOpenEntry(source.entryId) })
        }
        if (loaded.sources.isEmpty()) {
            Text(
                text = stringResource(R.string.pattern_detail_no_sources),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        loaded.terminalLabel?.let { terminal ->
            Text(
                text = stringResource(terminal.prefixRes, terminal.dateLabel),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        if (loaded.availableActions.isNotEmpty()) {
            ActionRow(availableActions = loaded.availableActions, actions = actions)
        }
    }
}

@Composable
private fun SourceRow(source: PatternSourceUi, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .semantics { role = Role.Button }
            .padding(vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = source.dateLabel,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(text = "—", color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(text = source.snippet, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun ActionRow(availableActions: Set<PatternAction>, actions: PatternActionCallbacks<Unit>) {
    // Compact padding + single-line text keeps "Mark resolved" / "Snooze 7 days" inside the
    // button at phone widths. Default OutlinedButton padding (24 dp / side) overflowed.
    val padding = PaddingValues(horizontal = 10.dp, vertical = 8.dp)
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (PatternAction.DISMISSED in availableActions) {
            OutlinedButton(
                onClick = { actions.onDismiss(Unit) },
                modifier = Modifier.weight(1f),
                contentPadding = padding,
            ) {
                ActionButtonLabel(stringResource(R.string.pattern_action_dismiss))
            }
        }
        if (PatternAction.SNOOZED in availableActions) {
            OutlinedButton(
                onClick = { actions.onSnooze(Unit) },
                modifier = Modifier.weight(1f),
                contentPadding = padding,
            ) {
                ActionButtonLabel(stringResource(R.string.pattern_action_snooze_7_days))
            }
        }
        if (PatternAction.MARKED_RESOLVED in availableActions) {
            OutlinedButton(
                onClick = { actions.onMarkResolved(Unit) },
                modifier = Modifier.weight(1f),
                contentPadding = padding,
            ) {
                ActionButtonLabel(stringResource(R.string.pattern_action_mark_resolved))
            }
        }
    }
}

@Composable
private fun ActionButtonLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        maxLines = 1,
        overflow = TextOverflow.Visible,
    )
}
