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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.anchildress1.vestige.ui.components.EyebrowE
import dev.anchildress1.vestige.ui.components.VestigeListCard
import dev.anchildress1.vestige.ui.components.VestigeListCardInteraction
import dev.anchildress1.vestige.ui.theme.VestigeTheme

@Composable
fun HistoryRow(
    summary: HistorySummary,
    durationLabel: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = VestigeTheme.colors
    val badgeText = if (summary.templateLabel != null) {
        "● #${summary.id} · ${summary.templateLabel.uppercase()}"
    } else {
        "● #${summary.id}"
    }
    val dotColor = if (summary.templateLabel != null) colors.coral else colors.lime
    val a11yDesc = "${summary.timeLabel} · $durationLabel · ${summary.snippet}"

    VestigeListCard(
        modifier = modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = true) { contentDescription = a11yDesc }
            .testTag("history_row"),
        interaction = VestigeListCardInteraction.Click(onClick = onClick),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.Top,
        ) {
            // Left column — time-of-day + duration
            Column(
                modifier = Modifier.width(52.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = summary.timeLabel,
                    style = VestigeTheme.typography.eyebrow.copy(fontSize = 13.sp, letterSpacing = 0.08.sp),
                    color = colors.ink,
                )
                EyebrowE(text = durationLabel)
            }

            Spacer(modifier = Modifier.width(12.dp))

            // Right column — badge + snippet
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                EyebrowE(text = badgeText, color = dotColor)
                Text(
                    text = summary.snippet,
                    style = VestigeTheme.typography.pCompact,
                    color = colors.ink,
                    maxLines = 2,
                )
            }
        }
    }
}
