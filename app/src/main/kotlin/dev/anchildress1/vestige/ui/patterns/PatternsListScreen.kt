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
                    // Screen headline carries the brand; the nav tab + section headers stay
                    // the functional "Patterns" word (function in navigation, brand in headings).
                    withStyle(SpanStyle(color = colors.ink)) { append("VESTIGES") }
                    withStyle(SpanStyle(color = colors.coral)) { append(".") }
                },
                style = VestigeTheme.typography.displayBig,
                modifier = Modifier.padding(horizontal = 18.dp, vertical = 12.dp),
            )
            // Stats ribbon shows only when there is data to read — matches History's headline-
            // then-content shape; an empty surface stays a single status band, not "0 of 0."
            (state as? PatternsListUiState.Loaded)?.let { PatternsStatRibbon(it) }
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
private fun PatternsStatRibbon(loaded: PatternsListUiState.Loaded) {
    val vestiges = loaded.cards.size
    val entries = loaded.entryCount
    val days = loaded.daysSinceFirstCapped
    Box(
        modifier = Modifier
            .padding(horizontal = 18.dp, vertical = 8.dp)
            .semantics(mergeDescendants = true) {
                contentDescription = "$vestiges vestiges, $entries entries, last $days days"
            },
    ) {
        StatRibbon(
            items = listOf(
                StatItem(value = "$vestiges", label = "VESTIGES"),
                StatItem(value = "$entries", label = "ENTRIES"),
                StatItem(value = "$days", label = "DAYS"),
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
    // Top-aligned form mirrors HistoryScreen's empty state — headline sits under the page title,
    // not floated in the middle, so the two surfaces feel the same when there is no data.
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp, vertical = 24.dp)
            .semantics(mergeDescendants = true) {
                liveRegion = LiveRegionMode.Polite
                contentDescription = "$header $body"
            },
        contentAlignment = Alignment.TopStart,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(text = accentedHeadline(header, colors.ink, colors.lime), style = VestigeTheme.typography.displayBig)
            Text(text = body, style = VestigeTheme.typography.p, color = colors.dim)
        }
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
