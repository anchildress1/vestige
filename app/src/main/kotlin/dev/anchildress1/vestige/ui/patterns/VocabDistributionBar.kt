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
import dev.anchildress1.vestige.ui.theme.VestigeColors
import dev.anchildress1.vestige.ui.theme.VestigeTheme
import kotlin.math.roundToInt

/**
 * Stacked horizontal proportion bar — one weighted segment per vocab cluster. The whole row
 * carries a single merged contentDescription so screen readers announce one informational
 * glyph instead of N unlabeled boxes.
 */
@Composable
fun VocabDistributionBar(
    segments: List<VocabDistributionSegment>,
    modifier: Modifier = Modifier,
    height: Dp = VocabDistributionBarDefaults.Height,
) {
    require(segments.isNotEmpty()) { "VocabDistributionBar requires at least one segment" }
    val totalWeight = segments.sumOf { it.weight.toDouble() }.toFloat()
    val description = composeDescription(segments, totalWeight)
    val colors = VestigeTheme.colors
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
                    .background(vocabAccentForIndex(index, colors)),
            )
        }
    }
}

/**
 * Returns the cluster-card accent for [index]. Wraps past 4 — defensive only; production
 * patterns produce a handful of clusters, not dozens.
 */
internal fun vocabAccentForIndex(index: Int, colors: VestigeColors): Color {
    val accents = listOf(colors.lime, colors.coral, colors.teal, colors.ember)
    return accents[index % accents.size]
}

private fun composeDescription(segments: List<VocabDistributionSegment>, totalWeight: Float): String {
    // Round to nearest, then absorb any rounding drift into the last segment so the announced
    // percentages always sum to 100. Floor-on-each (the previous behavior) under-reports and
    // can leave the user hearing "33%, 33%, 33%" for an even split.
    val rounded = segments.map { (it.weight / totalWeight * PERCENT_SCALE).roundToInt() }.toMutableList()
    val drift = PERCENT_SCALE.toInt() - rounded.sum()
    if (rounded.isNotEmpty()) rounded[rounded.lastIndex] += drift
    val parts = segments.zip(rounded) { segment, pct -> "${segment.label}: $pct%" }
    return "Vocabulary distribution: ${parts.joinToString(", ")}."
}

/** One cluster's slice. [weight] is normalized at render; pass raw member counts or proportions. */
@Immutable
data class VocabDistributionSegment(val label: String, val weight: Float) {
    init {
        require(label.isNotBlank()) { "VocabDistributionSegment.label must be non-blank" }
        require(weight > 0f && weight.isFinite()) {
            "VocabDistributionSegment.weight must be positive finite (got $weight)"
        }
    }
}

object VocabDistributionBarDefaults {
    val Height: Dp = 16.dp
}

private const val PERCENT_SCALE: Float = 100f
private const val TEST_TAG: String = "VocabDistributionBar"
