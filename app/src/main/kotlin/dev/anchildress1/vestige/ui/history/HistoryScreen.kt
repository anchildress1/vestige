package dev.anchildress1.vestige.ui.history

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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
import dev.anchildress1.vestige.ui.components.VestigeBottomNav
import dev.anchildress1.vestige.ui.components.accentedHeadline
import dev.anchildress1.vestige.ui.theme.VestigeTheme

@Composable
@Suppress("LongParameterList") // Screen seam: vm + persona + entry/nav/menu callbacks + modifier.
fun HistoryScreen(
    viewModel: HistoryViewModel,
    persona: Persona,
    onEntryClick: (Long) -> Unit = {},
    onNavSelect: (BottomTab) -> Unit = {},
    onMenuTap: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val uiState by viewModel.state.collectAsStateWithLifecycle()
    val colors = VestigeTheme.colors

    Column(modifier = modifier.fillMaxSize().background(colors.floor)) {
        AppTop(persona = persona.name, status = AppTopStatuses.Ready, onMenuTap = onMenuTap)

        Text(
            text = buildAnnotatedString {
                withStyle(SpanStyle(color = colors.ink)) { append(HistoryCopy.HEADING) }
                withStyle(SpanStyle(color = colors.coral)) { append(".") }
            },
            style = VestigeTheme.typography.displayBig,
            modifier = Modifier.padding(horizontal = 18.dp, vertical = 12.dp),
        )

        when {
            uiState.loading -> Box(Modifier.weight(1f))

            uiState.entries.isEmpty() -> HistoryEmptyState(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 24.dp),
            )

            else -> LazyColumn(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentPadding = PaddingValues(horizontal = 18.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(uiState.entries, key = { it.id }) { summary ->
                    HistoryRow(summary = summary, onClick = { onEntryClick(summary.id) })
                }
            }
        }

        VestigeBottomNav(active = BottomTab.HISTORY, onSelect = onNavSelect)
    }
}

@Composable
private fun HistoryEmptyState(modifier: Modifier = Modifier) {
    val colors = VestigeTheme.colors
    // Empty surface = a status band: politely announced, no role, no click action
    // (AGENTS.md band a11y rule — same shape as the Pattern-list empty states).
    Box(
        modifier = modifier.semantics(mergeDescendants = true) {
            liveRegion = LiveRegionMode.Polite
            contentDescription = "${HistoryCopy.EMPTY_HEADER} ${HistoryCopy.EMPTY_BODY}"
        },
        contentAlignment = Alignment.TopStart,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = accentedHeadline(HistoryCopy.EMPTY_HEADER, colors.ink, colors.coral),
                style = VestigeTheme.typography.displayBig,
            )
            Text(text = HistoryCopy.EMPTY_BODY, style = VestigeTheme.typography.p, color = colors.dim)
        }
    }
}
