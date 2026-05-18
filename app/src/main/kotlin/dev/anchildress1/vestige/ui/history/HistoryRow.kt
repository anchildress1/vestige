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
import androidx.compose.ui.unit.sp
import dev.anchildress1.vestige.ui.components.EyebrowE
import dev.anchildress1.vestige.ui.components.VestigeListCard
import dev.anchildress1.vestige.ui.components.VestigeListCardInteraction
import dev.anchildress1.vestige.ui.theme.VestigeTheme

@Composable
fun HistoryRow(summary: HistorySummary, durationLabel: String, onClick: (() -> Unit)?, modifier: Modifier = Modifier) {
    val colors = VestigeTheme.colors
    val badgeText = if (summary.templateLabel != null) {
        "● #${summary.id} · ${summary.templateLabel.uppercase()}"
    } else {
        "● #${summary.id}"
    }
    val dotColor = if (summary.templateLabel != null) colors.coral else colors.lime
    val a11yDesc = buildString {
        append("${summary.timeLabel} · $durationLabel")
        if (summary.templateLabel != null) append(" · ${summary.templateLabel}")
        append(" · ${summary.snippet}")
    }

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
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.Top,
        ) {
            HistoryRowTimeRail(timeLabel = summary.timeLabel, durationLabel = durationLabel)

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
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/**
 * Fixed-width left rail (time-of-day + duration). The fixed width keeps the snippet column
 * aligned across rows; single-line + no soft-wrap stops longer durations ("12m 30s") from
 * dropping their last glyph onto a second line.
 */
@Composable
private fun HistoryRowTimeRail(timeLabel: String, durationLabel: String) {
    val colors = VestigeTheme.colors
    Column(
        modifier = Modifier.width(64.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            text = timeLabel,
            style = VestigeTheme.typography.eyebrow.copy(fontSize = 13.sp, letterSpacing = 0.08.sp),
            color = colors.ink,
            maxLines = 1,
            softWrap = false,
        )
        Text(
            text = durationLabel,
            style = VestigeTheme.typography.eyebrow,
            color = colors.dim,
            maxLines = 1,
            softWrap = false,
        )
    }
}
