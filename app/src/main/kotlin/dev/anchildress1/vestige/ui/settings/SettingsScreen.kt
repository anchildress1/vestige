@file:Suppress("TooManyFunctions") // Screen split into small private composables — clarity over a god-function.

package dev.anchildress1.vestige.ui.settings

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import dev.anchildress1.vestige.R
import dev.anchildress1.vestige.model.Persona
import dev.anchildress1.vestige.ui.components.AppTop
import dev.anchildress1.vestige.ui.components.AppTopStatuses
import dev.anchildress1.vestige.ui.components.BottomTab
import dev.anchildress1.vestige.ui.components.EyebrowE
import dev.anchildress1.vestige.ui.components.VestigeBottomNav
import dev.anchildress1.vestige.ui.theme.VestigeTheme
import kotlinx.coroutines.launch

/** Static facts for the Settings surface, bundled to keep arity low. */
data class SettingsInfo(val versionLabel: String, val sourceUrl: String)

/** Settings actions, grouped so the screen stays a small-arity surface. */
data class SettingsActions(
    val onSelectPersona: (Persona) -> Unit,
    /** Writes the export to [Uri]; returns `true` on success so the screen can confirm honestly. */
    val onExportToUri: suspend (Uri) -> Boolean,
    val onWipe: () -> Unit,
    val onOpenModelStatus: () -> Unit,
    val onOpenSource: () -> Unit,
    val onExit: () -> Unit,
)

private const val DELETE_TOKEN = "DELETE"
private const val EXPORT_FILENAME = "vestige-entries.zip"
private val SNACKBAR_NAV_CLEARANCE = 84.dp

/** Test handle for the typed-DELETE confirmation field. */
const val WIPE_FIELD_TAG = "settings_wipe_field"

@Composable
@Suppress("LongParameterList", "LongMethod") // Route seam: launcher + chrome + body + dialog co-located.
fun SettingsScreen(
    persona: Persona,
    info: SettingsInfo,
    actions: SettingsActions,
    onNavSelect: (BottomTab) -> Unit = {},
    onMenuTap: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    BackHandler(onBack = actions.onExit)
    var confirmingWipe by remember { mutableStateOf(false) }
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val colors = VestigeTheme.colors
    val exportOkMessage = stringResource(id = R.string.settings_export_success)
    val exportFailedMessage = stringResource(id = R.string.settings_export_failed)
    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/zip"),
    ) { uri ->
        if (uri != null) {
            scope.launch {
                val ok = actions.onExportToUri(uri)
                snackbarHostState.showSnackbar(if (ok) exportOkMessage else exportFailedMessage)
            }
        }
    }

    Box(modifier = modifier.fillMaxSize().background(colors.floor)) {
        Column(modifier = Modifier.fillMaxSize()) {
            AppTop(persona = persona.name, status = AppTopStatuses.Ready, onMenuTap = onMenuTap)
            Box(
                modifier = Modifier
                    .clickable(role = Role.Button, onClick = actions.onExit)
                    .padding(horizontal = 18.dp, vertical = 8.dp)
                    .semantics { contentDescription = "Back" },
            ) {
                EyebrowE(text = stringResource(id = R.string.settings_back_eyebrow))
            }
            Text(
                text = buildAnnotatedString {
                    withStyle(SpanStyle(color = colors.ink)) { append("SETTINGS") }
                    withStyle(SpanStyle(color = colors.coral)) { append(".") }
                },
                style = VestigeTheme.typography.displayBig,
                modifier = Modifier.padding(horizontal = 18.dp, vertical = 4.dp),
            )
            SettingsBody(
                persona = persona,
                info = info,
                actions = actions,
                onExport = { exportLauncher.launch(EXPORT_FILENAME) },
                onDeleteAll = { confirmingWipe = true },
                modifier = Modifier.weight(1f),
            )
            VestigeBottomNav(active = null, onSelect = onNavSelect)
        }
        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = SNACKBAR_NAV_CLEARANCE),
        ) {
            SnackbarHost(snackbarHostState)
        }
    }

    if (confirmingWipe) {
        DeleteAllDialog(
            onConfirm = {
                confirmingWipe = false
                actions.onWipe()
            },
            onDismiss = { confirmingWipe = false },
        )
    }
}

@Composable
@Suppress("LongParameterList") // Body seam: persona + info + actions + two intents + modifier.
private fun SettingsBody(
    persona: Persona,
    info: SettingsInfo,
    actions: SettingsActions,
    onExport: () -> Unit,
    onDeleteAll: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = VestigeTheme.colors
    Column(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 18.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        PersonaSection(selected = persona, onSelect = actions.onSelectPersona)
        DataSection(onExport = onExport, onDeleteAll = onDeleteAll)
        SettingsSection(stringResource(id = R.string.settings_section_model)) {
            SettingsBoxRow(
                title = stringResource(id = R.string.settings_model_status),
                desc = stringResource(id = R.string.settings_model_status_desc),
                trailing = "→",
                titleColor = colors.ink,
                testTag = "settings_row_model",
                onClick = actions.onOpenModelStatus,
            )
        }
        SettingsSection(stringResource(id = R.string.settings_section_about)) {
            SettingsBoxRow(
                title = stringResource(id = R.string.settings_version_label),
                desc = stringResource(id = R.string.settings_version, info.versionLabel),
                trailing = "→",
                titleColor = colors.ink,
                testTag = "settings_row_version",
                onClick = actions.onOpenSource,
                desc2 = stringResource(id = R.string.settings_license),
            )
        }
    }
}

@Composable
private fun SettingsSection(label: String, content: @Composable () -> Unit) {
    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionHeader(label)
        content()
    }
}

@Composable
private fun SectionHeader(label: String) {
    val colors = VestigeTheme.colors
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(modifier = Modifier.size(6.dp).clip(VestigeTheme.shapes.pill).background(colors.lime))
        EyebrowE(text = label)
    }
}

@Composable
private fun PersonaSection(selected: Persona, onSelect: (Persona) -> Unit) {
    val colors = VestigeTheme.colors
    SettingsSection(stringResource(id = R.string.settings_section_persona)) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(VestigeTheme.shapes.m)
                .border(width = 1.dp, color = colors.hair, shape = VestigeTheme.shapes.m),
        ) {
            Persona.entries.forEachIndexed { index, persona ->
                if (index > 0) {
                    Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(colors.hair))
                }
                PersonaRow(
                    persona = persona,
                    isSelected = persona == selected,
                    onSelect = { onSelect(persona) },
                )
            }
        }
    }
}

@Composable
private fun PersonaRow(persona: Persona, isSelected: Boolean, onSelect: () -> Unit) {
    val colors = VestigeTheme.colors
    val name = stringResource(id = personaNameRes(persona))
    val desc = stringResource(id = personaDescRes(persona))
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .selectable(selected = isSelected, role = Role.RadioButton, onClick = onSelect)
            .semantics(mergeDescendants = true) { contentDescription = "$name. $desc" }
            .testTag("persona_${persona.name}")
            .then(if (isSelected) Modifier.background(colors.limeWash) else Modifier)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalAlignment = Alignment.Top,
    ) {
        RadioDot(selected = isSelected)
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = name,
                    style = VestigeTheme.typography.title,
                    color = if (isSelected) colors.lime else colors.ink,
                    modifier = Modifier.weight(1f),
                )
                if (isSelected) {
                    EyebrowE(
                        text = stringResource(id = R.string.settings_selected_tag),
                        color = colors.lime,
                        maxLines = 1,
                        softWrap = false,
                    )
                }
            }
            Text(text = desc, style = VestigeTheme.typography.pCompact, color = colors.dim)
        }
    }
}

private const val RADIO_RING_PX = 3f

@Composable
private fun RadioDot(selected: Boolean) {
    val colors = VestigeTheme.colors
    val ring = if (selected) colors.lime else colors.dim
    Box(
        modifier = Modifier
            .size(20.dp)
            .drawBehind {
                val r = size.minDimension / 2f
                drawCircle(color = ring, radius = r - RADIO_RING_PX, style = Stroke(width = RADIO_RING_PX))
                if (selected) drawCircle(color = colors.lime, radius = r / 2.4f)
            },
    )
}

@Composable
private fun DataSection(onExport: () -> Unit, onDeleteAll: () -> Unit) {
    val colors = VestigeTheme.colors
    SettingsSection(stringResource(id = R.string.settings_section_data)) {
        SettingsBoxRow(
            title = stringResource(id = R.string.settings_export),
            desc = stringResource(id = R.string.settings_export_desc),
            trailing = "→",
            titleColor = colors.ink,
            testTag = "settings_row_export",
            onClick = onExport,
        )
        SettingsBoxRow(
            title = stringResource(id = R.string.settings_delete_all),
            desc = stringResource(id = R.string.settings_delete_all_desc),
            trailing = "→",
            titleColor = colors.coral,
            testTag = "settings_row_delete",
            onClick = onDeleteAll,
        )
    }
}

@Composable
@Suppress("LongParameterList") // Box-row primitive: title + desc(+desc2) + glyph + color + tag + click.
private fun SettingsBoxRow(
    title: String,
    desc: String,
    trailing: String,
    titleColor: Color,
    testTag: String,
    onClick: () -> Unit,
    desc2: String? = null,
) {
    val colors = VestigeTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(VestigeTheme.shapes.m)
            .border(width = 1.dp, color = colors.hair, shape = VestigeTheme.shapes.m)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics(mergeDescendants = true) {
                contentDescription = listOfNotNull(title, desc, desc2).joinToString(". ")
            }
            .testTag(testTag)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(text = title, style = VestigeTheme.typography.title, color = titleColor)
            Text(text = desc, style = VestigeTheme.typography.pCompact, color = colors.dim)
            if (desc2 != null) {
                Text(text = desc2, style = VestigeTheme.typography.eyebrow, color = colors.dim)
            }
        }
        Text(text = trailing, style = VestigeTheme.typography.title, color = titleColor)
    }
}

private fun personaNameRes(persona: Persona): Int = when (persona) {
    Persona.WITNESS -> R.string.onboarding_persona_witness_name
    Persona.HARDASS -> R.string.onboarding_persona_hardass_name
    Persona.EDITOR -> R.string.onboarding_persona_editor_name
}

private fun personaDescRes(persona: Persona): Int = when (persona) {
    Persona.WITNESS -> R.string.onboarding_persona_witness_card
    Persona.HARDASS -> R.string.onboarding_persona_hardass_card
    Persona.EDITOR -> R.string.onboarding_persona_editor_card
}

@Composable
private fun DeleteAllDialog(onConfirm: () -> Unit, onDismiss: () -> Unit) {
    val colors = VestigeTheme.colors
    var typed by remember { mutableStateOf("") }
    val armed = typed == DELETE_TOKEN
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = colors.deep,
        titleContentColor = colors.ink,
        textContentColor = colors.dim,
        title = { Text(text = stringResource(id = R.string.settings_wipe_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(text = stringResource(id = R.string.settings_wipe_body))
                OutlinedTextField(
                    value = typed,
                    onValueChange = { typed = it },
                    singleLine = true,
                    label = { Text(text = stringResource(id = R.string.settings_wipe_placeholder)) },
                    modifier = Modifier.testTag(WIPE_FIELD_TAG),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm, enabled = armed) {
                Text(
                    text = stringResource(id = R.string.settings_wipe_confirm),
                    color = if (armed) colors.coral else colors.dim,
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(text = stringResource(id = R.string.settings_cancel), color = colors.dim)
            }
        },
    )
}
