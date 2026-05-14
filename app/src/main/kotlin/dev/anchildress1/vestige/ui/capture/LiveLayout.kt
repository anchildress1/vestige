package dev.anchildress1.vestige.ui.capture

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.anchildress1.vestige.ui.components.AppTop
import dev.anchildress1.vestige.ui.components.AppTopStatuses
import dev.anchildress1.vestige.ui.components.EyebrowE
import dev.anchildress1.vestige.ui.theme.VestigeTheme

/**
 * Capture screen recording composition. Matches `poc/screenshots/capture-running.png`. Caller
 * passes the full `CaptureUiState.Recording` snapshot — this composable is pure-render.
 */
@Composable
fun LiveLayout(
    state: CaptureUiState.Recording,
    onStopTap: () -> Unit,
    onDiscardTap: () -> Unit,
    modifier: Modifier = Modifier,
    maxDurationMs: Long = CaptureViewModel.MAX_DURATION_MS,
) {
    val colors = VestigeTheme.colors
    val elapsedSec = (state.elapsedMs / MS_PER_SEC).coerceAtLeast(0).toInt()
    val totalSec = (maxDurationMs / MS_PER_SEC).coerceAtLeast(1).toInt()
    val remainSec = (totalSec - elapsedSec).coerceAtLeast(0)
    val mm = (elapsedSec / SEC_PER_MIN).toString().padStart(2, '0')
    val ss = (elapsedSec % SEC_PER_MIN).toString().padStart(2, '0')
    val timerLabel = "$mm:$ss"
    val progress = elapsedSec.toFloat() / totalSec.toFloat()
    val wordCount = estimatedWords(elapsedSec)

    Column(modifier = modifier.fillMaxSize().background(colors.floor)) {
        AppTop(
            persona = state.persona.name,
            status = AppTopStatuses.Recording,
            rightContent = {},
        )
        TimerHeader(timerLabel = timerLabel, remainSec = remainSec)
        Box(modifier = Modifier.padding(horizontal = 18.dp, vertical = 14.dp)) {
            ChunkProgressBar(progress = progress, chunkDurationSec = totalSec)
        }
        Spacer(modifier = Modifier.height(8.dp))
        Column(
            modifier = Modifier.padding(horizontal = 18.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = CaptureCopy.LIVE_LEVEL_EYEBROW,
                style = VestigeTheme.typography.eyebrow,
                color = colors.coral,
            )
            LiveLevelBars(levels = state.recentLevels)
            WordCountCard(wordCount = wordCount)
        }
        Spacer(modifier = Modifier.weight(1f))
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 18.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            StopButton(onClick = onStopTap)
            DiscardLink(onClick = onDiscardTap)
        }
    }
}

@Composable
private fun TimerHeader(timerLabel: String, remainSec: Int) {
    val colors = VestigeTheme.colors
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 18.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Top,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                text = CaptureCopy.LIVE_RECORDING_EYEBROW,
                style = VestigeTheme.typography.eyebrow,
                color = colors.coral,
            )
            Text(
                text = timerLabel,
                style = VestigeTheme.typography.displayBig.copy(fontSize = 96.sp, lineHeight = 88.sp),
                color = colors.ink,
            )
        }
        Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(4.dp)) {
            EyebrowE(text = CaptureCopy.LIVE_REMAIN_LABEL)
            Text(
                text = remainSec.toString(),
                style = VestigeTheme.typography.displayBig.copy(fontSize = 44.sp, lineHeight = 40.sp),
                color = colors.coral,
            )
            EyebrowE(text = CaptureCopy.LIVE_SECONDS_LABEL)
        }
    }
}

@Composable
private fun WordCountCard(wordCount: Int) {
    val colors = VestigeTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(colors.s1)
            .border(width = 1.dp, color = colors.hair)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        EyebrowE(text = CaptureCopy.LIVE_WORD_COUNT_LABEL)
        Text(
            text = wordCount.toString(),
            style = VestigeTheme.typography.displayBig.copy(fontSize = 26.sp, lineHeight = 24.sp),
            color = colors.ink,
        )
    }
}

@Composable
private fun StopButton(onClick: () -> Unit) {
    val colors = VestigeTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(colors.ink)
            .clickable(onClick = onClick)
            .semantics(mergeDescendants = true) {
                role = Role.Button
                contentDescription = CaptureCopy.REC_LABEL_RECORDING
            }
            .padding(vertical = 16.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(modifier = Modifier.size(14.dp).background(colors.coral))
        Spacer(modifier = Modifier.size(10.dp))
        Text(
            text = CaptureCopy.LIVE_STOP_PRIMARY,
            style = VestigeTheme.typography.displayBig.copy(fontSize = 22.sp, lineHeight = 22.sp),
            color = colors.deep,
        )
    }
}

@Composable
private fun DiscardLink(onClick: () -> Unit) {
    val colors = VestigeTheme.colors
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .semantics(mergeDescendants = true) {
                role = Role.Button
                contentDescription = CaptureCopy.LIVE_DISCARD_SECONDARY
            }
            .padding(vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = CaptureCopy.LIVE_DISCARD_SECONDARY,
            style = VestigeTheme.typography.personaLabel,
            color = colors.faint,
        )
    }
}

private fun estimatedWords(elapsedSec: Int): Int = elapsedSec * WORDS_PER_SEC_TIMES_TEN / WORDS_DENOMINATOR

private const val MS_PER_SEC: Long = 1_000L
private const val SEC_PER_MIN: Int = 60

// 2.3 words/sec is the human-conversation average — multiplied by 10 + integer-divided to avoid
// dragging a Float through the path that recomposes 25 Hz.
private const val WORDS_PER_SEC_TIMES_TEN: Int = 23
private const val WORDS_DENOMINATOR: Int = 10
