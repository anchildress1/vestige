package dev.anchildress1.vestige.ui.capture

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.anchildress1.vestige.ui.components.AppTop
import dev.anchildress1.vestige.ui.components.BottomTab
import dev.anchildress1.vestige.ui.components.VestigeBottomNav
import dev.anchildress1.vestige.ui.components.VestigeSpinner
import dev.anchildress1.vestige.ui.theme.VestigeTheme

/**
 * Capture screen idle composition — matches `poc/capture-idle-empty-final.png`: hero, REC, OR
 * TYPE, then the footer (empty-state line for now; patterns-peek lands next) and the shared
 * bottom nav. No date strip or stat ribbon — the final comp dropped both.
 */
@Suppress("LongMethod", "LongParameterList") // Top-level Compose layout; chrome already bundled.
@Composable
fun IdleLayout(
    state: CaptureUiState.Idle,
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
            onMenuTap = chrome.onSettingsTap,
            onStatusTap = chrome.onStatusTap,
        )
        CaptureErrorBand(
            error = state.error,
            modifier = Modifier.padding(horizontal = 18.dp),
            onUseTyped = onTypeTap,
        )
        HeroBlock()
        Spacer(modifier = Modifier.height(8.dp))
        Box(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp),
            contentAlignment = Alignment.Center,
        ) {
            // Model deleted / still warming up / downloading / Wi-Fi-paused: a spinner stands in
            // for REC (no more diagnostic banner). The AppTop status pill still reflects the
            // model state for anyone who looks.
            if (state.modelReadiness is ModelReadiness.Ready) {
                RecButton(
                    onClick = onRecTap,
                    enabled = true,
                    contentDescription = CaptureCopy.REC_LABEL_IDLE,
                )
            } else {
                VestigeSpinner()
            }
        }
        Spacer(modifier = Modifier.height(18.dp))
        Box(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp),
            contentAlignment = Alignment.Center,
        ) {
            OrTypeButton(onClick = onTypeTap)
        }
        Spacer(modifier = Modifier.weight(1f))
        val peek = chrome.patternsPeek
        if (peek != null) {
            PatternsPeekCard(
                peek = peek,
                modifier = Modifier.padding(horizontal = 18.dp, vertical = 12.dp),
            )
        } else {
            Box(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 12.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = CaptureCopy.NO_ENTRIES_YET,
                    style = VestigeTheme.typography.eyebrow,
                    color = colors.dim,
                )
            }
        }
        VestigeBottomNav(
            active = BottomTab.CAPTURE,
            onSelect = { tab ->
                when (tab) {
                    BottomTab.CAPTURE -> Unit
                    BottomTab.PATTERNS -> chrome.onPatternsTap?.invoke()
                    BottomTab.HISTORY -> chrome.onHistoryTap?.invoke()
                }
            },
        )
    }
}

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
