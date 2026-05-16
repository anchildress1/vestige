package dev.anchildress1.vestige.ui.history

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredHeightIn
import androidx.compose.foundation.layout.windowInsetsBottomHeight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.anchildress1.vestige.ui.components.AppTop
import dev.anchildress1.vestige.ui.components.AppTopStatuses
import dev.anchildress1.vestige.ui.components.EyebrowE
import dev.anchildress1.vestige.ui.components.Pill
import dev.anchildress1.vestige.ui.components.StatItem
import dev.anchildress1.vestige.ui.components.StatRibbon
import dev.anchildress1.vestige.ui.components.VestigeSurface
import dev.anchildress1.vestige.ui.components.limeLeftRuleForActive
import dev.anchildress1.vestige.ui.theme.VestigeTheme

@Suppress("LongMethod")
@Composable
fun EntryDetailScreen(
    viewModel: EntryDetailViewModel,
    onBack: () -> Unit,
    onNewEntry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val colors = VestigeTheme.colors

    LaunchedEffect(viewModel) {
        viewModel.deleteComplete.collect { onBack() }
    }

    var showDeleteDialog by rememberSaveable { mutableStateOf(false) }

    Column(modifier = modifier.fillMaxSize().background(colors.floor)) {
        // AppTop — overflow menu on the right slot for delete action
        AppTop(
            persona = (state as? EntryDetailUiState.Loaded)?.model?.personaName ?: "",
            status = AppTopStatuses.Ready,
            rightContent = {
                EntryOverflowMenu(onDeleteTap = { showDeleteDialog = true })
            },
        )

        when (val s = state) {
            EntryDetailUiState.Loading -> Unit

            EntryDetailUiState.NotFound -> Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 20.dp, vertical = 24.dp),
                contentAlignment = Alignment.TopStart,
            ) {
                Text(
                    text = EntryDetailCopy.NOT_FOUND,
                    style = VestigeTheme.typography.p,
                    color = colors.dim,
                )
            }

            is EntryDetailUiState.Loaded -> EntryDetailContent(
                model = s.model,
                modifier = Modifier.weight(1f),
            )
        }

        EntryDetailBottomBar(onBack = onBack, onNewEntry = onNewEntry)
        Spacer(Modifier.windowInsetsBottomHeight(WindowInsets.navigationBars))
    }

    if (showDeleteDialog) {
        EntryDeleteDialog(
            onConfirm = {
                showDeleteDialog = false
                viewModel.delete()
            },
            onDismiss = { showDeleteDialog = false },
        )
    }
}

@Suppress("LongMethod")
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun EntryDetailContent(model: EntryDetailUiModel, modifier: Modifier = Modifier) {
    val colors = VestigeTheme.colors
    Column(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 18.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Spacer(Modifier.height(12.dp))

        // Header — FILED eyebrow + entry number display
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Bottom,
        ) {
            EyebrowE(text = "${EntryDetailCopy.FILED_EYEBROW_PREFIX} · ${model.filedTimeLabel}")
            if (model.templateLabel != null) {
                Pill(
                    text = model.templateLabel,
                    color = colors.coral,
                    modifier = Modifier.testTag("entry_template_label"),
                )
            }
        }

        Text(
            text = model.entryNumberLabel,
            style = VestigeTheme.typography.displayBig,
            color = colors.ink,
            modifier = Modifier.testTag("entry_number"),
        )

        // Stat ribbon — AUDIO duration + WORDS count
        StatRibbon(
            items = listOf(
                StatItem(value = model.audioLabel, label = EntryDetailCopy.AUDIO_STAT_LABEL),
                StatItem(value = "${model.wordCount}", label = EntryDetailCopy.WORDS_STAT_LABEL),
            ),
            modifier = Modifier
                .fillMaxWidth()
                .semantics(mergeDescendants = true) {
                    contentDescription = "${model.audioLabel} audio, ${model.wordCount} words"
                },
        )

        // USER turn — muted transcription
        TranscriptBlock(
            eyebrow = EntryDetailCopy.YOU_LABEL,
            body = model.transcription,
            bodyColor = colors.dim,
            testTag = "entry_transcription",
        )

        // MODEL turn — persona reading section
        if (model.energyDescriptor != null || model.observations.isNotEmpty()) {
            val readingLabel = "${model.personaName} ${EntryDetailCopy.READING_LABEL_SUFFIX}"
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("entry_reading_card")
                    .semantics(mergeDescendants = true) {
                        contentDescription = "$readingLabel: ${model.energyDescriptor.orEmpty()}"
                    },
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                EyebrowE(text = readingLabel)
                VestigeSurface(
                    accentModifier = Modifier.limeLeftRuleForActive(),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        if (model.energyDescriptor != null) {
                            Text(
                                text = model.energyDescriptor.uppercase(),
                                style = VestigeTheme.typography.h1,
                                color = colors.lime,
                                modifier = Modifier.testTag("entry_energy_descriptor"),
                            )
                        }
                        model.observations.forEach { obs ->
                            Text(
                                text = obs.text,
                                style = VestigeTheme.typography.p,
                                color = colors.ink,
                            )
                        }
                    }
                }
            }
        }

        // Tags
        if (model.tags.isNotEmpty()) {
            FlowRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("entry_tags"),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                model.tags.forEach { tag ->
                    Pill(
                        text = tag,
                        color = colors.faint,
                        modifier = Modifier.semantics { contentDescription = "tag: $tag" },
                    )
                }
            }
        }

        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun TranscriptBlock(
    eyebrow: String,
    body: String,
    bodyColor: androidx.compose.ui.graphics.Color,
    testTag: String,
) {
    val colors = VestigeTheme.colors
    // testTag on the outer container so onNodeWithTag finds the visible block in the merged tree.
    Column(
        modifier = Modifier
            .testTag(testTag)
            .fillMaxWidth()
            .background(colors.s1)
            .padding(horizontal = 12.dp, vertical = 12.dp)
            .semantics(mergeDescendants = true) {
                contentDescription = "$eyebrow: $body"
            },
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        EyebrowE(text = eyebrow)
        Text(
            text = body.ifBlank { "—" },
            style = VestigeTheme.typography.p,
            color = bodyColor,
        )
    }
}

@Composable
private fun EntryOverflowMenu(onDeleteTap: () -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        Box(
            modifier = Modifier
                .requiredHeightIn(min = 48.dp)
                .clickable { expanded = true }
                .padding(horizontal = 12.dp)
                .semantics {
                    contentDescription = EntryDetailCopy.OVERFLOW_CD
                    role = Role.Button
                },
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = "⋮",
                style = VestigeTheme.typography.h1,
                color = VestigeTheme.colors.ink,
            )
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(
                text = { Text(EntryDetailCopy.OVERFLOW_DELETE) },
                onClick = {
                    expanded = false
                    onDeleteTap()
                },
                modifier = Modifier.testTag("overflow_delete"),
            )
        }
    }
}

@Composable
private fun EntryDeleteDialog(onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = EntryDetailCopy.DELETE_TITLE,
                style = VestigeTheme.typography.h1,
                color = VestigeTheme.colors.ink,
            )
        },
        text = {
            Text(
                text = EntryDetailCopy.DELETE_BODY,
                style = VestigeTheme.typography.p,
                color = VestigeTheme.colors.dim,
            )
        },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                modifier = Modifier.testTag("delete_confirm"),
            ) {
                Text(EntryDetailCopy.DELETE_CONFIRM, color = VestigeTheme.colors.coral)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(EntryDetailCopy.DELETE_CANCEL, color = VestigeTheme.colors.dim)
            }
        },
        containerColor = VestigeTheme.colors.s2,
        titleContentColor = VestigeTheme.colors.ink,
    )
}

@Suppress("LongMethod")
@Composable
private fun EntryDetailBottomBar(onBack: () -> Unit, onNewEntry: () -> Unit) {
    val colors = VestigeTheme.colors
    val hairline = colors.hair
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .drawBehind {
                drawLine(
                    color = hairline,
                    start = Offset(0f, 0f),
                    end = Offset(size.width, 0f),
                    strokeWidth = Stroke.HairlineWidth,
                )
            },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Back button — left slot
        Box(
            modifier = Modifier
                .requiredHeightIn(min = 56.dp)
                .padding(horizontal = 18.dp)
                .clickable(onClick = onBack)
                .semantics {
                    role = Role.Button
                    contentDescription = EntryDetailCopy.BACK_CD
                },
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = EntryDetailCopy.BACK_LABEL,
                style = VestigeTheme.typography.h1,
                color = colors.dim,
                modifier = Modifier.testTag("detail_back"),
            )
        }

        Spacer(modifier = Modifier.weight(1f))

        // New entry CTA — right slot
        Box(
            modifier = Modifier
                .requiredHeightIn(min = 56.dp)
                .padding(horizontal = 18.dp)
                .clickable(onClick = onNewEntry)
                .semantics {
                    role = Role.Button
                    contentDescription = EntryDetailCopy.NEW_ENTRY_CD
                },
            contentAlignment = Alignment.Center,
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    text = EntryDetailCopy.NEW_ENTRY_LABEL,
                    style = VestigeTheme.typography.eyebrow,
                    color = colors.lime,
                    modifier = Modifier.testTag("detail_new_entry"),
                )
            }
        }
    }
}
