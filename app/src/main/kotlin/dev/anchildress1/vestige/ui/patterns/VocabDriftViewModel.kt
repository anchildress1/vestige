package dev.anchildress1.vestige.ui.patterns

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.anchildress1.vestige.model.PatternKind
import dev.anchildress1.vestige.storage.EntryStore
import dev.anchildress1.vestige.storage.PatternStore
import dev.anchildress1.vestige.storage.VocabCluster
import dev.anchildress1.vestige.storage.VocabClustersCodec
import dev.anchildress1.vestige.storage.vocabRootTokenOrNull
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Loads a single `VOCAB_FREQUENCY` pattern's persisted clusters + resolves example snippets. */
class VocabDriftViewModel(
    private val patternId: String,
    private val patternStore: PatternStore,
    private val entryStore: EntryStore,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : ViewModel() {

    private val _state = MutableStateFlow<VocabDriftUiState>(VocabDriftUiState.Loading)
    val state: StateFlow<VocabDriftUiState> = _state.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            _state.value = loadState()
        }
    }

    private suspend fun loadState(): VocabDriftUiState = withContext(ioDispatcher) {
        val pattern = patternStore.findByPatternId(patternId) ?: return@withContext VocabDriftUiState.NotFound
        if (pattern.kind != PatternKind.VOCAB_FREQUENCY) return@withContext VocabDriftUiState.NotFound
        val clusters = VocabClustersCodec.decode(pattern.vocabClustersJson)
        if (clusters.isEmpty()) return@withContext VocabDriftUiState.NotYetClustered

        val rootToken = vocabRootTokenOrNull(pattern.signatureJson) ?: run {
            Log.e(TAG, "vocab-frequency pattern missing token pid=${pattern.patternId}")
            return@withContext VocabDriftUiState.NotFound
        }
        VocabDriftUiState.Loaded(
            patternTitle = pattern.title,
            rootToken = rootToken,
            totalEntries = clusters.sumOf { it.memberEntryIds.size },
            clusters = clusters.map { it.toUiModel() },
        )
    }

    private fun VocabCluster.toUiModel(): VocabClusterUiModel {
        val entry = entryStore.readEntry(exampleEntryId)
        if (entry == null) {
            // Stale cluster row — example entry was deleted out from under us. Log + render
            // blank snippet rather than crash; user still sees the label + count.
            Log.w(TAG, "vocab cluster example entry missing pid=$patternId cid=$clusterId eid=$exampleEntryId")
        }
        return VocabClusterUiModel(
            clusterId = clusterId,
            label = label,
            description = description,
            exampleSnippet = entry?.entryText?.let(::snippetOf).orEmpty(),
            memberCount = memberEntryIds.size,
        )
    }

    private fun snippetOf(text: String): String {
        val cleaned = text.trim().replace(WHITESPACE_RUN, " ")
        return if (cleaned.length <= MAX_SNIPPET_CHARS) cleaned else cleaned.take(MAX_SNIPPET_CHARS - 1) + "…"
    }

    private companion object {
        const val TAG: String = "VestigeVocabDriftVM"
        const val MAX_SNIPPET_CHARS: Int = 140
        val WHITESPACE_RUN: Regex = Regex("""\s+""")
    }
}
