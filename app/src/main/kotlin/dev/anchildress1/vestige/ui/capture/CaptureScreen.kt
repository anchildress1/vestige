package dev.anchildress1.vestige.ui.capture

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.anchildress1.vestige.R
import dev.anchildress1.vestige.ui.components.AppTop
import dev.anchildress1.vestige.ui.components.AppTopStatuses
import dev.anchildress1.vestige.ui.components.PageSpinnerDiameter
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

    val onOpenEntryDetail = chrome.onOpenEntryDetail
    LaunchedEffect(viewModel, onOpenEntryDetail) {
        viewModel.openEntryEvents.collect { entryId ->
            onOpenEntryDetail?.invoke(entryId)
            viewModel.onOpenEntryHandled()
        }
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

// Brief filing spinner between STOP / typed-submit and the entry being persisted. Keep the
// helper line in lockstep with the local-processing notification copy so "working" means the
// same thing in-app and in the system shade.
@Composable
private fun SubmittingPane(persona: String, modifier: Modifier = Modifier) {
    val colors = VestigeTheme.colors
    val helper = stringResource(R.string.notification_local_processing_text)
    Column(modifier = modifier.fillMaxSize().background(colors.floor)) {
        AppTop(persona = persona, status = AppTopStatuses.Ready)
        // weight(1f) takes the space below AppTop so the spinner is truly vertically centered
        // (fillMaxSize here would over-extend past the fold and skew it).
        Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            Column(
                modifier = Modifier.semantics(mergeDescendants = true) {
                    liveRegion = LiveRegionMode.Polite
                    contentDescription = helper
                },
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                VestigeSpinner(diameter = PageSpinnerDiameter)
                Text(
                    text = helper,
                    style = VestigeTheme.typography.p,
                    color = colors.dim,
                    modifier = Modifier.padding(horizontal = 24.dp),
                )
            }
        }
    }
}
