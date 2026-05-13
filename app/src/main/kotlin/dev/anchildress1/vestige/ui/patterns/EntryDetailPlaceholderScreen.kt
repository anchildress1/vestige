package dev.anchildress1.vestige.ui.patterns

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import dev.anchildress1.vestige.ui.components.VestigeSurface
import dev.anchildress1.vestige.ui.theme.VestigeTheme
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
// Single Compose layout function; further splitting churns the diff without clarifying the shape.
@Suppress("LongMethod")
@Composable
fun EntryDetailPlaceholderScreen(
    entryId: Long,
    entryStore: EntryStore,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    // Hoisted so tests / Phase 4 nav can inject a deterministic dispatcher; defaults to IO for
    // the production ObjectBox read.
    ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    val state by produceState<EntryDetailPlaceholderUiState>(
        initialValue = EntryDetailPlaceholderUiState.Loading,
        key1 = entryId,
        key2 = entryStore,
    ) {
        value = withContext(ioDispatcher) {
            entryStore.readEntry(entryId)?.toUiState() ?: EntryDetailPlaceholderUiState.NotFound
        }
    }

    // Theme-owned colors — M3 default `colorScheme.background` + `onBackground`.
    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(text = stringResource(id = R.string.entry_detail_placeholder_title)) },
                navigationIcon = {
                    val backDescription = stringResource(R.string.pattern_back_description)
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
                    color = VestigeTheme.colors.dim,
                )
            }

            is EntryDetailPlaceholderUiState.Loaded -> Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(horizontal = 20.dp, vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                VestigeSurface(contentPadding = PaddingValues(16.dp)) {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(text = uiState.dateLabel, style = MaterialTheme.typography.titleMedium)
                        Text(
                            text = stringResource(id = R.string.entry_detail_placeholder_notice),
                            style = MaterialTheme.typography.bodyMedium,
                            color = VestigeTheme.colors.dim,
                        )
                        Text(text = uiState.entryText, style = MaterialTheme.typography.bodyLarge)
                    }
                }
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
