package dev.anchildress1.vestige.ui.patterns

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.anchildress1.vestige.storage.EntryStore
import dev.anchildress1.vestige.storage.PatternStore
import dev.anchildress1.vestige.storage.VocabCluster
import dev.anchildress1.vestige.storage.VocabClustersCodec
import dev.anchildress1.vestige.model.PatternKind
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * Loads a single `VOCAB_FREQUENCY` pattern + its persisted clusters. No clustering happens
 * here — the orchestrator already stamped `vocabClustersJson`. This VM just decodes, resolves
 * each cluster's example entry into a UI-ready snippet, and surfaces the result.
 *
 * Construction mirrors [PatternDetailViewModel] — patternId is constructor-injected, the host
 * lays the VM down with `remember(patternId, …)` so it tears down on back-navigation.
 */
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
        if (clusters.isEmpty()) return@withContext VocabDriftUiState.NotFound

        val rootToken = runCatching {
            JSONObject(pattern.signatureJson).optString("token", "")
        }.getOrDefault("")
        val totalEntries = clusters.sumOf { it.memberEntryIds.size }
        val uiClusters = clusters.map { it.toUiModel() }

        VocabDriftUiState.Loaded(
            patternTitle = pattern.title,
            rootToken = rootToken,
            totalEntries = totalEntries,
            clusters = uiClusters,
        )
    }

    private fun VocabCluster.toUiModel(): VocabClusterUiModel {
        val snippet = entryStore.readEntry(exampleEntryId)
            ?.entryText
            ?.let { snippetOf(it) }
            .orEmpty()
        return VocabClusterUiModel(
            clusterId = clusterId,
            label = label,
            description = description,
            exampleSnippet = snippet,
            memberCount = memberEntryIds.size,
        )
    }

    private fun snippetOf(text: String): String {
        val cleaned = text.trim().replace(WHITESPACE_RUN, " ")
        return if (cleaned.length <= MAX_SNIPPET_CHARS) cleaned else cleaned.take(MAX_SNIPPET_CHARS - 1) + "…"
    }

    private companion object {
        const val MAX_SNIPPET_CHARS: Int = 140
        val WHITESPACE_RUN: Regex = Regex("""\s+""")
    }
}
