// Compose layout cluster + lifecycle tone helpers; splitting hurts call-site readability.
@file:Suppress("TooManyFunctions")

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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.anchildress1.vestige.R
import dev.anchildress1.vestige.model.PatternState
import dev.anchildress1.vestige.ui.components.VestigeListCard
import dev.anchildress1.vestige.ui.components.VestigeScaffold
import dev.anchildress1.vestige.ui.components.VestigeSurface
import dev.anchildress1.vestige.ui.theme.VestigeTheme

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
@Suppress("LongMethod") // Compose layout cluster; splitting hurts call-site readability.
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
    VestigeScaffold(
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
                onRestart = { viewModel.restart() },
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
                color = VestigeTheme.colors.dim,
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
        PatternSummaryCard(loaded)
        PatternIntensityCard(loaded.state, loaded.traceHits)

        HorizontalDivider(color = VestigeTheme.colors.hair)

        PatternSourcesCard(sources = loaded.sources, onOpenEntry = onOpenEntry)

        loaded.terminalLabel?.let { terminal ->
            Text(
                text = stringResource(terminal.prefixRes, terminal.dateLabel),
                style = MaterialTheme.typography.bodyMedium,
                color = VestigeTheme.colors.dim,
            )
        }

        if (loaded.availableActions.isNotEmpty()) {
            ActionRow(availableActions = loaded.availableActions, actions = actions)
        }
    }
}

@Composable
private fun PatternSummaryCard(loaded: PatternDetailUiState.Loaded) {
    VestigeSurface(contentPadding = PaddingValues(16.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(text = loaded.title, style = MaterialTheme.typography.headlineSmall)
            loaded.templateLabel?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.labelLarge,
                    color = VestigeTheme.colors.dim,
                )
            }
            Text(text = loaded.observation, style = MaterialTheme.typography.bodyLarge)
            // Count meta renders without a label — "Seen in:" is reserved for the sources card
            // heading below.
            Text(
                text = stringResource(
                    R.string.pattern_card_meta,
                    loaded.supportingCount,
                    loaded.totalEntryCount,
                    loaded.lastSeenLabel,
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = VestigeTheme.colors.dim,
            )
        }
    }
}

@Composable
private fun PatternIntensityCard(state: PatternState, traceHits: Set<Int>) {
    // POC: "Intensity · 30 days" trace strip. Hero element of the detail screen.
    val style = intensityToneFor(state).themedStyle()
    VestigeSurface(contentPadding = PaddingValues(16.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                text = stringResource(R.string.pattern_detail_intensity_eyebrow),
                style = MaterialTheme.typography.labelSmall,
                color = VestigeTheme.colors.dim,
            )
            TraceBarE(
                hits = traceHits,
                height = 28.dp,
                accent = style.accent,
                peak = style.peak,
            )
        }
    }
}

internal data class PatternIntensityStyle(val accent: Color, val peak: Boolean)

/**
 * Tone variants kept enum-shaped so the lifecycle → tone mapping stays pure-Kotlin and trivially
 * testable; the concrete color binding happens in [themedStyle], which pulls from the active
 * `VestigeTheme.colors`. One source of truth — `themed*Style` is the only place that resolves
 * tone → color.
 */
internal enum class IntensityTone(val peak: Boolean) {
    ACTIVE_PEAK(peak = true),
    SNOOZED(peak = false),
    SETTLED(peak = false),
    FROZEN(peak = false),
}

internal fun intensityToneFor(state: PatternState): IntensityTone = when (state) {
    PatternState.ACTIVE -> IntensityTone.ACTIVE_PEAK
    PatternState.SNOOZED -> IntensityTone.SNOOZED
    PatternState.RESOLVED, PatternState.DISMISSED -> IntensityTone.SETTLED
    PatternState.BELOW_THRESHOLD -> IntensityTone.FROZEN
}

@Composable
internal fun IntensityTone.themedStyle(): PatternIntensityStyle {
    val colors = VestigeTheme.colors
    val accent = when (this) {
        IntensityTone.ACTIVE_PEAK -> colors.lime
        IntensityTone.SNOOZED -> colors.ember
        IntensityTone.SETTLED -> colors.teal
        IntensityTone.FROZEN -> colors.tealDim
    }
    return PatternIntensityStyle(accent = accent, peak = peak)
}

@Composable
private fun PatternSourcesCard(sources: List<PatternSourceUi>, onOpenEntry: (Long) -> Unit) {
    VestigeSurface(contentPadding = PaddingValues(16.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                text = stringResource(R.string.pattern_detail_seen_in),
                style = MaterialTheme.typography.titleSmall,
            )
            sources.forEach { source ->
                SourceRow(source = source, onClick = { onOpenEntry(source.entryId) })
            }
            if (sources.isEmpty()) {
                Text(
                    text = stringResource(R.string.pattern_detail_no_sources),
                    style = MaterialTheme.typography.bodyMedium,
                    color = VestigeTheme.colors.dim,
                )
            }
        }
    }
}

@Composable
private fun SourceRow(source: PatternSourceUi, onClick: () -> Unit) {
    VestigeListCard(
        modifier = Modifier
            .semantics { role = Role.Button }
            .padding(vertical = 2.dp),
        onClick = onClick,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = source.dateLabel,
                style = MaterialTheme.typography.bodyMedium,
                color = VestigeTheme.colors.dim,
            )
            Text(text = "—", color = VestigeTheme.colors.dim)
            Text(text = source.snippet, style = MaterialTheme.typography.bodyMedium)
        }
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
        if (PatternAction.RESTART in availableActions) {
            OutlinedButton(
                onClick = { actions.onRestart(Unit) },
                modifier = Modifier.weight(1f),
                contentPadding = padding,
            ) {
                ActionButtonLabel(stringResource(R.string.pattern_action_restart))
            }
        }
    }
}

@Composable
private fun ActionButtonLabel(text: String) {
    // Single-line, clip on overflow rather than paint past the button bounds — large font scales
    // or longer locales would otherwise overlap the next button.
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        maxLines = 1,
        softWrap = false,
        overflow = TextOverflow.Ellipsis,
    )
}
