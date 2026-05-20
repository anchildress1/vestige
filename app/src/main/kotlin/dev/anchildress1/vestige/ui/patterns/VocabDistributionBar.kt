package dev.anchildress1.vestige.ui.patterns

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.anchildress1.vestige.ui.theme.VestigeTheme

/**
 * Stacked horizontal proportion bar — one weighted segment per vocab cluster. Used on the
 * Vocab Drift surface to show how the supporting entries of a `VOCAB_FREQUENCY` pattern split
 * across distinct framings. Segments adopt accent colors in the Scoreboard palette order
 * (lime → coral → teal → ember), wrapping past index 3.
 *
 * Bar height defaults to 16.dp — meaningful glyph, not a hairline. Hairlines belong to the
 * Scoreboard tick rules; this is a body element.
 *
 * A single merged [contentDescription] is set on the bar's outer `Row` so the entire glyph
 * announces as one element to screen readers (matching the AGENTS.md band a11y rule for
 * status surfaces) rather than 3+ unlabeled boxes.
 */
@Composable
fun VocabDistributionBar(
    segments: List<VocabDistributionSegment>,
    modifier: Modifier = Modifier,
    height: Dp = VocabDistributionBarDefaults.Height,
) {
    require(segments.isNotEmpty()) { "VocabDistributionBar requires at least one segment" }
    val totalWeight = segments.sumOf { it.weight.toDouble() }.toFloat()
    require(totalWeight > 0f) { "VocabDistributionBar weights must sum > 0 (got $totalWeight)" }
    val description = composeDescription(segments, totalWeight)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(height)
            .clip(VestigeTheme.shapes.xs)
            .semantics(mergeDescendants = true) { contentDescription = description }
            .testTag(TEST_TAG),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        segments.forEachIndexed { index, segment ->
            Box(
                modifier = Modifier
                    .weight(segment.weight)
                    .fillMaxHeight()
                    .background(accentForIndex(index)),
            )
        }
    }
}

@Composable
private fun accentForIndex(index: Int): Color {
    // Wrap past the 4-color band — defensive only; the orchestrator's vocab pass produces a
    // handful of clusters in practice, not dozens.
    val accents = listOf(
        VestigeTheme.colors.lime,
        VestigeTheme.colors.coral,
        VestigeTheme.colors.teal,
        VestigeTheme.colors.ember,
    )
    return accents[index % accents.size]
}

private fun composeDescription(segments: List<VocabDistributionSegment>, totalWeight: Float): String {
    val parts = segments.map { segment ->
        val pct = ((segment.weight / totalWeight) * PERCENT_SCALE).toInt()
        "${segment.label}: $pct%"
    }
    return "Vocabulary distribution: ${parts.joinToString(", ")}."
}

/**
 * One cluster's slice. [weight] is normalized at render — pass raw member counts or precomputed
 * proportions; the bar handles the division.
 */
@Immutable
data class VocabDistributionSegment(
    val label: String,
    val weight: Float,
)

object VocabDistributionBarDefaults {
    val Height: Dp = 16.dp
}

private const val PERCENT_SCALE: Float = 100f
private const val TEST_TAG: String = "VocabDistributionBar"
