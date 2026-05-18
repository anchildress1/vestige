package dev.anchildress1.vestige.ui.patterns

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.anchildress1.vestige.R
import dev.anchildress1.vestige.ui.components.VestigeScaffold
import dev.anchildress1.vestige.ui.theme.VestigeTheme

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PatternsListScreen(
    viewModel: PatternsListViewModel,
    onOpenPattern: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbarHostState = rememberPatternSnackbarHostState(viewModel.events, viewModel::undo)

    VestigeScaffold(
        modifier = modifier,
        topBar = { TopAppBar(title = { Text(stringResource(R.string.patterns_title)) }) },
        snackbarHost = { PatternSnackbarHost(snackbarHostState) },
    ) { padding ->
        PatternsListBody(
            state = state,
            padding = padding,
            onCardClick = onOpenPattern,
            actions = PatternActionCallbacks(
                onDrop = viewModel::drop,
                onSkip = viewModel::skip,
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

        is PatternsListUiState.Empty -> EmptyState(state, Modifier.padding(padding))

        is PatternsListUiState.Loaded -> LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // Section order is the PatternSection declaration order per spec §P0.4:
            // ACTIVE → SKIPPED → CLOSED → DROPPED. Empty sections render no header.
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
                        onDrop = { actions.onDrop(card.patternId) },
                        onSkip = { actions.onSkip(card.patternId) },
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
private fun EmptyState(empty: PatternsListUiState.Empty, modifier: Modifier = Modifier) {
    val copy = emptyCopyFor(empty.reason)
    val eyebrow = copy.eyebrowRes?.let { stringResource(it, empty.entryCount) }
    val header = stringResource(copy.headerRes)
    val body = stringResource(copy.bodyRes)
    // Status band per AGENTS.md: announced politely, no click action, single merged description.
    val description = listOfNotNull(eyebrow, header, body).joinToString(" ")
    Box(
        modifier = modifier
            .fillMaxSize()
            .semantics(mergeDescendants = true) {
                liveRegion = LiveRegionMode.Polite
                contentDescription = description
            },
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            eyebrow?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.labelSmall,
                    color = VestigeTheme.colors.dim,
                    textAlign = TextAlign.Center,
                )
            }
            Text(
                text = header,
                style = MaterialTheme.typography.titleMedium,
                color = VestigeTheme.colors.ink,
                textAlign = TextAlign.Center,
            )
            Text(
                text = body,
                style = MaterialTheme.typography.bodyMedium,
                color = VestigeTheme.colors.dim,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Preview
@Composable
private fun PatternsListPreview() {
    VestigeTheme {
        PatternsListBody(
            state = PatternsListUiState.Loaded(
                listOf(
                    PatternCardUi(
                        patternId = "abc",
                        title = "Tuesday Meetings",
                        templateLabel = "Crashed",
                        observation = "Fourth entry mentions Tuesday meetings. State before: cruising. After: crashed.",
                        supportingCount = 4,
                        totalEntryCount = 12,
                        lastSeenLabel = "May 7",
                        section = PatternSection.ACTIVE,
                        traceHits = PREVIEW_TRACE_HITS,
                        availableActions = setOf(PatternAction.DROP, PatternAction.SKIP),
                    ),
                ),
            ),
            padding = PaddingValues(0.dp),
            onCardClick = {},
            actions = PatternActionCallbacks(onDrop = {}, onSkip = {}, onRestart = {}),
        )
    }
}

// Mirrors the POC's `traceHits` for the Tuesday Meetings sample so the @Preview matches.
private val PREVIEW_TRACE_HITS = setOf(3, 10, 17, 24, 26, 28)
