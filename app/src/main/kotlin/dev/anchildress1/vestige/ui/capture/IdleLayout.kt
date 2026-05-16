package dev.anchildress1.vestige.ui.capture

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsBottomHeight
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.anchildress1.vestige.ui.components.AppTop
import dev.anchildress1.vestige.ui.components.EyebrowE
import dev.anchildress1.vestige.ui.components.StatItem
import dev.anchildress1.vestige.ui.components.StatRibbon
import dev.anchildress1.vestige.ui.theme.VestigeTheme

/**
 * Capture screen idle composition. Matches `poc/screenshots/capture-still.png` modulo the
 * deferred patterns peek + footer (out of scope this branch — see plan).
 */
@Suppress("LongMethod", "LongParameterList") // Top-level Compose layout; chrome already bundled.
@Composable
fun IdleLayout(
    state: CaptureUiState.Idle,
    stats: CaptureStats,
    meta: CaptureMeta,
    onRecTap: () -> Unit,
    onTypeTap: () -> Unit,
    modifier: Modifier = Modifier,
    chrome: IdleChromeCallbacks = IdleChromeCallbacks(),
) {
    val colors = VestigeTheme.colors
    Column(modifier = modifier.fillMaxSize().background(colors.floor)) {
        AppTop(
            persona = state.persona.name,
            status = appTopStatusFor(state.modelReadiness),
            onPersonaTap = chrome.onPersonaTap,
            onStatusTap = chrome.onStatusTap,
        )
        DateStrip(meta = meta)
        Box(modifier = Modifier.padding(horizontal = 18.dp, vertical = 12.dp)) {
            StatRibbon(items = stats.toRibbonItems(colors.lime, colors.coral))
        }
        CaptureErrorBand(
            error = state.error,
            readiness = state.modelReadiness,
            modifier = Modifier.padding(horizontal = 18.dp),
        )
        HeroBlock()
        Spacer(modifier = Modifier.height(8.dp))
        Box(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp),
            contentAlignment = Alignment.Center,
        ) {
            val recEnabled = state.modelReadiness is ModelReadiness.Ready
            RecButton(
                onClick = onRecTap,
                enabled = recEnabled,
                contentDescription = CaptureCopy.REC_LABEL_IDLE,
            )
        }
        Spacer(modifier = Modifier.height(18.dp))
        Box(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp),
            contentAlignment = Alignment.Center,
        ) {
            OrTypeButton(onClick = onTypeTap)
        }
        Spacer(modifier = Modifier.weight(1f))
        chrome.onPatternsTap?.let { onTap ->
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 18.dp, vertical = 4.dp),
                contentAlignment = Alignment.Center,
            ) {
                PatternsLink(onClick = onTap)
            }
        }
        chrome.onSettingsTap?.let { onTap ->
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 18.dp, vertical = 4.dp),
                contentAlignment = Alignment.Center,
            ) {
                SettingsLink(onClick = onTap)
            }
        }
        if (chrome.lastEntryFooter != null) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 18.dp, vertical = 12.dp),
                contentAlignment = Alignment.Center,
            ) {
                HistoryFooter(
                    footer = chrome.lastEntryFooter,
                    onHistoryTap = chrome.onHistoryTap,
                )
            }
        } else {
            chrome.onHistoryTap?.let { onTap ->
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 18.dp, vertical = 12.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    HistoryLink(onClick = onTap)
                }
            }
        }
        Spacer(Modifier.windowInsetsBottomHeight(WindowInsets.navigationBars))
    }
}

@Composable
private fun PatternsLink(onClick: () -> Unit) {
    val colors = VestigeTheme.colors
    Box(
        modifier = Modifier
            .clickable(onClick = onClick)
            .semantics(mergeDescendants = true) {
                role = Role.Button
                contentDescription = CaptureCopy.PATTERNS_LINK
            }
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Text(
            text = CaptureCopy.PATTERNS_LINK,
            style = VestigeTheme.typography.personaLabel,
            color = colors.dim,
        )
    }
}

@Composable
private fun SettingsLink(onClick: () -> Unit) {
    val colors = VestigeTheme.colors
    Box(
        modifier = Modifier
            .clickable(onClick = onClick)
            .semantics(mergeDescendants = true) {
                role = Role.Button
                contentDescription = CaptureCopy.SETTINGS_LINK
            }
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Text(
            text = CaptureCopy.SETTINGS_LINK,
            style = VestigeTheme.typography.personaLabel,
            color = colors.dim,
        )
    }
}

@Composable
private fun DateStrip(meta: CaptureMeta) {
    val colors = VestigeTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 18.dp, vertical = 12.dp)
            .drawBehind {
                drawLine(
                    color = colors.hair,
                    start = Offset(0f, size.height),
                    end = Offset(size.width, size.height),
                    strokeWidth = Stroke.HairlineWidth,
                )
            },
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Top,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                text = "NOW · ${meta.weekdayLabel} ${meta.monthDayLabel}",
                style = VestigeTheme.typography.eyebrow,
                color = colors.lime,
            )
            Text(
                text = buildAnnotatedString {
                    append(meta.timeLabel)
                    withStyle(SpanStyle(color = colors.dim)) {
                        append(" · ${CaptureCopy.DAY_PREFIX} ${meta.dayNumber}")
                    }
                },
                style = VestigeTheme.typography.displayBig.copy(fontSize = 32.sp, lineHeight = 30.sp),
                color = colors.ink,
            )
        }
        Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(4.dp)) {
            EyebrowE(text = CaptureCopy.STREAK_LABEL)
            Text(
                text = buildAnnotatedString {
                    withStyle(SpanStyle(color = colors.lime)) { append("${meta.streakDays}") }
                    withStyle(SpanStyle(color = colors.dim, fontSize = 14.sp)) { append(" d") }
                },
                style = VestigeTheme.typography.displayBig.copy(fontSize = 32.sp, lineHeight = 30.sp),
                color = colors.lime,
            )
        }
    }
}

private fun CaptureStats.toRibbonItems(lime: Color, coral: Color): List<StatItem> = listOf(
    StatItem(value = kept.toString(), label = CaptureCopy.STAT_KEPT),
    StatItem(value = active.toString(), label = CaptureCopy.STAT_ACTIVE, color = lime),
    StatItem(value = hitsThisMonth.toString(), label = CaptureCopy.STAT_HITS_MONTH),
    StatItem(value = cloud.toString(), label = CaptureCopy.STAT_CLOUD, color = coral),
)

@Composable
private fun HeroBlock() {
    val colors = VestigeTheme.colors
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = heroAnnotated(
                full = CaptureCopy.HERO_QUESTION,
                highlightSuffix = CaptureCopy.HERO_HIGHLIGHT_FROM_END,
                inkColor = colors.ink,
                accentColor = colors.lime,
            ),
            style = VestigeTheme.typography.displayBig.copy(fontSize = 38.sp, lineHeight = 38.sp),
        )
    }
}

private fun heroAnnotated(full: String, highlightSuffix: String, inkColor: Color, accentColor: Color): AnnotatedString {
    val split = full.length - highlightSuffix.length
    return buildAnnotatedString {
        if (split > 0) {
            withStyle(SpanStyle(color = inkColor)) { append(full.substring(0, split)) }
        }
        withStyle(SpanStyle(color = accentColor)) { append(highlightSuffix) }
    }
}

@Composable
private fun HistoryFooter(footer: LastEntryFooter, onHistoryTap: (() -> Unit)?) {
    val colors = VestigeTheme.colors
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Left group: prefix + stacked date + duration
        Row(
            modifier = Modifier.weight(1f),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            EyebrowE(text = CaptureCopy.HISTORY_FOOTER_PREFIX)
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                EyebrowE(text = footer.monthLabel, color = colors.faint)
                Text(
                    text = footer.dayLabel,
                    style = VestigeTheme.typography.eyebrow.copy(fontSize = 16.sp),
                    color = colors.ink,
                )
            }
            Text(
                text = footer.durationLabel,
                style = VestigeTheme.typography.eyebrow,
                color = colors.dim,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
        }
        if (onHistoryTap != null) {
            HistoryLink(onClick = onHistoryTap, testTag = "history_footer_link")
        } else {
            Text(
                text = CaptureCopy.HISTORY_LINK,
                style = VestigeTheme.typography.personaLabel,
                color = colors.dim,
            )
        }
    }
}

@Composable
private fun OrTypeButton(onClick: () -> Unit) {
    val colors = VestigeTheme.colors
    Box(
        modifier = Modifier
            .border(width = 1.dp, color = colors.hair)
            .clickable(onClick = onClick)
            .semantics(mergeDescendants = true) {
                role = Role.Button
                contentDescription = CaptureCopy.OR_TYPE
            }
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Text(
            text = CaptureCopy.OR_TYPE,
            style = VestigeTheme.typography.personaLabel,
            color = colors.dim,
        )
    }
}
