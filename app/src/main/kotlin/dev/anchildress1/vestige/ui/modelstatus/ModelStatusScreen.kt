package dev.anchildress1.vestige.ui.modelstatus

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.anchildress1.vestige.R
import dev.anchildress1.vestige.ui.capture.ModelReadiness
import dev.anchildress1.vestige.ui.components.EyebrowE
import dev.anchildress1.vestige.ui.components.VestigeScaffold
import dev.anchildress1.vestige.ui.theme.VestigeTheme

private enum class PendingConfirm { None, ReDownload, Delete }

/**
 * Standalone "this is a local AI app" surface, reachable by tapping the AppTop status pill.
 * Copy is verbatim from `ux-copy.md` §"Local Model Status (standalone screen)"; the confirm
 * dialogs follow the canonical §"Destructive Confirmations" wording (the dedicated spec) over
 * the shorter §"Local Model Status" summary.
 */
@Composable
fun ModelStatusScreen(
    info: ModelStatusInfo,
    onReDownload: () -> Unit,
    onDelete: () -> Unit,
    onExit: () -> Unit,
    modifier: Modifier = Modifier,
) {
    BackHandler(onBack = onExit)
    var pending by remember { mutableStateOf(PendingConfirm.None) }
    VestigeScaffold(modifier = modifier) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 24.dp, vertical = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            EyebrowE(text = stringResource(id = R.string.model_status_eyebrow))
            Text(
                text = stringResource(id = R.string.model_status_header),
                style = VestigeTheme.typography.h1,
                color = VestigeTheme.colors.ink,
            )
            StatusLine(readiness = info.readiness)
            EyebrowE(
                text = stringResource(id = R.string.model_status_detail, info.sizeLabel, info.versionName),
            )
            Spacer(modifier = Modifier.weight(1f))
            OutlinedButton(
                onClick = { pending = PendingConfirm.ReDownload },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(text = stringResource(id = R.string.model_status_redownload))
            }
            TextButton(
                onClick = { pending = PendingConfirm.Delete },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(text = stringResource(id = R.string.model_status_delete), color = VestigeTheme.colors.coral)
            }
        }
    }
    ConfirmHost(
        pending = pending,
        onReDownloadConfirmed = {
            pending = PendingConfirm.None
            onReDownload()
        },
        onDeleteConfirmed = {
            pending = PendingConfirm.None
            onDelete()
        },
        onDismiss = { pending = PendingConfirm.None },
    )
}

@Composable
private fun StatusLine(readiness: ModelReadiness) {
    val text = when (readiness) {
        ModelReadiness.Ready -> stringResource(id = R.string.model_status_ready)

        ModelReadiness.Loading -> stringResource(id = R.string.model_status_loading)

        ModelReadiness.Paused -> stringResource(id = R.string.model_status_paused)

        is ModelReadiness.Downloading -> stringResource(id = R.string.model_status_downloading) +
            " " + stringResource(id = R.string.model_status_downloading_pct, readiness.percent)
    }
    // Status surface: re-download flips Loading → Downloading % → Ready live, so announce
    // politely. No role / click — it is not interactive (AGENTS.md band a11y rule).
    Text(
        text = text,
        style = VestigeTheme.typography.p,
        color = VestigeTheme.colors.ink,
        modifier = Modifier
            .fillMaxWidth()
            .semantics {
                liveRegion = LiveRegionMode.Polite
                contentDescription = text
            },
    )
}

private data class ConfirmSpec(val title: String, val body: String, val confirmLabel: String, val destructive: Boolean)

@Composable
private fun ConfirmHost(
    pending: PendingConfirm,
    onReDownloadConfirmed: () -> Unit,
    onDeleteConfirmed: () -> Unit,
    onDismiss: () -> Unit,
) {
    when (pending) {
        PendingConfirm.None -> Unit

        PendingConfirm.ReDownload -> ConfirmDialog(
            spec = ConfirmSpec(
                title = stringResource(id = R.string.model_status_redownload_title),
                body = stringResource(id = R.string.model_status_redownload_body),
                confirmLabel = stringResource(id = R.string.model_status_redownload_confirm),
                destructive = false,
            ),
            onConfirm = onReDownloadConfirmed,
            onDismiss = onDismiss,
        )

        PendingConfirm.Delete -> ConfirmDialog(
            spec = ConfirmSpec(
                title = stringResource(id = R.string.model_status_delete_title),
                body = stringResource(id = R.string.model_status_delete_body),
                confirmLabel = stringResource(id = R.string.model_status_delete_confirm),
                destructive = true,
            ),
            onConfirm = onDeleteConfirmed,
            onDismiss = onDismiss,
        )
    }
}

@Composable
private fun ConfirmDialog(spec: ConfirmSpec, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    val colors = VestigeTheme.colors
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = colors.deep,
        titleContentColor = colors.ink,
        textContentColor = colors.dim,
        title = { Text(text = spec.title) },
        text = { Text(text = spec.body) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(text = spec.confirmLabel, color = if (spec.destructive) colors.coral else colors.lime)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(text = stringResource(id = R.string.model_status_cancel), color = colors.dim)
            }
        },
    )
}
