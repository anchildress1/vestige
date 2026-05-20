package dev.anchildress1.vestige.ui.capture

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.anchildress1.vestige.ui.components.EyebrowE
import dev.anchildress1.vestige.ui.patterns.TraceBarE
import dev.anchildress1.vestige.ui.theme.VestigeTheme

/**
 * Idle-screen patterns peek — a compact teaser (count eyebrow + names + a union 30-day
 * TraceBar) shown above the bottom nav when active patterns exist (`poc/
 * capture-idle-populated-final.png`). Informational, not interactive: a single merged
 * contentDescription, no click action.
 */
@Composable
fun PatternsPeekCard(peek: CapturePatternsPeek, modifier: Modifier = Modifier) {
    val colors = VestigeTheme.colors
    val eyebrow = CaptureCopy.PATTERNS_PEEK_EYEBROW_FMT.format(peek.activeCount)
    val teaser = peek.names.joinToString(CaptureCopy.PATTERNS_PEEK_SEPARATOR)
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(colors.s1)
            .border(width = 1.dp, color = colors.hair)
            .semantics(mergeDescendants = true) {
                liveRegion = LiveRegionMode.Polite
                contentDescription = "$eyebrow. $teaser"
            }
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        EyebrowE(text = eyebrow, color = colors.lime)
        Text(text = teaser, style = VestigeTheme.typography.p, color = colors.ink)
        TraceBarE(hits = peek.traceHits, accent = colors.lime, peak = true)
    }
}
