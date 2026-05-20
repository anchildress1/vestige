@file:Suppress("TooManyFunctions") // Screen split into small private composables — clarity over a god-function.

package dev.anchildress1.vestige.ui.patterns

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.anchildress1.vestige.ui.components.AppTop
import dev.anchildress1.vestige.ui.components.AppTopStatuses
import dev.anchildress1.vestige.ui.components.EyebrowE
import dev.anchildress1.vestige.ui.theme.VestigeTheme

/** Vocab Drift surface — renders persisted clusters for a single VOCAB_FREQUENCY pattern. */
@Composable
fun VocabDriftScreen(viewModel: VocabDriftViewModel, onBack: () -> Unit, modifier: Modifier = Modifier) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val colors = VestigeTheme.colors
    Column(modifier = modifier.fillMaxSize().background(colors.floor)) {
        AppTop(persona = "", status = AppTopStatuses.Ready, onMenuTap = onBack)
        when (val s = state) {
            VocabDriftUiState.Loading -> Spacer(Modifier.weight(1f))

            VocabDriftUiState.NotFound -> StatusBandBody(
                text = VocabDriftCopy.NOT_FOUND,
                testTag = VocabDriftTestTags.NOT_FOUND_BAND,
                modifier = Modifier.weight(1f),
            )

            VocabDriftUiState.NotYetClustered -> StatusBandBody(
                text = VocabDriftCopy.NOT_YET_CLUSTERED,
                testTag = VocabDriftTestTags.NOT_YET_CLUSTERED_BAND,
                modifier = Modifier.weight(1f),
            )

            is VocabDriftUiState.Loaded -> VocabDriftBody(
                state = s,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun StatusBandBody(text: String, testTag: String, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 24.dp)
            .semantics(mergeDescendants = true) {
                contentDescription = text
                liveRegion = LiveRegionMode.Polite
            }
            .testTag(testTag),
        contentAlignment = Alignment.TopStart,
    ) {
        Text(text = text, style = VestigeTheme.typography.p, color = VestigeTheme.colors.dim)
    }
}

@Composable
private fun VocabDriftBody(state: VocabDriftUiState.Loaded, modifier: Modifier = Modifier) {
    val colors = VestigeTheme.colors
    Column(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Header(state)
        VocabDistributionBar(segments = state.clusters.map { it.toSegment() })
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            state.clusters.forEachIndexed { index, cluster ->
                ClusterCard(cluster = cluster, accent = vocabAccentForIndex(index, colors))
            }
        }
    }
}

@Composable
private fun Header(state: VocabDriftUiState.Loaded) {
    val colors = VestigeTheme.colors
    val rootDisplay = if (state.rootToken.isNotBlank()) "\"${state.rootToken}\"" else state.patternTitle
    val headlineText = "$rootDisplay × ${state.totalEntries}"
    val subtext = VocabDriftCopy.subtitle(state.clusters.size)
    Column(
        modifier = Modifier.semantics(mergeDescendants = true) {
            contentDescription = "$headlineText. $subtext"
        },
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        EyebrowE(text = VocabDriftCopy.EYEBROW)
        Text(text = headlineText, style = VestigeTheme.typography.h2, color = colors.ink)
        Text(text = subtext, style = VestigeTheme.typography.pCompact, color = colors.dim)
    }
}

@Composable
private fun ClusterCard(cluster: VocabClusterUiModel, accent: Color) {
    val colors = VestigeTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .border(width = 1.dp, color = colors.hair)
            .background(colors.deep)
            .padding(start = 0.dp)
            .testTag(VocabDriftTestTags.clusterCardTag(cluster.clusterId)),
    ) {
        Box(modifier = Modifier.size(width = 4.dp, height = MIN_CARD_HEIGHT).background(accent))
        Column(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(text = cluster.label, style = VestigeTheme.typography.title, color = colors.ink)
            Text(text = cluster.description, style = VestigeTheme.typography.pCompact, color = colors.dim)
            if (cluster.exampleSnippet.isNotBlank()) {
                Text(
                    text = "\"${cluster.exampleSnippet}\"",
                    style = VestigeTheme.typography.pCompact,
                    color = colors.faint,
                )
            }
        }
    }
}

private fun VocabClusterUiModel.toSegment(): VocabDistributionSegment =
    VocabDistributionSegment(label = label, weight = memberCount.toFloat())

private val MIN_CARD_HEIGHT = 64.dp

object VocabDriftCopy {
    const val EYEBROW: String = "VOCAB DRIFT"
    const val NOT_FOUND: String =
        "Vocab drift isn't available for this pattern."
    const val NOT_YET_CLUSTERED: String =
        "Not enough evidence yet. Vocab drift surfaces after the model finds at least six related entries."

    fun subtitle(clusterCount: Int): String = when (clusterCount) {
        1 -> "One framing across these entries — vocabulary stayed consistent."
        else -> "$clusterCount distinct framings of the same underlying state."
    }
}

object VocabDriftTestTags {
    const val NOT_FOUND_BAND: String = "VocabDrift_NotFound"
    const val NOT_YET_CLUSTERED_BAND: String = "VocabDrift_NotYetClustered"
    private const val ID_PREFIX_CHARS: Int = 8

    fun clusterCardTag(clusterId: String): String = "VocabDrift_Cluster_${clusterId.take(ID_PREFIX_CHARS)}"
}
