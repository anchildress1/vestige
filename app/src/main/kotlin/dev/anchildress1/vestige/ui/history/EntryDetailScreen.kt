@file:Suppress("TooManyFunctions") // Screen split into small private composables — clarity over a god-function.

package dev.anchildress1.vestige.ui.history

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.anchildress1.vestige.ui.components.AppTop
import dev.anchildress1.vestige.ui.components.AppTopStatuses
import dev.anchildress1.vestige.ui.components.BottomTab
import dev.anchildress1.vestige.ui.components.EyebrowE
import dev.anchildress1.vestige.ui.components.Pill
import dev.anchildress1.vestige.ui.components.VestigeBottomNav
import dev.anchildress1.vestige.ui.components.VestigeSpinner
import dev.anchildress1.vestige.ui.components.limeLeftRuleForActive
import dev.anchildress1.vestige.ui.theme.VestigeTheme
import java.util.Locale

@Composable
private fun LensTone.color(): Color {
    val colors = VestigeTheme.colors
    return when (this) {
        LensTone.CANONICAL -> colors.lime
        LensTone.CONFLICT -> colors.coral
        LensTone.AMBIGUOUS -> colors.ember
        LensTone.CANDIDATE -> colors.teal
    }
}

@Composable
private fun statusTone(status: String): Color {
    val colors = VestigeTheme.colors
    return when (status) {
        EntryDetailCopy.THREE_LENS_STATUS_CONFLICT -> colors.coral
        EntryDetailCopy.THREE_LENS_STATUS_CANDIDATE -> colors.teal
        EntryDetailCopy.THREE_LENS_STATUS_AMBIGUOUS -> colors.ember
        else -> colors.lime
    }
}

@Composable
fun EntryDetailScreen(
    viewModel: EntryDetailViewModel,
    onBack: () -> Unit,
    onNavSelect: (BottomTab) -> Unit = {},
    onMenuTap: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val colors = VestigeTheme.colors

    Column(modifier = modifier.fillMaxSize().background(colors.floor)) {
        AppTop(
            persona = (state as? EntryDetailUiState.Loaded)?.model?.personaName ?: "",
            status = AppTopStatuses.Ready,
            onMenuTap = onMenuTap,
        )
        when (val s = state) {
            EntryDetailUiState.Loading -> Spacer(Modifier.weight(1f))

            EntryDetailUiState.NotFound -> Box(
                modifier = Modifier.weight(1f).fillMaxWidth().padding(20.dp),
                contentAlignment = Alignment.TopStart,
            ) {
                Text(text = EntryDetailCopy.NOT_FOUND, style = VestigeTheme.typography.p, color = colors.dim)
            }

            is EntryDetailUiState.Loaded -> EntryDetailContent(
                model = s.model,
                onBack = onBack,
                modifier = Modifier.weight(1f),
            )
        }
        VestigeBottomNav(active = BottomTab.HISTORY, onSelect = onNavSelect)
    }
}

@Suppress("LongMethod")
@Composable
private fun EntryDetailContent(model: EntryDetailUiModel, onBack: () -> Unit, modifier: Modifier = Modifier) {
    val colors = VestigeTheme.colors
    Column(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 18.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Spacer(Modifier.height(8.dp))
        Box(
            modifier = Modifier
                .clickable(onClick = onBack)
                .semantics {
                    role = Role.Button
                    contentDescription = EntryDetailCopy.BACK_CD
                }
                .padding(vertical = 6.dp),
        ) {
            EyebrowE(text = "← BACK", modifier = Modifier.testTag("detail_back"))
        }
        Text(
            text = model.timeOfDayLabel,
            style = VestigeTheme.typography.displayBig,
            color = colors.ink,
            modifier = Modifier.testTag("entry_time"),
        )
        EyebrowE(text = "${model.dateLabel} · ${model.audioLabel.uppercase()} · ${model.wordCount} WORDS")
        if (model.templateLabel != null) {
            Pill(
                text = model.templateLabel.uppercase(Locale.US),
                modifier = Modifier.testTag("entry_template_label"),
            )
        }

        if (model.followUp != null) {
            FollowUpCard(personaName = model.personaName, body = model.followUp)
        }

        when (model.extraction) {
            ExtractionDisplay.COMPLETE -> {
                ThreeLensRead(status = model.lensStatus, lenses = model.lenses)
                FieldGrid(fields = model.fields)
            }

            ExtractionDisplay.FAILED -> ExtractionFailedBand()

            ExtractionDisplay.NO_READ -> Unit

            ExtractionDisplay.IN_PROGRESS -> {
                ExtractingBand()
                LensSkeletonRow()
                FieldSkeletonGrid()
            }
        }

        ObservationRows(model.observations)
        EntryTagsRow(tags = model.tags)
        // Transcript sits at the very bottom of the scroll, after the tags.
        TranscriptBlock(
            eyebrow = EntryDetailCopy.YOU_LABEL,
            body = model.transcription,
            testTag = "entry_transcription",
        )
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun FollowUpCard(personaName: String, body: String) {
    val colors = VestigeTheme.colors
    val eyebrow = "$personaName · FOLLOW-UP"
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(colors.s1)
            .limeLeftRuleForActive()
            .testTag("entry_follow_up")
            .semantics(mergeDescendants = true) { contentDescription = "$eyebrow: $body" }
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        EyebrowE(text = eyebrow, color = colors.lime)
        Text(text = body, style = VestigeTheme.typography.p, color = colors.ink)
    }
}

@Composable
private fun ThreeLensRead(status: String, lenses: List<LensRead>) {
    val colors = VestigeTheme.colors
    Column(
        modifier = Modifier.fillMaxWidth().testTag("entry_three_lens"),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // The eyebrow label is weighted so it yields/clips first; the CANONICAL_WITH_CONFLICT
        // status is unweighted, so the Row measures it at full intrinsic width — it is never
        // cut off, on any device width.
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            EyebrowE(
                text = EntryDetailCopy.THREE_LENS_EYEBROW,
                modifier = Modifier.weight(1f),
                color = colors.lime,
                maxLines = 1,
                softWrap = false,
            )
            EyebrowE(text = status, color = statusTone(status), maxLines = 1)
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            lenses.forEach { lens ->
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    EyebrowE(text = lens.label, maxLines = 1, softWrap = false)
                    Text(text = lens.value, style = VestigeTheme.typography.pCompact, color = lens.tone.color())
                }
            }
        }
        val rawReads = lenses.filter { it.rawResponse != null }
        if (rawReads.isNotEmpty()) {
            RawModelOutput(rawReads)
        }
    }
}

@Composable
private fun RawModelOutput(lenses: List<LensRead>) {
    val colors = VestigeTheme.colors
    var expanded by remember { mutableStateOf(false) }
    val toggleCd = if (expanded) EntryDetailCopy.RAW_OUTPUT_COLLAPSE_CD else EntryDetailCopy.RAW_OUTPUT_EXPAND_CD
    Column(
        modifier = Modifier.fillMaxWidth().testTag("entry_raw_output"),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded }
                .semantics {
                    role = Role.Button
                    contentDescription = toggleCd
                }
                .padding(vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            EyebrowE(
                text = EntryDetailCopy.RAW_OUTPUT_EYEBROW,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                softWrap = false,
            )
            EyebrowE(text = if (expanded) "−" else "+", color = colors.lime, maxLines = 1)
        }
        if (expanded) {
            lenses.forEach { lens ->
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(colors.s1)
                        .padding(horizontal = 14.dp, vertical = 10.dp)
                        .semantics(mergeDescendants = true) {
                            liveRegion = LiveRegionMode.Polite
                            contentDescription = "${lens.label} raw output: ${lens.rawResponse.orEmpty()}"
                        },
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    EyebrowE(text = lens.label, maxLines = 1, softWrap = false)
                    Text(
                        text = lens.rawResponse ?: EntryDetailCopy.RAW_OUTPUT_NONE,
                        style = VestigeTheme.typography.pCompact,
                        color = colors.ink,
                    )
                }
            }
        }
    }
}

@Composable
private fun FieldGrid(fields: List<FieldRow>) {
    val colors = VestigeTheme.colors
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(colors.s1)
            .testTag("entry_field_grid"),
        verticalArrangement = Arrangement.spacedBy(0.dp),
    ) {
        fields.forEach { field ->
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                EyebrowE(
                    text = field.label,
                    modifier = Modifier.weight(FIELD_LABEL_WEIGHT),
                    maxLines = 1,
                    softWrap = false,
                )
                Text(
                    text = field.value,
                    style = VestigeTheme.typography.p,
                    color = colors.ink,
                    modifier = Modifier.weight(FIELD_VALUE_WEIGHT),
                )
                EyebrowE(text = field.tone.name, color = field.tone.color(), maxLines = 1, softWrap = false)
            }
        }
    }
}

@Composable
private fun ObservationRows(observations: List<ObservationLine>) {
    if (observations.isEmpty()) return
    val colors = VestigeTheme.colors
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(colors.s1)
            .testTag("entry_observations")
            .semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
        verticalArrangement = Arrangement.spacedBy(0.dp),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            EyebrowE(text = EntryDetailCopy.OBSERVATIONS_EYEBROW, color = colors.lime)
            observations.forEach { line ->
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(text = line.text, style = VestigeTheme.typography.p, color = colors.ink)
                    observationMeta(line)?.let { meta ->
                        EyebrowE(text = meta, color = colors.dim)
                    }
                }
            }
        }
    }
}

@Composable
private fun ExtractingBand() {
    val colors = VestigeTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(colors.s1)
            .testTag("entry_extracting")
            .semantics(mergeDescendants = true) {
                liveRegion = LiveRegionMode.Polite
                contentDescription =
                    "${EntryDetailCopy.EXTRACTING_EYEBROW}. ${EntryDetailCopy.EXTRACTING_BODY}"
            }
            .padding(horizontal = 14.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.Top,
    ) {
        VestigeSpinner()
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            EyebrowE(text = EntryDetailCopy.EXTRACTING_EYEBROW, color = colors.lime)
            Text(text = EntryDetailCopy.EXTRACTING_BODY, style = VestigeTheme.typography.p, color = colors.dim)
        }
    }
}

@Composable
private fun ExtractionFailedBand() {
    val colors = VestigeTheme.colors
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(colors.s1)
            .testTag("entry_extracting_failed")
            .semantics(mergeDescendants = true) {
                liveRegion = LiveRegionMode.Polite
                contentDescription = "${EntryDetailCopy.FAILED_EYEBROW}. ${EntryDetailCopy.FAILED_BODY}"
            }
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        EyebrowE(text = EntryDetailCopy.FAILED_EYEBROW, color = colors.coral)
        Text(text = EntryDetailCopy.FAILED_BODY, style = VestigeTheme.typography.p, color = colors.dim)
    }
}

@Composable
private fun LensSkeletonRow() {
    Row(
        modifier = Modifier.fillMaxWidth().testTag("entry_lens_skeleton"),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        repeat(LENS_COLUMNS) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                SkeletonBar(widthFraction = 0.6f)
                SkeletonBar(widthFraction = 0.9f)
            }
        }
    }
}

@Composable
private fun FieldSkeletonGrid() {
    Column(
        modifier = Modifier.fillMaxWidth().testTag("entry_field_skeleton"),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        repeat(FIELD_SKELETON_ROWS) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                SkeletonBar(modifier = Modifier.weight(FIELD_LABEL_WEIGHT))
                SkeletonBar(modifier = Modifier.weight(FIELD_VALUE_WEIGHT))
            }
        }
    }
}

@Composable
private fun SkeletonBar(modifier: Modifier = Modifier, widthFraction: Float = 1f) {
    Box(
        modifier = modifier
            .fillMaxWidth(widthFraction)
            .height(SKELETON_BAR_HEIGHT)
            .background(VestigeTheme.colors.s2),
    )
}

@Composable
private fun TranscriptBlock(eyebrow: String, body: String, testTag: String) {
    val colors = VestigeTheme.colors
    Column(
        modifier = Modifier
            .testTag(testTag)
            .fillMaxWidth()
            .background(colors.s1)
            .padding(horizontal = 12.dp, vertical = 12.dp)
            .semantics(mergeDescendants = true) { contentDescription = "$eyebrow: $body" },
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        EyebrowE(text = eyebrow)
        Text(text = body.ifBlank { "—" }, style = VestigeTheme.typography.p, color = colors.dim)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun EntryTagsRow(tags: List<String>) {
    if (tags.isEmpty()) return
    val colors = VestigeTheme.colors
    Column(
        modifier = Modifier.fillMaxWidth().testTag("entry_tags"),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        EyebrowE(text = "▸ TAGS")
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            tags.forEach { tag -> TagChip(tag = tag, color = colors.faint) }
        }
    }
}

@Composable
private fun TagChip(tag: String, color: Color) {
    Box(
        modifier = Modifier
            .border(width = 1.dp, color = color, shape = RectangleShape)
            .semantics { contentDescription = "tag: $tag" }
            .padding(horizontal = 10.dp, vertical = 4.dp),
    ) {
        Text(text = tag, style = VestigeTheme.typography.eyebrow, color = color)
    }
}

private fun observationMeta(observation: ObservationLine): String? {
    val evidence = observation.evidence
        ?.replace('-', ' ')
        ?.uppercase(Locale.ROOT)
        ?.takeIf(String::isNotBlank)
    val fields = observation.fields
        .map(String::trim)
        .filter(String::isNotEmpty)
        .joinToString(", ")
        .takeIf(String::isNotBlank)
    val joined = listOfNotNull(evidence, fields).joinToString(" · ")
    return joined.takeIf(String::isNotBlank)
}

private const val FIELD_LABEL_WEIGHT = 0.32f
private const val FIELD_VALUE_WEIGHT = 0.68f
private const val LENS_COLUMNS = 3
private const val FIELD_SKELETON_ROWS = 5
private val SKELETON_BAR_HEIGHT = 12.dp
