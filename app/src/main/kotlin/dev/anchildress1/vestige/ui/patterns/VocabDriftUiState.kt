package dev.anchildress1.vestige.ui.patterns

/** State for the Vocab Drift screen — see [VocabDriftViewModel] for the load path. */
sealed interface VocabDriftUiState {

    data object Loading : VocabDriftUiState

    /**
     * The pattern row was missing or wasn't a vocab-frequency pattern, or its clusters column
     * was empty / corrupted. Either way the UI shows a brief absent-data line and a back affordance.
     */
    data object NotFound : VocabDriftUiState

    /**
     * Resolved snapshot. [totalEntries] is the sum across [clusters] (every supporting entry
     * landed in some cluster). [rootToken] is the canonical word the clusters are framings of —
     * the UI uses it as the headline; never duplicates it inside a cluster label.
     */
    data class Loaded(
        val patternTitle: String,
        val rootToken: String,
        val totalEntries: Int,
        val clusters: List<VocabClusterUiModel>,
    ) : VocabDriftUiState
}

/**
 * UI projection of a single [dev.anchildress1.vestige.storage.VocabCluster]. The screen never
 * reads ObjectBox entities directly — the ViewModel resolves the example snippet at load time
 * so the renderer stays pure.
 */
data class VocabClusterUiModel(
    val clusterId: String,
    val label: String,
    val description: String,
    val exampleSnippet: String,
    val memberCount: Int,
)
