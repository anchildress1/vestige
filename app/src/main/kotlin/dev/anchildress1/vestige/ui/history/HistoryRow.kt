package dev.anchildress1.vestige.ui.history

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.anchildress1.vestige.ui.components.EyebrowE
import dev.anchildress1.vestige.ui.components.VestigeListCard
import dev.anchildress1.vestige.ui.components.VestigeListCardInteraction
import dev.anchildress1.vestige.ui.theme.VestigeTheme

private const val MS_PER_SECOND = 1_000L

@Composable
fun HistoryRow(summary: HistorySummary, onClick: (() -> Unit)?, modifier: Modifier = Modifier) {
    val colors = VestigeTheme.colors
    val seconds = summary.durationMs / MS_PER_SECOND
    val meta = "$seconds · ${summary.wordCount} WORDS"
    val a11yDesc = "${summary.timeLabel} ${summary.dateLabel} · ${summary.snippet} · " +
        "$seconds seconds · ${summary.wordCount} words"

    VestigeListCard(
        modifier = modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = true) { contentDescription = a11yDesc }
            .testTag("history_row"),
        interaction = if (onClick != null) {
            VestigeListCardInteraction.Click(onClick = onClick)
        } else {
            VestigeListCardInteraction.Static
        },
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 14.dp),
            verticalAlignment = Alignment.Top,
        ) {
            HistoryRowTimeRail(timeLabel = summary.timeLabel, dateLabel = summary.dateLabel)

            Spacer(modifier = Modifier.width(14.dp))

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = summary.snippet,
                    style = VestigeTheme.typography.p,
                    color = colors.ink,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                EyebrowE(text = meta)
            }
        }
    }
}

/**
 * Fixed-width left rail (12-hour clock + date). The fixed width keeps the snippet column
 * aligned across rows; single-line + no soft-wrap stops "11:02 PM" from wrapping.
 */
@Composable
private fun HistoryRowTimeRail(timeLabel: String, dateLabel: String) {
    val colors = VestigeTheme.colors
    Column(
        modifier = Modifier.width(88.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            text = timeLabel,
            style = VestigeTheme.typography.title,
            color = colors.ink,
            maxLines = 1,
            softWrap = false,
        )
        EyebrowE(text = dateLabel)
    }
}
