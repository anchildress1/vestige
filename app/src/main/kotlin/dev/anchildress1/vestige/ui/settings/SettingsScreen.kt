package dev.anchildress1.vestige.ui.settings

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import dev.anchildress1.vestige.R
import dev.anchildress1.vestige.model.Persona
import dev.anchildress1.vestige.ui.components.EyebrowE
import dev.anchildress1.vestige.ui.components.VestigeScaffold
import dev.anchildress1.vestige.ui.theme.VestigeTheme

/** Static facts for the Settings surface (version + source link), bundled to keep arity low. */
data class SettingsInfo(val versionLabel: String, val sourceUrl: String)

/** Settings actions, grouped so the screen stays a small-arity surface. */
data class SettingsActions(
    val onSelectPersona: (Persona) -> Unit,
    val onExportToUri: (Uri) -> Unit,
    val onWipe: () -> Unit,
    val onOpenModelStatus: () -> Unit,
    val onOpenSource: () -> Unit,
    val onExit: () -> Unit,
)

private const val DELETE_TOKEN = "DELETE"
private const val EXPORT_FILENAME = "vestige-entries.zip"

/** Test handle for the typed-DELETE confirmation field. */
const val WIPE_FIELD_TAG = "settings_wipe_field"

@Composable
fun SettingsScreen(persona: Persona, info: SettingsInfo, actions: SettingsActions, modifier: Modifier = Modifier) {
    BackHandler(onBack = actions.onExit)
    var confirmingWipe by remember { mutableStateOf(false) }
    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/zip"),
    ) { uri -> uri?.let(actions.onExportToUri) }

    VestigeScaffold(modifier = modifier) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 24.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            EyebrowE(text = stringResource(id = R.string.settings_eyebrow))
            Text(
                text = stringResource(id = R.string.settings_header),
                style = VestigeTheme.typography.h1,
                color = VestigeTheme.colors.ink,
            )
            PersonaSection(selected = persona, onSelect = actions.onSelectPersona)
            DataSection(
                onExport = { exportLauncher.launch(EXPORT_FILENAME) },
                onDeleteAll = { confirmingWipe = true },
            )
            SettingsRow(
                label = stringResource(id = R.string.settings_model_status),
                section = stringResource(id = R.string.settings_section_model),
                onClick = actions.onOpenModelStatus,
            )
            AboutSection(info = info, onOpenSource = actions.onOpenSource)
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
private fun PersonaSection(selected: Persona, onSelect: (Persona) -> Unit) {
    EyebrowE(text = stringResource(id = R.string.settings_section_persona))
    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Persona.entries.forEach { persona ->
            val isSelected = persona == selected
            Text(
                text = stringResource(id = personaLabelRes(persona)),
                style = VestigeTheme.typography.title,
                color = if (isSelected) VestigeTheme.colors.lime else VestigeTheme.colors.dim,
                modifier = Modifier
                    .fillMaxWidth()
                    .selectable(selected = isSelected, role = Role.RadioButton) { onSelect(persona) }
                    .padding(vertical = 10.dp),
            )
        }
    }
}

private fun personaLabelRes(persona: Persona): Int = when (persona) {
    Persona.WITNESS -> R.string.settings_persona_witness
    Persona.HARDASS -> R.string.settings_persona_hardass
    Persona.EDITOR -> R.string.settings_persona_editor
}

@Composable
private fun DataSection(onExport: () -> Unit, onDeleteAll: () -> Unit) {
    val section = stringResource(id = R.string.settings_section_data)
    SettingsRow(label = stringResource(id = R.string.settings_export), section = section, onClick = onExport)
    Text(
        text = stringResource(id = R.string.settings_delete_all),
        style = VestigeTheme.typography.title,
        color = VestigeTheme.colors.coral,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(role = Role.Button, onClick = onDeleteAll)
            .padding(vertical = 10.dp),
    )
}

@Composable
private fun AboutSection(info: SettingsInfo, onOpenSource: () -> Unit) {
    EyebrowE(text = stringResource(id = R.string.settings_section_about))
    EyebrowE(text = stringResource(id = R.string.settings_version, info.versionLabel))
    SettingsRow(
        label = stringResource(id = R.string.settings_source),
        section = null,
        onClick = onOpenSource,
    )
    EyebrowE(text = stringResource(id = R.string.settings_license))
}

@Composable
private fun SettingsRow(label: String, section: String?, onClick: () -> Unit) {
    if (section != null) EyebrowE(text = section)
    Text(
        text = label,
        style = VestigeTheme.typography.title,
        color = VestigeTheme.colors.ink,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(role = Role.Button, onClick = onClick)
            .padding(vertical = 10.dp),
    )
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
