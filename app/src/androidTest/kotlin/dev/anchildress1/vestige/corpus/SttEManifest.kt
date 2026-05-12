package dev.anchildress1.vestige.corpus

import java.io.File
import java.time.ZonedDateTime
import java.time.format.DateTimeParseException

/** One row from `docs/stt-e-manifest.example.txt`. Tags are pre-baked, not re-extracted. */
data class SttEEntry(val id: String, val capturedAt: ZonedDateTime, val tags: List<String>, val entryText: String)

/**
 * 4-column pipe-delimited loader for STT-E. Differs from [CorpusManifest] by carrying a comma-
 * separated `tags` field between datetime and text. Pre-baked tags keep the comparison focused on
 * retrieval ranking — STT-C already validated the tag-extraction layer separately.
 */
object SttEManifest {
    fun load(file: File): List<SttEEntry> {
        require(file.exists() && file.canRead()) { "Manifest not readable: ${file.absolutePath}" }
        return file.useLines { lines ->
            lines.withIndex()
                .filter { (_, raw) -> raw.isNotBlank() && !raw.trimStart().startsWith("#") }
                .map { (lineIndex, raw) -> parseLine(file, lineIndex + 1, raw) }
                .toList()
        }
    }

    private fun parseLine(source: File, lineNumber: Int, raw: String): SttEEntry {
        val parts = raw.split('|', limit = ENTRY_FIELD_COUNT)
        require(parts.size == ENTRY_FIELD_COUNT) {
            "Malformed manifest line $lineNumber in ${source.name}: expected $ENTRY_FIELD_COUNT " +
                "pipe-delimited fields, got ${parts.size}"
        }
        val id = parts[0].trim()
        val captured = parts[1].trim()
        val rawTags = parts[2].trim()
        val text = parts[3].trim()
        require(id.isNotEmpty()) { "Manifest line $lineNumber missing id" }
        require(text.isNotEmpty()) { "Manifest line $lineNumber ($id) missing entry text" }
        val capturedAt = try {
            ZonedDateTime.parse(captured)
        } catch (parseError: DateTimeParseException) {
            throw IllegalArgumentException(
                "Manifest line $lineNumber ($id) in ${source.name}: invalid ISO zoned datetime " +
                    "\"$captured\" — ${parseError.message}",
                parseError,
            )
        }
        val tags = rawTags.split(',').map { it.trim() }.filter { it.isNotEmpty() }
        return SttEEntry(id = id, capturedAt = capturedAt, tags = tags, entryText = text)
    }

    private const val ENTRY_FIELD_COUNT = 4
}
