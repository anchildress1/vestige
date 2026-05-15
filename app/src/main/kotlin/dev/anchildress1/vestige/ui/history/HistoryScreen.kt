package dev.anchildress1.vestige.ui.history

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsBottomHeight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.anchildress1.vestige.model.Persona
import dev.anchildress1.vestige.ui.components.AppTop
import dev.anchildress1.vestige.ui.components.AppTopStatuses
import dev.anchildress1.vestige.ui.components.EyebrowE
import dev.anchildress1.vestige.ui.components.StatItem
import dev.anchildress1.vestige.ui.components.StatRibbon
import dev.anchildress1.vestige.ui.theme.VestigeTheme

@Suppress("LongMethod")
@Composable
fun HistoryScreen(viewModel: HistoryViewModel, persona: Persona, modifier: Modifier = Modifier) {
    val uiState by viewModel.state.collectAsStateWithLifecycle()
    val colors = VestigeTheme.colors

    Column(modifier = modifier.fillMaxSize().background(colors.floor)) {
        AppTop(persona = persona.name, status = AppTopStatuses.Ready)

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 18.dp, vertical = 8.dp),
        ) {
            EyebrowE(
                text = HistoryCopy.EYEBROW,
                modifier = Modifier.semantics { contentDescription = HistoryCopy.EYEBROW },
            )
        }

        Text(
            text = HistoryCopy.HEADING,
            style = VestigeTheme.typography.displayBig,
            color = colors.ink,
            modifier = Modifier.padding(horizontal = 18.dp, vertical = 4.dp),
        )

        when {
            uiState.loading -> Unit

            uiState.entries.isEmpty() -> {
                HistoryEmptyState(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 20.dp, vertical = 24.dp),
                )
                Spacer(Modifier.windowInsetsBottomHeight(WindowInsets.navigationBars))
            }

            else -> LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = 16.dp),
            ) {
                // Stats + density bar scroll with the list — full screen height for items
                uiState.stats?.let { stats ->
                    item(key = "stats") {
                        Box(
                            modifier = Modifier
                                .padding(horizontal = 18.dp, vertical = 10.dp)
                                .semantics(mergeDescendants = true) {
                                    val entryWord = if (stats.totalEntries == 1) "entry" else "entries"
                                    contentDescription = "${stats.totalEntries} $entryWord, " +
                                        "${stats.daysTracked} days tracked, " +
                                        "+${stats.thisWeek} this week, " +
                                        "${stats.avgAudioLabel} average per day"
                                },
                        ) {
                            StatRibbon(
                                items = listOf(
                                    StatItem(value = "${stats.totalEntries}", label = "ENTRIES"),
                                    StatItem(value = "${stats.daysTracked}", label = "DAYS"),
                                    StatItem(
                                        value = "${HistoryCopy.THIS_WEEK_PREFIX}${stats.thisWeek}",
                                        label = "THIS WK",
                                        color = colors.lime,
                                    ),
                                    StatItem(value = stats.avgAudioLabel, label = "AVG/DAY"),
                                ),
                            )
                        }
                    }
                    item(key = "density") {
                        DensityBar(
                            buckets = stats.densityBuckets,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 18.dp)
                                .padding(bottom = 8.dp),
                        )
                    }
                }

                uiState.groups.forEach { group ->
                    item(key = "header-${group.dateKey}") {
                        HistorySectionHeader(label = group.headerLabel, count = group.summaries.size)
                    }
                    items(group.summaries, key = { it.id }) { summary ->
                        HistoryRow(
                            summary = summary,
                            durationLabel = HistoryDurationFormatter.format(summary.durationMs),
                            onClick = null,
                        )
                    }
                }

                item(key = "nav-bar-inset") {
                    Spacer(Modifier.windowInsetsBottomHeight(WindowInsets.navigationBars))
                }
            }
        }
    }
}

@Composable
private fun HistorySectionHeader(label: String, count: Int) {
    val colors = VestigeTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(colors.floor)
            .padding(horizontal = 18.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            EyebrowE(text = HistoryCopy.SECTION_COLLAPSE, color = colors.faint)
            EyebrowE(text = label)
        }
        EyebrowE(text = "$count", color = colors.faint)
    }
}

private const val DENSITY_BAR_MIN_FRACTION = 0.06f

@Composable
private fun DensityBar(buckets: List<Int>, modifier: Modifier = Modifier) {
    val colors = VestigeTheme.colors
    val maxCount = buckets.maxOrNull()?.coerceAtLeast(1) ?: 1
    val totalInWindow = buckets.sum()
    Row(
        modifier = modifier
            .height(32.dp)
            .semantics(mergeDescendants = true) {
                val entryWord = if (totalInWindow == 1) "entry" else "entries"
                contentDescription = "$totalInWindow $entryWord in the last 30 days"
            },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        EyebrowE(text = HistoryCopy.DENSITY_LABEL, color = colors.faint)
        Row(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
                .padding(horizontal = 8.dp),
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            buckets.forEach { count ->
                val fraction = count.toFloat() / maxCount
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight(fraction.coerceAtLeast(DENSITY_BAR_MIN_FRACTION))
                        .background(if (count > 0) colors.lime else colors.hair),
                )
            }
        }
        EyebrowE(text = "$totalInWindow ${HistoryCopy.ENTRIES_SUFFIX}", color = colors.faint)
    }
}

@Composable
private fun HistoryEmptyState(modifier: Modifier = Modifier) {
    val colors = VestigeTheme.colors
    Box(modifier = modifier, contentAlignment = Alignment.TopStart) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = HistoryCopy.EMPTY_HEADER,
                style = VestigeTheme.typography.h1,
                color = colors.ink,
            )
            Text(
                text = HistoryCopy.EMPTY_BODY,
                style = VestigeTheme.typography.p,
                color = colors.dim,
            )
        }
    }
}
