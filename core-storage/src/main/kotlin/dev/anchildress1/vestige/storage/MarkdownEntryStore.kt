package dev.anchildress1.vestige.storage

import android.util.Log
import dev.anchildress1.vestige.model.ExtractionStatus
import dev.anchildress1.vestige.model.TemplateLabel
import java.io.File
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

/**
 * Read/write the markdown source-of-truth files at `{baseDir}/entries/{ISO8601}--{slug}.md`.
 * YAML frontmatter holds the structured fields; body is the entry text. Structured Phase-2
 * fields (`stated_commitment`, `entry_observations`, `confidence`) round-trip as JSON blobs.
 * Audio bytes never persist here.
 */
@Suppress("TooManyFunctions") // 4 read/write surface + 9 YAML helpers on one shape.
class MarkdownEntryStore(private val baseDir: File) {

    /**
     * Writes [entry], returning the file. Reuses [EntryEntity.markdownFilename] if set (re-eval
     * overwrites in place); otherwise mints a deterministic name with `-2`/`-3` collision
     * suffixes and stores it on the entry.
     */
    fun write(entry: EntryEntity): File {
        val entriesDir = ensureEntriesDir()
        val filename = if (entry.markdownFilename.isNotBlank()) {
            entry.markdownFilename
        } else {
            val initial = EntryFilename.buildFilename(entry.timestampEpochMs, entry.entryText)
            EntryFilename.resolveUnique(entriesDir, initial).also { entry.markdownFilename = it }
        }
        val target = File(entriesDir, filename)

        val payload = buildString {
            append(FRONTMATTER_FENCE).append('\n')
            append("schema_version: ").append(SCHEMA_VERSION).append('\n')
            append("timestamp: ").append(formatIso(entry.timestampEpochMs)).append('\n')
            append("duration_ms: ").append(entry.durationMs).append('\n')
            append("template_label: ").append(entry.templateLabel?.serial ?: NULL).append('\n')
            append("energy_descriptor: ").append(yamlScalar(entry.energyDescriptor)).append('\n')
            append("recurrence_link: ").append(yamlScalar(entry.recurrenceLink)).append('\n')
            append("stated_commitment: ").append(yamlJsonBlob(entry.statedCommitmentJson)).append('\n')
            append("tags:").append('\n')
            entry.tags.map { it.name }.sorted().forEach { tag -> appendLine("  - $tag") }
            append("confidence: ").append(yamlJsonInline(entry.confidenceJson)).append('\n')
            append("entry_observations: ").append(yamlJsonInline(entry.entryObservationsJson)).append('\n')
            append(FRONTMATTER_FENCE).append('\n')
            append('\n')
            append(entry.entryText)
            if (!entry.entryText.endsWith('\n')) append('\n')
        }

        // Atomic write: temp file then rename so a crash mid-write doesn't leave a half file.
        val tempFile = File(entriesDir, "$filename$TEMP_SUFFIX")
        tempFile.writeText(payload, Charsets.UTF_8)
        if (target.exists() && !target.delete()) {
            // Best-effort cleanup of the temp before raising — leave the target intact for diagnosis.
            val cleanedUp = tempFile.delete()
            error("Could not replace existing markdown at ${target.absolutePath} (temp cleanup=$cleanedUp)")
        }
        if (!tempFile.renameTo(target)) {
            val cleanedUp = tempFile.delete()
            error("Could not rename ${tempFile.absolutePath} → ${target.absolutePath} (temp cleanup=$cleanedUp)")
        }
        return target
    }

    /**
     * Read a markdown file produced by [write] back into an [EntryEntity]. Operational fields
     * (`extraction_status` / `attempt_count` / `last_error`) are intentionally absent from the
     * markdown source — a rebuilt entity has `extraction_status = COMPLETED` per
     * architecture-brief.md §"Field placement rules". `tags` ToMany cannot be hydrated without
     * a BoxStore, so callers do that themselves on rebuild; this method returns the entity
     * with the tag *names* attached as a separate side-channel via [readTagNames].
     */
    fun read(file: File): EntryEntity {
        require(file.isFile) { "Markdown entry file does not exist: ${file.absolutePath}" }
        val raw = file.readText(Charsets.UTF_8)
        val (front, body) = splitFrontmatter(raw)
        val parsed = parseFrontmatter(front)
        validateSchemaVersion(parsed)

        val timestamp = parsed[KEY_TIMESTAMP]?.let { parseIso(it) } ?: 0L
        val rawDuration = parsed[KEY_DURATION_MS]
        val durationMs = if (rawDuration != null) {
            rawDuration.toLongOrNull() ?: run {
                Log.w(TAG, "duration_ms in ${file.name} is not a valid Long: '$rawDuration'; defaulting to 0")
                0L
            }
        } else {
            0L
        }
        val templateLabel = parsed[KEY_TEMPLATE_LABEL]?.takeUnless { it == NULL }
            ?.let { TemplateLabel.fromSerial(it) }
        val energy = parsed[KEY_ENERGY_DESCRIPTOR]?.takeUnless { it == NULL }
        val recurrence = parsed[KEY_RECURRENCE_LINK]?.takeUnless { it == NULL }
        val commitment = parsed[KEY_STATED_COMMITMENT]?.takeUnless { it == NULL }
        val confidence = parsed[KEY_CONFIDENCE] ?: "{}"
        val observations = parsed[KEY_ENTRY_OBSERVATIONS] ?: "[]"

        return EntryEntity(
            markdownFilename = file.name,
            entryText = body.trimEnd(),
            timestampEpochMs = timestamp,
            durationMs = durationMs,
            templateLabel = templateLabel,
            energyDescriptor = energy,
            recurrenceLink = recurrence,
            statedCommitmentJson = commitment,
            entryObservationsJson = observations,
            confidenceJson = confidence,
            extractionStatus = ExtractionStatus.COMPLETED,
            attemptCount = 0,
            lastError = null,
        )
    }

    /** Returns the tag names embedded in [file]'s frontmatter, in the original order written. */
    fun readTagNames(file: File): List<String> {
        require(file.isFile) { "Markdown entry file does not exist: ${file.absolutePath}" }
        val (front, _) = splitFrontmatter(file.readText(Charsets.UTF_8))
        return parseTagNames(front)
    }

    /**
     * Deletes the markdown file for the given [filename]. No-ops if the file does not exist so
     * callers do not need to guard against a missing markdown file on a partially-written entry.
     * Returns `true` if a file was deleted, `false` if it was already absent.
     */
    fun delete(filename: String): Boolean {
        if (filename.isBlank()) return false
        val file = File(File(baseDir, ENTRIES_SUBDIR), filename)
        return file.exists() && file.delete()
    }

    /** All entry markdown files in `{baseDir}/entries`, alphabetical order. */
    fun listAll(): List<File> {
        val entriesDir = File(baseDir, ENTRIES_SUBDIR)
        if (!entriesDir.isDirectory) return emptyList()
        return entriesDir.listFiles { f -> f.isFile && f.name.endsWith(".md") }
            ?.sortedBy { it.name }
            ?: emptyList()
    }

    private fun ensureEntriesDir(): File {
        val entriesDir = File(baseDir, ENTRIES_SUBDIR)
        require(entriesDir.isDirectory || entriesDir.mkdirs()) {
            "Cannot create entries directory: $entriesDir"
        }
        return entriesDir
    }

    private fun formatIso(timestampEpochMs: Long): String {
        val instant = Instant.ofEpochMilli(timestampEpochMs).truncatedTo(ChronoUnit.SECONDS)
        return DateTimeFormatter.ISO_INSTANT.format(instant)
    }

    private fun parseIso(value: String): Long {
        val instant = Instant.parse(value.trim())
        return instant.toEpochMilli()
    }

    private fun yamlScalar(value: String?): String = if (value == null) NULL else value

    private fun yamlJsonBlob(value: String?): String = if (value == null) NULL else value

    private fun yamlJsonInline(value: String): String = value.ifBlank { "{}" }

    private fun splitFrontmatter(raw: String): Pair<String, String> {
        val withoutLeading = raw.trimStart()
        require(withoutLeading.startsWith(FRONTMATTER_FENCE)) {
            "Markdown entry missing leading frontmatter fence ($FRONTMATTER_FENCE)."
        }
        val afterFirst = withoutLeading.substringAfter(FRONTMATTER_FENCE).trimStart('\n')
        // `substringBefore`/`substringAfter` silently return the whole string when the closing
        // fence is missing — that turns a malformed entry into a "valid" one with the body
        // mis-parsed as frontmatter. Fail loudly instead.
        val closingFence = "\n$FRONTMATTER_FENCE"
        require(afterFirst.contains(closingFence)) {
            "Markdown entry missing closing frontmatter fence ($FRONTMATTER_FENCE)."
        }
        val frontmatter = afterFirst.substringBefore(closingFence)
        val body = afterFirst.substringAfter(closingFence).trimStart('\n')
        return frontmatter to body
    }

    private fun parseFrontmatter(front: String): Map<String, String> {
        val result = mutableMapOf<String, String>()
        val lines = front.lineSequence().toList()
        var index = 0
        while (index < lines.size) {
            val line = lines[index]
            val colonIndex = line.indexOf(':')
            if (colonIndex <= 0 || line.startsWith("  ")) {
                index++
                continue
            }
            val key = line.substring(0, colonIndex).trim()
            val rawValue = line.substring(colonIndex + 1).trim()
            result[key] = rawValue
            index++
        }
        return result
    }

    private fun validateSchemaVersion(parsed: Map<String, String>) {
        val schemaVersion = parsed[KEY_SCHEMA_VERSION]?.toIntOrNull()
            ?: error("Markdown entry missing or invalid schema_version.")
        require(schemaVersion == SCHEMA_VERSION) {
            "Unsupported markdown schema_version: $schemaVersion (expected $SCHEMA_VERSION)."
        }
    }

    private fun parseTagNames(front: String): List<String> {
        val tags = mutableListOf<String>()
        var inTagsBlock = false
        front.lineSequence().forEach { line ->
            when {
                line == "tags:" -> inTagsBlock = true

                inTagsBlock && line.startsWith(YAML_LIST_ITEM_PREFIX) ->
                    tags += line.substring(YAML_LIST_ITEM_PREFIX.length).trim()

                inTagsBlock && !line.startsWith("  ") && line.isNotBlank() -> inTagsBlock = false
            }
        }
        return tags
    }

    companion object {
        const val ENTRIES_SUBDIR = "entries"
        private const val TAG = "MarkdownEntryStore"
        const val SCHEMA_VERSION = 1
        private const val FRONTMATTER_FENCE = "---"
        private const val NULL = "null"
        private const val TEMP_SUFFIX = ".tmp"
        private const val YAML_LIST_ITEM_PREFIX = "  - "
        private const val KEY_SCHEMA_VERSION = "schema_version"
        private const val KEY_TIMESTAMP = "timestamp"
        private const val KEY_DURATION_MS = "duration_ms"
        private const val KEY_TEMPLATE_LABEL = "template_label"
        private const val KEY_ENERGY_DESCRIPTOR = "energy_descriptor"
        private const val KEY_RECURRENCE_LINK = "recurrence_link"
        private const val KEY_STATED_COMMITMENT = "stated_commitment"
        private const val KEY_CONFIDENCE = "confidence"
        private const val KEY_ENTRY_OBSERVATIONS = "entry_observations"
    }
}
