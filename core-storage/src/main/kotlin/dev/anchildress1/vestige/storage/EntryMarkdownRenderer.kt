package dev.anchildress1.vestige.storage

import android.util.Log
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
        append("template_label: ").append(yamlScalar(entry.templateLabel?.serial)).append('\n')
        append("energy_descriptor: ").append(yamlScalar(entry.energyDescriptor)).append('\n')
        append("recurrence_link: ").append(yamlScalar(entry.recurrenceLink)).append('\n')
        append("stated_commitment: ").append(yamlJsonBlob(entry.statedCommitmentJson)).append('\n')
        val tags = tagNames(entry)
        if (tags.isEmpty()) {
            append("tags: []").append('\n')
        } else {
            append("tags:").append('\n')
            tags.forEach { tag -> appendLine("  - ${yamlScalar(tag)}") }
        }
        append("confidence: ").append(yamlJsonInline(entry.confidenceJson)).append('\n')
        append("entry_observations: ").append(yamlJsonArrayInline(entry.entryObservationsJson)).append('\n')
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

    // YAML scalar values that contain control characters, quotes, or YAML-reserved indicators
    // produce invalid frontmatter when emitted bare. Wrap in double quotes with JSON-style
    // escaping for the small set of bytes that actually break a YAML 1.2 parser.
    private fun yamlScalar(value: String?): String = when {
        value == null -> NULL
        value.isEmpty() -> "\"\""
        needsQuoting(value) -> quoteYaml(value)
        else -> value
    }

    private fun yamlJsonBlob(value: String?): String = if (value == null) NULL else value

    private fun yamlJsonInline(value: String): String = value.ifBlank { "{}" }

    private fun yamlJsonArrayInline(value: String): String = value.ifBlank { "[]" }

    private fun needsQuoting(value: String): Boolean {
        val hasReserved = value.any { it in YAML_RESERVED_CHARS || it < ' ' }
        val hasLeadingIndicator = value.first() in YAML_LEADING_INDICATORS
        val hasBoundaryWhitespace = value.first().isWhitespace() || value.last().isWhitespace()
        return hasReserved || hasLeadingIndicator || hasBoundaryWhitespace
    }

    private fun quoteYaml(value: String): String = buildString {
        append('"')
        value.forEach { ch ->
            when (ch) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (ch < ' ') append("\\x%02x".format(ch.code)) else append(ch)
            }
        }
        append('"')
    }

    private fun tagNames(entry: EntryEntity): List<String> = runCatching { entry.tags.map { it.name }.sorted() }
        .onFailure { Log.e(TAG, "tag resolution failed for entry id=${entry.id}", it) }
        .getOrDefault(emptyList())

    private const val TAG = "VestigeMarkdownRenderer"
    private const val FRONTMATTER_FENCE = "---"
    private const val SCHEMA_VERSION = 1
    private const val NULL = "null"
    private val YAML_LEADING_INDICATORS: Set<Char> = setOf('-', '?', '!', '&', '*', '>', '|')
    private val YAML_RESERVED_CHARS: Set<Char> = setOf(':', '#', '"', '\\', '\n', '\r', '\t')
}
