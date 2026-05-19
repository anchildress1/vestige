package dev.anchildress1.vestige.ui.patterns

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.anchildress1.vestige.model.Persona
import dev.anchildress1.vestige.ui.components.AppTop
import dev.anchildress1.vestige.ui.components.AppTopStatuses
import dev.anchildress1.vestige.ui.components.BottomTab
import dev.anchildress1.vestige.ui.components.StatItem
import dev.anchildress1.vestige.ui.components.StatRibbon
import dev.anchildress1.vestige.ui.components.VestigeBottomNav
import dev.anchildress1.vestige.ui.components.accentedHeadline
import dev.anchildress1.vestige.ui.theme.VestigeTheme
import java.util.Locale

private const val PATTERN_WINDOW_DAYS = "30"

@Composable
@Suppress("LongParameterList") // Screen seam: vm + open/persona/nav/menu callbacks + modifier.
fun PatternsListScreen(
    viewModel: PatternsListViewModel,
    onOpenPattern: (String) -> Unit,
    persona: Persona = Persona.WITNESS,
    onNavSelect: (BottomTab) -> Unit = {},
    onMenuTap: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbarHostState = rememberPatternSnackbarHostState(viewModel.events, viewModel::undo)
    val colors = VestigeTheme.colors

    Box(modifier = modifier.fillMaxSize().background(colors.floor)) {
        Column(modifier = Modifier.fillMaxSize()) {
            AppTop(persona = persona.name, status = AppTopStatuses.Ready, onMenuTap = onMenuTap)
            Text(
                text = buildAnnotatedString {
                    withStyle(SpanStyle(color = colors.ink)) { append("PATTERNS") }
                    withStyle(SpanStyle(color = colors.coral)) { append(".") }
                },
                style = VestigeTheme.typography.displayBig,
                modifier = Modifier.padding(horizontal = 18.dp, vertical = 12.dp),
            )
            PatternsStatRibbon(state)
            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                when (val s = state) {
                    PatternsListUiState.Loading -> Unit

                    is PatternsListUiState.Empty -> PatternsEmptyState(s)

                    is PatternsListUiState.Loaded -> PatternsLoadedList(
                        cards = s.cards,
                        onCardClick = onOpenPattern,
                        actions = PatternActionCallbacks(
                            onDrop = viewModel::drop,
                            onSkip = viewModel::skip,
                            onRestart = viewModel::restart,
                        ),
                    )
                }
            }
            VestigeBottomNav(active = BottomTab.PATTERNS, onSelect = onNavSelect)
        }
        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = SNACKBAR_NAV_CLEARANCE),
        ) {
            PatternSnackbarHost(snackbarHostState)
        }
    }
}

private val SNACKBAR_NAV_CLEARANCE = 84.dp

@Composable
private fun PatternsStatRibbon(state: PatternsListUiState) {
    val loaded = state as? PatternsListUiState.Loaded
    val vestiges = loaded?.cards?.size ?: 0
    val entries = when (state) {
        is PatternsListUiState.Empty -> state.entryCount
        is PatternsListUiState.Loaded -> state.cards.firstOrNull()?.totalEntryCount?.toInt() ?: 0
        PatternsListUiState.Loading -> 0
    }
    Box(
        modifier = Modifier
            .padding(horizontal = 18.dp, vertical = 8.dp)
            .semantics(mergeDescendants = true) {
                contentDescription =
                    "$vestiges vestiges, $entries entries, last $PATTERN_WINDOW_DAYS days"
            },
    ) {
        StatRibbon(
            items = listOf(
                StatItem(value = "$vestiges", label = "VESTIGES"),
                StatItem(value = "$entries", label = "ENTRIES"),
                StatItem(value = PATTERN_WINDOW_DAYS, label = "DAYS"),
            ),
        )
    }
}

@Composable
private fun PatternsEmptyState(empty: PatternsListUiState.Empty) {
    val colors = VestigeTheme.colors
    val copy = emptyCopyFor(empty.reason)
    val header = stringResource(copy.headerRes)
    val body = stringResource(copy.bodyRes)
    // Status band per AGENTS.md: announced politely, no click action, single merged description
    // in the spoken (sentence-case) form — the display uppercases purely visually.
    Column(
        modifier = Modifier
            .fillMaxSize()
            .semantics(mergeDescendants = true) {
                liveRegion = LiveRegionMode.Polite
                contentDescription = "$header $body"
            }
            .padding(horizontal = 18.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Text(text = accentedHeadline(header, colors.ink, colors.lime), style = VestigeTheme.typography.displayBig)
        Text(
            text = body,
            style = VestigeTheme.typography.p,
            color = colors.dim,
            modifier = Modifier.padding(top = 16.dp),
        )
    }
}

@Composable
private fun PatternsLoadedList(
    cards: List<PatternCardUi>,
    onCardClick: (String) -> Unit,
    actions: PatternActionCallbacks<String>,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // Section order is the PatternSection declaration order per spec §P0.4:
        // ACTIVE → SKIPPED → CLOSED → DROPPED. Empty sections render no header.
        val grouped = cards.groupBy { it.section }
        PatternSection.entries.forEach { section ->
            val sectionCards = grouped[section].orEmpty()
            if (sectionCards.isEmpty()) return@forEach
            item(key = "header-${section.name}") {
                SectionHeader(section = section)
            }
            items(sectionCards, key = { it.patternId }) { card ->
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

@Preview
@Composable
private fun PatternsListPreview() {
    VestigeTheme {
        PatternsLoadedList(
            cards = listOf(
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
            onCardClick = {},
            actions = PatternActionCallbacks(onDrop = {}, onSkip = {}, onRestart = {}),
        )
    }
}

// Mirrors the POC's `traceHits` for the Tuesday Meetings sample so the @Preview matches.
private val PREVIEW_TRACE_HITS = setOf(3, 10, 17, 24, 26, 28)
