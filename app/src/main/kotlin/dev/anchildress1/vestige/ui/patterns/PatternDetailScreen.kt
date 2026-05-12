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
import androidx.compose.material3.SnackbarHost
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

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

    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
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
        topBar = {
            TopAppBar(
                title = { Text("") },
                navigationIcon = {
                    IconButton(
                        onClick = onBack,
                        modifier = Modifier.semantics { contentDescription = "Back" },
                    ) {
                        Text(text = "←", style = MaterialTheme.typography.titleLarge)
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
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
            Text("Pattern not found.", color = MaterialTheme.colorScheme.onSurfaceVariant)
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
            text = "${loaded.supportingCount} of ${loaded.totalEntryCount} entries · " +
                "Last seen ${loaded.lastSeenLabel}",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        // POC: "Intensity · 30 days" trace strip. Hero element of the detail screen.
        Text(
            text = "INTENSITY · 30 DAYS",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        TraceBar(hits = loaded.traceHits, height = 28.dp)

        HorizontalDivider(color = MaterialTheme.colorScheme.outline)

        Text(text = "Seen in:", style = MaterialTheme.typography.titleSmall)
        loaded.sources.forEach { source ->
            SourceRow(source = source, onClick = { onOpenEntry(source.entryId) })
        }
        if (loaded.sources.isEmpty()) {
            Text(
                text = "No source entries.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        loaded.terminalLabel?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        if (!loaded.isTerminal) {
            ActionRow(actions = actions)
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
private fun ActionRow(actions: PatternActionCallbacks<Unit>) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        OutlinedButton(onClick = { actions.onDismiss(Unit) }, modifier = Modifier.weight(1f)) { Text("Dismiss") }
        OutlinedButton(onClick = { actions.onSnooze(Unit) }, modifier = Modifier.weight(1f)) { Text("Snooze 7 days") }
        OutlinedButton(onClick = { actions.onMarkResolved(Unit) }, modifier = Modifier.weight(1f)) {
            Text("Mark resolved")
        }
    }
}
