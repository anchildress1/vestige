package dev.anchildress1.vestige.corpus

import java.io.File
import java.time.ZonedDateTime
import java.time.format.DateTimeParseException

/** One row from `docs/stt-*-manifest.example.txt`. */
data class CorpusEntry(val id: String, val capturedAt: ZonedDateTime, val entryText: String)

/**
 * Loader for the pipe-delimited STT-C / STT-D manifest format. `#` lines and blanks skipped; every
 * other line is `id|isoZonedDateTime|entryText`. Extra pipes after the second collapse into
 * `entryText`.
 */
object CorpusManifest {
    fun load(file: File): List<CorpusEntry> {
        require(file.exists() && file.canRead()) { "Manifest not readable: ${file.absolutePath}" }
        return file.useLines { lines ->
            lines.withIndex()
                .filter { (_, raw) -> raw.isNotBlank() && !raw.trimStart().startsWith("#") }
                .map { (lineIndex, raw) -> parseLine(file, lineIndex + 1, raw) }
                .toList()
        }
    }

    private fun parseLine(source: File, lineNumber: Int, raw: String): CorpusEntry {
        val parts = raw.split('|', limit = ENTRY_FIELD_COUNT)
        require(parts.size == ENTRY_FIELD_COUNT) {
            "Malformed manifest line $lineNumber in ${source.name}: expected $ENTRY_FIELD_COUNT " +
                "pipe-delimited fields, got ${parts.size}"
        }
        val id = parts[0].trim()
        val captured = parts[1].trim()
        val text = parts[2].trim()
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
        return CorpusEntry(id = id, capturedAt = capturedAt, entryText = text)
    }

    private const val ENTRY_FIELD_COUNT = 3
}
