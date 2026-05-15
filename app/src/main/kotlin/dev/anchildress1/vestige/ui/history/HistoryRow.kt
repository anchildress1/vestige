package dev.anchildress1.vestige.ui.history

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.anchildress1.vestige.ui.components.EyebrowE
import dev.anchildress1.vestige.ui.components.Pill
import dev.anchildress1.vestige.ui.components.VestigeListCard
import dev.anchildress1.vestige.ui.components.VestigeListCardInteraction
import dev.anchildress1.vestige.ui.theme.VestigeTheme
import java.util.Locale

@Composable
fun HistoryRow(
    summary: HistorySummary,
    dateLabel: String,
    durationLabel: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = VestigeTheme.colors
    val a11yDesc = "$dateLabel · $durationLabel · ${summary.snippet}"
    VestigeListCard(
        modifier = modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = true) { contentDescription = a11yDesc }
            .testTag("history_row"),
        interaction = VestigeListCardInteraction.Click(onClick = onClick),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Top,
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                EyebrowE(text = dateLabel)
                if (summary.templateLabel != null) {
                    Pill(
                        text = summary.templateLabel.uppercase(Locale.US),
                        color = colors.lime,
                        fill = true,
                    )
                }
                Text(
                    text = summary.snippet,
                    style = VestigeTheme.typography.p,
                    color = colors.ink,
                )
            }
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = durationLabel,
                style = VestigeTheme.typography.eyebrow,
                color = colors.dim,
            )
        }
    }
}
