package dev.anchildress1.vestige.storage

import java.time.Instant
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

/** Renders export markdown from the ObjectBox entry row. */
object EntryMarkdownRenderer {

    fun filenameFor(entry: EntryEntity): String =
        entry.markdownFilename.ifBlank { EntryFilename.buildFilename(entry.timestampEpochMs, entry.entryText) }

    fun render(entry: EntryEntity): String = buildString {
        append(FRONTMATTER_FENCE).append('\n')
        append("schema_version: ").append(SCHEMA_VERSION).append('\n')
        append("timestamp: ").append(formatIso(entry.timestampEpochMs)).append('\n')
        append("duration_ms: ").append(entry.durationMs).append('\n')
        append("persona: ").append(entry.persona.name.lowercase()).append('\n')
        append("follow_up: ").append(yamlScalar(entry.followUpText)).append('\n')
        append("template_label: ").append(entry.templateLabel?.serial ?: NULL).append('\n')
        append("energy_descriptor: ").append(yamlScalar(entry.energyDescriptor)).append('\n')
        append("recurrence_link: ").append(yamlScalar(entry.recurrenceLink)).append('\n')
        append("stated_commitment: ").append(yamlJsonBlob(entry.statedCommitmentJson)).append('\n')
        append("tags:").append('\n')
        tagNames(entry).forEach { tag -> appendLine("  - $tag") }
        append("confidence: ").append(yamlJsonInline(entry.confidenceJson)).append('\n')
        append("entry_observations: ").append(yamlJsonInline(entry.entryObservationsJson)).append('\n')
        append("lens_receipts: ").append(yamlJsonInline(entry.lensReceiptsJsonOrEmpty)).append('\n')
        append(FRONTMATTER_FENCE).append('\n')
        append('\n')
        append(entry.entryText)
        if (!entry.entryText.endsWith('\n')) append('\n')
    }

    private fun formatIso(timestampEpochMs: Long): String {
        val instant = Instant.ofEpochMilli(timestampEpochMs).truncatedTo(ChronoUnit.SECONDS)
        return DateTimeFormatter.ISO_INSTANT.format(instant)
    }

    private fun yamlScalar(value: String?): String = if (value == null) NULL else value

    private fun yamlJsonBlob(value: String?): String = if (value == null) NULL else value

    private fun yamlJsonInline(value: String): String = value.ifBlank { "{}" }

    private fun tagNames(entry: EntryEntity): List<String> = runCatching { entry.tags.map { it.name }.sorted() }
        .getOrDefault(emptyList())

    private const val FRONTMATTER_FENCE = "---"
    private const val SCHEMA_VERSION = 1
    private const val NULL = "null"
}
