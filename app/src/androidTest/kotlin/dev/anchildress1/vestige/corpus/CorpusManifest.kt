package dev.anchildress1.vestige.corpus

import java.io.File
import java.time.ZonedDateTime

/** One row from a `docs/stt-*-manifest.example.txt` corpus. Pipes split fields. */
data class CorpusEntry(val id: String, val capturedAt: ZonedDateTime, val entryText: String)

/**
 * Pipe-delimited fixture loader. `#` lines and blank lines are skipped; every other line must be
 * `id|isoZonedDateTime|entryText`. Pipes inside transcripts are not supported — sample-data
 * transcripts never contain `|`, and a brittle escape syntax is worse than the rule.
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
        return CorpusEntry(
            id = id,
            capturedAt = ZonedDateTime.parse(captured),
            entryText = text,
        )
    }

    private const val ENTRY_FIELD_COUNT = 3
}
