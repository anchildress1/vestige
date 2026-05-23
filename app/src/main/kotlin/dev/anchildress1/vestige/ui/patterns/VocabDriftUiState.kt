package dev.anchildress1.vestige.ui.patterns

import androidx.compose.runtime.Immutable
import kotlinx.collections.immutable.ImmutableList

/** State for the Vocab Drift screen — see [VocabDriftViewModel] for the load path. */
sealed interface VocabDriftUiState {

    object Loading : VocabDriftUiState

    /** Wrong pattern id / wrong kind / missing root token. Surface as an "absent" status band. */
    object NotFound : VocabDriftUiState

    /**
     * Right kind, right id — but the orchestrator's clustering pass hasn't produced clusters
     * yet (supporting set still below the floor, or embeddings still backfilling). The user
     * sees a different, more hopeful copy than [NotFound].
     */
    object NotYetClustered : VocabDriftUiState

    /**
     * Resolved snapshot. [totalEntries] is the sum across [clusters]. [rootToken] is the
     * canonical word the clusters are framings of — the UI uses it as the headline and never
     * repeats it inside a cluster label.
     */
    @Immutable
    data class Loaded(
        val patternTitle: String,
        val rootToken: String,
        val totalEntries: Int,
        val clusters: ImmutableList<VocabClusterUiModel>,
    ) : VocabDriftUiState
}

/** UI projection of a single [dev.anchildress1.vestige.storage.VocabCluster]. */
@Immutable
data class VocabClusterUiModel(
    val clusterId: String,
    val label: String,
    val description: String,
    val exampleSnippet: String,
    val memberCount: Int,
)
