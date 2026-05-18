package dev.anchildress1.vestige.ui.modelstatus

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import dev.anchildress1.vestige.R
import dev.anchildress1.vestige.ui.capture.ModelReadiness
import dev.anchildress1.vestige.ui.components.AppTop
import dev.anchildress1.vestige.ui.components.AppTopStatuses
import dev.anchildress1.vestige.ui.components.BottomTab
import dev.anchildress1.vestige.ui.components.EyebrowE
import dev.anchildress1.vestige.ui.components.StatItem
import dev.anchildress1.vestige.ui.components.StatRibbon
import dev.anchildress1.vestige.ui.components.VestigeBottomNav
import dev.anchildress1.vestige.ui.components.VestigeConfirmCard
import dev.anchildress1.vestige.ui.theme.VestigeTheme

private enum class PendingConfirm { None, ReDownload, Delete }

/**
 * "This is a local AI app" surface — `poc/model-detail-final.png`. Reachable from the AppTop
 * status pill or Settings. Stack + network-gate copy is honest static product fact (no
 * fabricated telemetry); state-driven copy is verbatim from `ux-copy.md`.
 */
@Composable
@Suppress("LongParameterList", "LongMethod") // Route seam: chrome + bands + stack + actions + dialog.
fun ModelStatusScreen(
    info: ModelStatusInfo,
    onReDownload: () -> Unit,
    onDelete: () -> Unit,
    onExit: () -> Unit,
    onNavSelect: (BottomTab) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    BackHandler(onBack = onExit)
    var pending by remember { mutableStateOf(PendingConfirm.None) }
    val colors = VestigeTheme.colors
    val actionsEnabled = info.readiness !is ModelReadiness.Downloading

    Box(modifier = modifier.fillMaxSize().background(colors.floor)) {
        Column(modifier = Modifier.fillMaxSize()) {
            AppTop(persona = "", status = AppTopStatuses.Ready, onMenuTap = onExit)
            Box(
                modifier = Modifier
                    .clickable(role = Role.Button, onClick = onExit)
                    .padding(horizontal = 18.dp, vertical = 8.dp)
                    .semantics { contentDescription = "Back" },
            ) {
                EyebrowE(text = stringResource(id = R.string.model_status_back_eyebrow))
            }
            Text(
                text = buildAnnotatedString {
                    withStyle(SpanStyle(color = colors.ink)) { append("MODEL STATUS") }
                    withStyle(SpanStyle(color = colors.coral)) { append(".") }
                },
                style = VestigeTheme.typography.displayBig,
                modifier = Modifier.padding(horizontal = 18.dp, vertical = 4.dp),
            )
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 18.dp, vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                StatusBand(info)
                ModelStatRibbon(sizeLabel = info.sizeLabel)
                EyebrowE(text = stringResource(id = R.string.model_status_stack_eyebrow), color = colors.lime)
                StackRow(
                    name = stringResource(id = R.string.model_status_stack_main_name),
                    role = stringResource(id = R.string.model_status_stack_main_role),
                    size = info.sizeLabel,
                )
                StackRow(
                    name = stringResource(id = R.string.model_status_stack_embed_name),
                    role = stringResource(id = R.string.model_status_stack_embed_role),
                    size = stringResource(id = R.string.model_status_stack_embed_size),
                )
                StackRow(
                    name = stringResource(id = R.string.model_status_stack_runtime_name),
                    role = stringResource(id = R.string.model_status_stack_runtime_role),
                    size = stringResource(id = R.string.model_status_stack_runtime_size),
                )
                NetworkGateBand()
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    OutlineAction(
                        label = stringResource(id = R.string.model_status_redownload),
                        color = colors.dim,
                        enabled = actionsEnabled,
                        onClick = { pending = PendingConfirm.ReDownload },
                        modifier = Modifier.weight(1f),
                    )
                    OutlineAction(
                        label = stringResource(id = R.string.model_status_delete),
                        color = colors.coral,
                        enabled = actionsEnabled,
                        onClick = { pending = PendingConfirm.Delete },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
            VestigeBottomNav(active = null, onSelect = onNavSelect)
        }
    }

    when (pending) {
        PendingConfirm.None -> Unit

        PendingConfirm.ReDownload -> VestigeConfirmCard(
            title = stringResource(id = R.string.model_status_redownload_title),
            body = stringResource(id = R.string.model_status_redownload_body),
            confirmLabel = stringResource(id = R.string.model_status_redownload_confirm),
            onConfirm = {
                pending = PendingConfirm.None
                onReDownload()
            },
            onDismiss = { pending = PendingConfirm.None },
            cancelLabel = stringResource(id = R.string.model_status_cancel),
        )

        PendingConfirm.Delete -> VestigeConfirmCard(
            title = stringResource(id = R.string.model_status_delete_title),
            body = stringResource(id = R.string.model_status_delete_body),
            confirmLabel = stringResource(id = R.string.model_status_delete_confirm),
            onConfirm = {
                pending = PendingConfirm.None
                onDelete()
            },
            onDismiss = { pending = PendingConfirm.None },
            cancelLabel = stringResource(id = R.string.model_status_cancel),
        )
    }
}

@Composable
private fun StatusBand(info: ModelStatusInfo) {
    val colors = VestigeTheme.colors
    val ready = info.readiness is ModelReadiness.Ready
    val accent = if (ready) colors.lime else colors.coral
    val eyebrow = stringResource(
        id = if (ready) R.string.model_status_band_ready else R.string.model_status_band_pending,
    )
    val body = if (ready) {
        stringResource(id = R.string.model_status_detail, info.sizeLabel, info.versionName)
    } else {
        readinessLine(info.readiness)
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(VestigeTheme.shapes.m)
            .border(width = 1.dp, color = accent, shape = VestigeTheme.shapes.m)
            .background(colors.s1)
            .semantics(mergeDescendants = true) {
                liveRegion = LiveRegionMode.Polite
                contentDescription = body
            }
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        EyebrowE(text = eyebrow, color = accent, maxLines = 1, softWrap = false)
        Text(text = body, style = VestigeTheme.typography.p, color = colors.ink)
    }
}

@Composable
private fun readinessLine(readiness: ModelReadiness): String = when (readiness) {
    // Ready never reaches here — the band shows the on-device detail line instead.
    ModelReadiness.Ready -> ""

    ModelReadiness.Loading -> stringResource(id = R.string.model_status_loading)

    ModelReadiness.Paused -> stringResource(id = R.string.model_status_paused)

    is ModelReadiness.Downloading -> stringResource(id = R.string.model_status_downloading) +
        " " + stringResource(id = R.string.model_status_downloading_pct, readiness.percent)
}

@Composable
private fun ModelStatRibbon(sizeLabel: String) {
    Box(
        modifier = Modifier.semantics(mergeDescendants = true) {
            contentDescription = "$sizeLabel on disk, 0 cloud calls"
        },
    ) {
        StatRibbon(
            items = listOf(
                StatItem(value = sizeLabel, label = "ON DISK"),
                StatItem(value = "0", label = "CLOUD CALLS", color = VestigeTheme.colors.coral),
            ),
        )
    }
}

@Composable
private fun StackRow(name: String, role: String, size: String) {
    val colors = VestigeTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(VestigeTheme.shapes.m)
            .border(width = 1.dp, color = colors.hair, shape = VestigeTheme.shapes.m)
            .semantics(mergeDescendants = true) { contentDescription = "$name. $role. $size" }
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(text = name, style = VestigeTheme.typography.title, color = colors.ink)
            EyebrowE(text = role)
        }
        EyebrowE(text = size, maxLines = 1, softWrap = false)
        Box(modifier = Modifier.size(7.dp).clip(VestigeTheme.shapes.pill).background(colors.lime))
    }
}

@Composable
private fun NetworkGateBand() {
    val colors = VestigeTheme.colors
    val body = stringResource(id = R.string.model_status_gate_body)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(VestigeTheme.shapes.m)
            .border(width = 1.dp, color = colors.coral, shape = VestigeTheme.shapes.m)
            .background(colors.s1)
            .semantics(mergeDescendants = true) { contentDescription = body }
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        EyebrowE(
            text = stringResource(id = R.string.model_status_gate_eyebrow),
            color = colors.coral,
            maxLines = 1,
            softWrap = false,
        )
        Text(text = body, style = VestigeTheme.typography.p, color = colors.dim)
    }
}

@Composable
private fun OutlineAction(label: String, color: Color, enabled: Boolean, onClick: () -> Unit, modifier: Modifier) {
    val drawColor = if (enabled) color else VestigeTheme.colors.faint
    Box(
        modifier = modifier
            .border(width = 1.dp, color = drawColor)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .semantics {
                contentDescription = label
                if (!enabled) disabled()
            }
            .padding(vertical = 14.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text = label, style = VestigeTheme.typography.personaLabel, color = drawColor)
    }
}
