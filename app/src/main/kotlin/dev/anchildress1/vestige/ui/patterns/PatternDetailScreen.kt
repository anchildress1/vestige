// Compose layout cluster + lifecycle tone helpers; splitting hurts call-site readability.
@file:Suppress("TooManyFunctions")

package dev.anchildress1.vestige.ui.patterns

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.anchildress1.vestige.R
import dev.anchildress1.vestige.model.PatternState
import dev.anchildress1.vestige.ui.components.EyebrowE
import dev.anchildress1.vestige.ui.components.VestigeListCard
import dev.anchildress1.vestige.ui.components.VestigeListCardInteraction
import dev.anchildress1.vestige.ui.components.VestigeScaffold
import dev.anchildress1.vestige.ui.components.VestigeSurface
import dev.anchildress1.vestige.ui.theme.VestigeTheme

@OptIn(ExperimentalMaterial3Api::class)
@Composable
@Suppress("LongMethod") // Compose layout cluster; splitting hurts call-site readability.
fun PatternDetailScreen(
    viewModel: PatternDetailViewModel,
    onBack: () -> Unit,
    onOpenEntry: (Long) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbarHostState = rememberPatternSnackbarHostState(viewModel.events, viewModel::undo)

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
                onDrop = { viewModel.drop() },
                onSkip = { viewModel.skip() },
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

        PatternVocabularyCard(words = loaded.vocabulary)

        loaded.terminalLabel?.let { terminal ->
            val text = terminal.days
                ?.let { stringResource(terminal.prefixRes, terminal.dateLabel, it) }
                ?: stringResource(terminal.prefixRes, terminal.dateLabel)
            Text(
                text = text,
                style = MaterialTheme.typography.bodyMedium,
                color = VestigeTheme.colors.dim,
                // Status band per AGENTS.md: announced politely, no click action.
                modifier = Modifier.semantics {
                    liveRegion = LiveRegionMode.Polite
                    contentDescription = text
                },
            )
        }

        // CLOSED is model-detected — the terminal banner replaces the action row. DROPPED keeps Restart.
        if (loaded.availableActions.isNotEmpty() && loaded.state != PatternState.CLOSED) {
            ActionRow(availableActions = loaded.availableActions, actions = actions)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PatternVocabularyCard(words: List<String>) {
    if (words.isEmpty()) return
    VestigeSurface(contentPadding = PaddingValues(16.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                text = stringResource(R.string.pattern_detail_words_used),
                style = MaterialTheme.typography.labelSmall,
                color = VestigeTheme.colors.dim,
            )
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                words.forEach { word -> VocabularyChip(word) }
            }
        }
    }
}

@Composable
private fun VocabularyChip(word: String) {
    Box(
        modifier = Modifier
            .border(width = 1.dp, color = VestigeTheme.colors.faint, shape = RectangleShape)
            .semantics { contentDescription = "word used: $word" }
            .padding(horizontal = 10.dp, vertical = 4.dp),
    ) {
        Text(text = word, style = VestigeTheme.typography.eyebrow, color = VestigeTheme.colors.ink)
    }
}

@Composable
private fun PatternSummaryCard(loaded: PatternDetailUiState.Loaded) {
    VestigeSurface(contentPadding = PaddingValues(16.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(text = loaded.title, style = MaterialTheme.typography.headlineSmall)
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
        interaction = VestigeListCardInteraction.Click(onClick = onClick),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                EyebrowE(text = source.dateLabel, maxLines = 1, softWrap = false)
                Text(
                    text = source.snippet,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(
                text = "→",
                style = MaterialTheme.typography.titleMedium,
                color = VestigeTheme.colors.dim,
                maxLines = 1,
                softWrap = false,
            )
        }
    }
}

@Composable
private fun ActionRow(availableActions: Set<PatternAction>, actions: PatternActionCallbacks<Unit>) {
    // Compact padding + single-line text keeps the action labels inside the buttons at phone
    // widths. Default OutlinedButton padding (24 dp / side) overflowed.
    val padding = PaddingValues(horizontal = 10.dp, vertical = 8.dp)
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (PatternAction.DROP in availableActions) {
            OutlinedButton(
                onClick = { actions.onDrop(Unit) },
                modifier = Modifier.weight(1f),
                contentPadding = padding,
            ) {
                ActionButtonLabel(stringResource(R.string.pattern_action_dismiss))
            }
        }
        if (PatternAction.SKIP in availableActions) {
            OutlinedButton(
                onClick = { actions.onSkip(Unit) },
                modifier = Modifier.weight(1f),
                contentPadding = padding,
            ) {
                ActionButtonLabel(stringResource(R.string.pattern_action_snooze_7_days))
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
