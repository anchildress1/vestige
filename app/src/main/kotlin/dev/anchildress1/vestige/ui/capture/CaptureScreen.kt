package dev.anchildress1.vestige.ui.capture

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.anchildress1.vestige.ui.components.AppTop
import dev.anchildress1.vestige.ui.components.AppTopStatuses
import dev.anchildress1.vestige.ui.components.BottomTab
import dev.anchildress1.vestige.ui.components.VestigeSpinner
import dev.anchildress1.vestige.ui.theme.VestigeTheme

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

// Denied + no rationale after an explicit ask ⇒ system-level "don't ask again": surface the
// Settings path + typed-entry fallback instead of a dead Retry.
private fun Context.isMicPermanentlyBlocked(): Boolean {
    val activity = findActivity() ?: return false
    return !ActivityCompat.shouldShowRequestPermissionRationale(activity, Manifest.permission.RECORD_AUDIO)
}

/**
 * Route composable for the capture surface. Owns the mic permission launcher, dispatches across
 * [CaptureUiState] variants, and forwards the VM's one-shot "open this entry" event to the host
 * so a finished capture lands on the entry's detail in History.
 */
@Composable
@OptIn(ExperimentalMaterial3Api::class)
@Suppress("LongMethod") // Route dispatcher — per-state branch + sheet/menu wiring co-located.
fun CaptureScreen(
    viewModel: CaptureViewModel,
    modifier: Modifier = Modifier,
    chrome: IdleChromeCallbacks = IdleChromeCallbacks(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            viewModel.startRecording()
        } else {
            viewModel.onMicDenied(permanentlyBlocked = context.isMicPermanentlyBlocked())
        }
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
    val onMenuTap = remember(chrome) { chrome.onSettingsTap ?: {} }
    val onNavSelect: (BottomTab) -> Unit = remember(chrome) { { tab -> captureNavRoute(tab, chrome) } }

    val onOpenEntryDetail = chrome.onOpenEntryDetail
    LaunchedEffect(viewModel, onOpenEntryDetail) {
        viewModel.openEntryEvents.collect { entryId -> onOpenEntryDetail?.invoke(entryId) }
    }

    when (val current = state) {
        is CaptureUiState.Idle -> IdleLayout(
            state = current,
            onRecTap = onRecTap,
            onTypeTap = { showTypeSheet = true },
            modifier = modifier,
            chrome = chrome,
        )

        is CaptureUiState.Recording -> LiveLayout(
            state = current,
            onStopTap = viewModel::stopRecording,
            onDiscardTap = viewModel::discard,
            onMenuTap = onMenuTap,
            onNavSelect = onNavSelect,
            modifier = modifier,
        )

        is CaptureUiState.Submitting -> SubmittingPane(
            persona = current.persona.name,
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

private fun captureNavRoute(tab: BottomTab, chrome: IdleChromeCallbacks) {
    when (tab) {
        BottomTab.CAPTURE -> Unit
        BottomTab.PATTERNS -> chrome.onPatternsTap?.invoke()
        BottomTab.HISTORY -> chrome.onHistoryTap?.invoke()
    }
}

// Brief filing spinner between STOP / typed-submit and the entry being persisted. No copy —
// the old "Reading the entry." review page is gone; the entry opens in History on persist.
@Composable
private fun SubmittingPane(persona: String, modifier: Modifier = Modifier) {
    val colors = VestigeTheme.colors
    Column(modifier = modifier.fillMaxSize().background(colors.floor)) {
        AppTop(persona = persona, status = AppTopStatuses.Ready)
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            VestigeSpinner()
        }
    }
}
