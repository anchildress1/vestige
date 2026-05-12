package dev.anchildress1.vestige.ui.patterns

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.anchildress1.vestige.R
import dev.anchildress1.vestige.storage.EntryEntity
import dev.anchildress1.vestige.storage.EntryStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun EntryDetailPlaceholderScreen(
    entryId: Long,
    entryStore: EntryStore,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by produceState<EntryDetailPlaceholderUiState>(
        initialValue = EntryDetailPlaceholderUiState.Loading,
        key1 = entryId,
        key2 = entryStore,
    ) {
        value = withContext(Dispatchers.IO) {
            entryStore.readEntry(entryId)?.toUiState() ?: EntryDetailPlaceholderUiState.NotFound
        }
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(text = stringResource(id = R.string.entry_detail_placeholder_title)) },
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
    ) { padding ->
        when (val uiState = state) {
            EntryDetailPlaceholderUiState.Loading -> Unit

            EntryDetailPlaceholderUiState.NotFound -> Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = stringResource(id = R.string.entry_detail_placeholder_not_found),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            is EntryDetailPlaceholderUiState.Loaded -> Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(horizontal = 20.dp, vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(text = uiState.dateLabel, style = MaterialTheme.typography.titleMedium)
                Text(
                    text = stringResource(id = R.string.entry_detail_placeholder_notice),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(text = uiState.entryText, style = MaterialTheme.typography.bodyLarge)
            }
        }
    }
}

private sealed interface EntryDetailPlaceholderUiState {
    data object Loading : EntryDetailPlaceholderUiState
    data object NotFound : EntryDetailPlaceholderUiState
    data class Loaded(val dateLabel: String, val entryText: String) : EntryDetailPlaceholderUiState
}

private fun EntryEntity.toUiState(): EntryDetailPlaceholderUiState.Loaded = EntryDetailPlaceholderUiState.Loaded(
    dateLabel = formatShortDate(timestampEpochMs),
    entryText = entryText,
)
