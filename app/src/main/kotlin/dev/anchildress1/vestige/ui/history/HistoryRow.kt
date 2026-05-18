package dev.anchildress1.vestige.ui.history

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.anchildress1.vestige.ui.components.EyebrowE
import dev.anchildress1.vestige.ui.components.VestigeListCard
import dev.anchildress1.vestige.ui.components.VestigeListCardInteraction
import dev.anchildress1.vestige.ui.theme.VestigeTheme

/**
 * History list row — the entry's timestamp only (12-hour clock + date beneath). The entry's
 * own content is intentionally not surfaced on the History view (visible or to a11y); tapping
 * the row opens the full entry.
 */
@Composable
fun HistoryRow(summary: HistorySummary, onClick: (() -> Unit)?, modifier: Modifier = Modifier) {
    val colors = VestigeTheme.colors
    VestigeListCard(
        modifier = modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = true) {
                contentDescription = "${summary.timeLabel}, ${summary.dateLabel}"
            }
            .testTag("history_row"),
        interaction = if (onClick != null) {
            VestigeListCardInteraction.Click(onClick = onClick)
        } else {
            VestigeListCardInteraction.Static
        },
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = summary.timeLabel,
                style = VestigeTheme.typography.title,
                color = colors.ink,
                maxLines = 1,
                softWrap = false,
            )
            EyebrowE(text = summary.dateLabel)
        }
    }
}
