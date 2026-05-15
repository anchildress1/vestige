package dev.anchildress1.vestige.ui.history

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.anchildress1.vestige.ui.components.EyebrowE
import dev.anchildress1.vestige.ui.components.VestigeScaffold
import dev.anchildress1.vestige.ui.theme.VestigeTheme
import java.time.ZoneId

@Suppress("LongMethod")
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun HistoryScreen(viewModel: HistoryViewModel, onBack: () -> Unit, zoneId: ZoneId, modifier: Modifier = Modifier) {
    val uiState by viewModel.state.collectAsState()
    val nowEpochMs = System.currentTimeMillis()

    VestigeScaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "History",
                        style = MaterialTheme.typography.titleMedium,
                    )
                },
                navigationIcon = {
                    IconButton(
                        onClick = onBack,
                        modifier = Modifier.semantics { contentDescription = "Back" },
                    ) {
                        Text(
                            text = "←",
                            style = MaterialTheme.typography.titleLarge,
                        )
                    }
                },
            )
        },
    ) { padding ->
        when {
            uiState.loading -> Unit

            uiState.entries.isEmpty() -> HistoryEmptyState(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(horizontal = 20.dp, vertical = 24.dp),
            )

            else -> LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(uiState.entries, key = { it.id }) { summary ->
                    val dateLabel = HistoryDateFormatter.format(
                        timestampEpochMs = summary.timestampEpochMs,
                        nowEpochMs = nowEpochMs,
                        zoneId = zoneId,
                    )
                    val durationLabel = HistoryDurationFormatter.format(summary.durationMs)
                    HistoryRow(
                        summary = summary,
                        dateLabel = dateLabel,
                        durationLabel = durationLabel,
                        onClick = { /* Entry detail is Story 4.7 — no-op stub */ },
                    )
                }
            }
        }
    }
}

@Composable
private fun HistoryEmptyState(modifier: Modifier = Modifier) {
    val colors = VestigeTheme.colors
    Box(modifier = modifier, contentAlignment = Alignment.TopStart) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            EyebrowE(text = "HISTORY")
            Text(
                text = "No entries yet.",
                style = VestigeTheme.typography.h1,
                color = colors.ink,
            )
            Text(
                text = "First one takes 30 seconds.",
                style = VestigeTheme.typography.p,
                color = colors.dim,
            )
        }
    }
}
