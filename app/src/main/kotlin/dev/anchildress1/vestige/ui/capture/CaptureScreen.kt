package dev.anchildress1.vestige.ui.capture

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredHeightIn
import androidx.compose.foundation.layout.windowInsetsBottomHeight
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.anchildress1.vestige.model.Persona
import dev.anchildress1.vestige.ui.components.AppTop
import dev.anchildress1.vestige.ui.components.AppTopStatuses
import dev.anchildress1.vestige.ui.components.EyebrowE
import dev.anchildress1.vestige.ui.theme.VestigeTheme

/**
 * Route composable for the capture surface. Owns the mic permission launcher and dispatches
 * across [CaptureUiState] variants. Stateless wrt audio I/O — the [viewModel] holds the
 * recording job + foreground-call lifecycle.
 *
 * Stats and meta are caller-supplied so tests + previews can pin them; production wires them
 * from `AppContainer.entryStore.countCompleted()` + similar reads.
 */
@Composable
@Suppress("LongParameterList") // Route-level Compose entry; bundled callbacks already.
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
fun CaptureScreen(
    viewModel: CaptureViewModel,
    stats: CaptureStats,
    meta: CaptureMeta,
    modifier: Modifier = Modifier,
    lastEntryFooter: LastEntryFooter? = null,
    chrome: IdleChromeCallbacks = IdleChromeCallbacks(),
    onOpenPatterns: (() -> Unit)? = null,
    onOpenHistory: (() -> Unit)? = null,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) viewModel.startRecording() else viewModel.onMicDenied()
    }
    val onRecTap = remember(viewModel, launcher, context) {
        {
            val granted = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.RECORD_AUDIO,
            ) == PackageManager.PERMISSION_GRANTED
            if (granted) viewModel.startRecording() else launcher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }
    var showTypeSheet by rememberSaveable { mutableStateOf(false) }

    when (val current = state) {
        is CaptureUiState.Idle -> IdleLayout(
            state = current,
            stats = stats,
            meta = meta,
            onRecTap = onRecTap,
            onTypeTap = { showTypeSheet = true },
            modifier = modifier,
            lastEntryFooter = lastEntryFooter,
            chrome = chrome.copy(
                onPatternsTap = onOpenPatterns ?: chrome.onPatternsTap,
                onHistoryTap = onOpenHistory ?: chrome.onHistoryTap,
            ),
        )

        is CaptureUiState.Recording -> LiveLayout(
            state = current,
            onStopTap = viewModel::stopRecording,
            onDiscardTap = viewModel::discard,
            modifier = modifier,
        )

        is CaptureUiState.Inferring -> InferringPane(
            persona = current.persona,
            modifier = modifier,
        )

        is CaptureUiState.Reviewing -> ReviewingPane(
            state = current,
            onAcknowledge = viewModel::acknowledgeReview,
            onOpenHistory = onOpenHistory,
            modifier = modifier,
        )
    }

    if (showTypeSheet) {
        TypeEntrySheet(
            onDismiss = { showTypeSheet = false },
            onSubmit = { text ->
                viewModel.submitTyped(text)
                showTypeSheet = false
            },
        )
    }
}

@Composable
private fun InferringPane(persona: Persona, modifier: Modifier = Modifier) {
    val colors = VestigeTheme.colors
    Column(modifier = modifier.fillMaxSize().background(colors.floor)) {
        AppTop(persona = persona.name, status = AppTopStatuses.Ready)
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                text = CaptureCopy.READING_PLACEHOLDER,
                style = VestigeTheme.typography.h1,
                color = colors.dim,
            )
        }
    }
}

@Composable
private fun ReviewingPane(
    state: CaptureUiState.Reviewing,
    onAcknowledge: () -> Unit,
    onOpenHistory: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val colors = VestigeTheme.colors
    Column(modifier = modifier.fillMaxSize().background(colors.floor)) {
        AppTop(persona = state.persona.name, status = AppTopStatuses.Ready)
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 18.dp, vertical = 18.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            TranscriptTurn(label = CaptureCopy.YOU_LABEL, body = state.review.transcription, bodyColor = colors.dim)
            TranscriptTurn(label = state.persona.name, body = state.review.followUp, bodyColor = colors.ink)
        }
        Spacer(modifier = Modifier.weight(1f))
        DoneButton(onClick = onAcknowledge)
        if (onOpenHistory != null) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 18.dp, vertical = 8.dp),
                contentAlignment = Alignment.Center,
            ) {
                HistoryLink(onClick = onOpenHistory)
            }
        }
        Spacer(Modifier.windowInsetsBottomHeight(WindowInsets.navigationBars))
    }
}

@Composable
private fun TranscriptTurn(label: String, body: String, bodyColor: Color) {
    val colors = VestigeTheme.colors
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(colors.s1)
            .padding(horizontal = 12.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        EyebrowE(text = label)
        Text(
            text = body.ifBlank { "—" },
            style = VestigeTheme.typography.p,
            color = bodyColor,
        )
    }
}

@Composable
internal fun HistoryLink(onClick: () -> Unit, testTag: String? = null) {
    val colors = VestigeTheme.colors
    Box(
        modifier = Modifier
            .requiredHeightIn(min = 48.dp)
            .clickable(onClick = onClick)
            .semantics(mergeDescendants = true) {
                role = Role.Button
                contentDescription = CaptureCopy.HISTORY_LINK_A11Y
                if (testTag != null) this.testTag = testTag
            }
            .padding(horizontal = 12.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = CaptureCopy.HISTORY_LINK,
            style = VestigeTheme.typography.personaLabel,
            color = colors.dim,
        )
    }
}

@Composable
private fun DoneButton(onClick: () -> Unit) {
    val colors = VestigeTheme.colors
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 18.dp, vertical = 18.dp)
            .background(colors.ink)
            .clickable(onClick = onClick)
            .semantics(mergeDescendants = true) {
                role = Role.Button
                contentDescription = "Done"
            }
            .padding(vertical = 16.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = "DONE · NEW ENTRY",
            style = VestigeTheme.typography.displayBig.copy(fontSize = 22.sp, lineHeight = 22.sp),
            color = colors.deep,
        )
    }
}
