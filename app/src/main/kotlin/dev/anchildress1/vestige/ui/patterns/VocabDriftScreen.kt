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
import dev.anchildress1.vestige.ui.theme.VestigeColors
import dev.anchildress1.vestige.ui.theme.VestigeTheme

/**
 * Vocab Drift surface — the EmbeddingGemma payoff page. Renders the per-cluster split of a
 * single `VOCAB_FREQUENCY` pattern's supporting entries: one root token (e.g. "tired"),
 * multiple framings (e.g. "exhausted, drained, wiped" / "sluggish, foggy" / "wired-tired,
 * anxious-tired"). The story it tells the user: tag matching saw these 23 entries as one
 * thing; embeddings saw three.
 */
@Composable
fun VocabDriftScreen(viewModel: VocabDriftViewModel, onBack: () -> Unit, modifier: Modifier = Modifier) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val colors = VestigeTheme.colors
    Column(modifier = modifier.fillMaxSize().background(colors.floor)) {
        AppTop(
            persona = "",
            status = AppTopStatuses.Ready,
            onMenuTap = onBack,
        )
        when (val s = state) {
            VocabDriftUiState.Loading -> Spacer(Modifier.weight(1f))

            VocabDriftUiState.NotFound -> NotFoundBody(modifier = Modifier.weight(1f))

            is VocabDriftUiState.Loaded -> VocabDriftBody(
                state = s,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun NotFoundBody(modifier: Modifier = Modifier) {
    // Status band per AGENTS.md: role + contentDescription + liveRegion + no click action.
    val text = VocabDriftCopy.NOT_FOUND
    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 24.dp)
            .semantics(mergeDescendants = true) {
                contentDescription = text
                liveRegion = LiveRegionMode.Polite
            }
            .testTag(VocabDriftTestTags.NOT_FOUND_BAND),
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
        ClusterColumn(clusters = state.clusters, colors = colors)
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
private fun ClusterColumn(clusters: List<VocabClusterUiModel>, colors: VestigeColors) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        clusters.forEachIndexed { index, cluster ->
            ClusterCard(cluster = cluster, accent = accentForIndex(index, colors))
        }
    }
}

@Composable
private fun accentForIndex(index: Int, colors: VestigeColors): Color {
    val accents = listOf(colors.lime, colors.coral, colors.teal, colors.ember)
    return accents[index % accents.size]
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
        "Not enough evidence yet. Vocab drift surfaces after the model finds at least six related entries."

    fun subtitle(clusterCount: Int): String = when (clusterCount) {
        1 -> "One framing across these entries — vocabulary stayed consistent."
        else -> "$clusterCount distinct framings of the same underlying state."
    }
}

object VocabDriftTestTags {
    const val NOT_FOUND_BAND: String = "VocabDrift_NotFound"
    private const val ID_PREFIX_CHARS: Int = 8

    fun clusterCardTag(clusterId: String): String = "VocabDrift_Cluster_${clusterId.take(ID_PREFIX_CHARS)}"
}
