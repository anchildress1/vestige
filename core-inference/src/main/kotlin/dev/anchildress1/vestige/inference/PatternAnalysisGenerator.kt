package dev.anchildress1.vestige.inference

import android.util.Log
import dev.anchildress1.vestige.model.DetectedPattern
import dev.anchildress1.vestige.model.PatternKind
import dev.anchildress1.vestige.model.Persona
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.json.JSONTokener
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Best-effort background analysis for temporal patterns. The deterministic detector decides what
 * counts; this call decides how to say the recurring time relationship back to the user.
 *
 * The persona contributes voice only — the foreground follow-up prompt is a different task with a
 * conflicting output contract, so this path takes a one-line tone descriptor, not that prompt.
 */
class PatternAnalysisGenerator(
    private val engine: LiteRtLmEngine,
    private val personaPromptComposer: (Persona) -> String = ::patternAnalysisPersonaTone,
    private val templateLoader: () -> String = { loadResource("/patterns/temporal-analysis.txt") },
    private val forbiddenPhraseDetector: (String) -> Boolean = ObservationResponseParser::containsForbiddenPhrase,
    private val zoneId: ZoneId = ZoneId.systemDefault(),
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {

    suspend fun generate(
        persona: Persona,
        pattern: DetectedPattern,
        evidence: List<PatternEvidenceEntry>,
    ): PatternAnalysisResult? = withContext(ioDispatcher) {
        if (pattern.kind != PatternKind.TEMPORAL_RELATIVE || evidence.isEmpty()) return@withContext null
        val raw = try {
            engine.generateText(buildSystemInstruction(persona), buildUserPrompt(pattern, evidence))
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (@Suppress("TooGenericExceptionCaught") error: Exception) {
            Log.w(TAG, "pattern analysis threw ${error.javaClass.simpleName}")
            return@withContext null
        }
        parse(raw)
    }

    private fun buildSystemInstruction(persona: Persona): String = buildString {
        append(personaPromptComposer(persona).trimEnd())
        append("\n\n")
        append(templateLoader().trimEnd())
    }

    private fun buildUserPrompt(pattern: DetectedPattern, evidence: List<PatternEvidenceEntry>): String = buildString {
        append("## PATTERN\n")
        append("kind: ${pattern.kind.serial}\n")
        append("signature: ${pattern.signatureJson}\n\n")
        append("## EVIDENCE\n")
        evidence.sortedBy { it.timestampEpochMs }
            .takeLast(MAX_EVIDENCE_ROWS)
            .forEach { entry ->
                append("- ")
                append(formatLocalTimestamp(entry.timestampEpochMs))
                entry.templateLabel?.takeIf { it.isNotBlank() }?.let { append(" | label=$it") }
                entry.tags.takeIf { it.isNotEmpty() }?.let { append(" | tags=${it.joinToString(",")}") }
                append(" | text=")
                append(entry.text.take(MAX_TEXT_CHARS).replace('\n', ' ').trim())
                append('\n')
            }
    }

    private fun formatLocalTimestamp(epochMs: Long): String =
        DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(Instant.ofEpochMilli(epochMs).atZone(zoneId))

    @Suppress("ReturnCount") // Model-output validation is clearer as guard clauses.
    private fun parse(raw: String): PatternAnalysisResult? {
        // Distinguishes each rejection so a deterministic fallback callout is diagnosable.
        // Length only — model output is derived from journal text, never a log sink.
        fun rejected(reason: String): PatternAnalysisResult? {
            Log.w(TAG, "pattern analysis rejected reason=$reason (rawLen=${raw.length})")
            return null
        }
        val root = findFirstParseableObject(raw) ?: return rejected("no-json")
        val title = root.optString("title").trim().sanitizeTitle() ?: return rejected("blank-title")
        val callout = root.optString("callout").trim().sanitizeCallout()
            ?: return rejected("blank-or-overlong-callout")
        if (forbiddenPhraseDetector(title) || forbiddenPhraseDetector(callout)) {
            return rejected("forbidden-phrase")
        }
        return PatternAnalysisResult(title = title, calloutText = callout)
    }

    private fun String.sanitizeTitle(): String? {
        val cleaned = removeSurrounding("\"")
            .replace(DISALLOWED_TITLE_CHARS, "")
            .trim()
            .truncateToTitleCap()
        return cleaned.takeIf { it.isNotBlank() }
    }

    private fun String.sanitizeCallout(): String? {
        val cleaned = removeSurrounding("\"")
            .lineSequence()
            .firstOrNull()
            ?.trim()
            .orEmpty()
        return cleaned.takeIf { it.isNotBlank() && it.length <= MAX_CALLOUT_CHARS }
    }

    private fun String.truncateToTitleCap(): String {
        if (length <= MAX_TITLE_CHARS) return this
        val trimmed = substring(0, MAX_TITLE_CHARS)
        val lastSpace = trimmed.lastIndexOf(' ')
        return if (lastSpace > MAX_TITLE_CHARS / 2) trimmed.substring(0, lastSpace).trim() else trimmed.trim()
    }

    @Suppress("ReturnCount") // Mirrors the existing tolerant JSON-object scanner shape.
    private fun findFirstParseableObject(raw: String): JSONObject? {
        if (raw.isBlank()) return null
        var cursor = 0
        while (cursor < raw.length) {
            val open = raw.indexOf('{', cursor).takeIf { it >= 0 } ?: return null
            val close = scanBalancedClose(raw, open)
            if (close == null) {
                cursor = open + 1
                continue
            }
            val candidate = raw.substring(open, close + 1)
            val parsed = runCatching { JSONTokener(candidate).nextValue() as? JSONObject }.getOrNull()
            if (parsed != null) return parsed
            cursor = open + 1
        }
        return null
    }

    private fun scanBalancedClose(raw: String, open: Int): Int? {
        var depth = 0
        var inString = false
        var escape = false
        for (i in open until raw.length) {
            val c = raw[i]
            if (escape) {
                escape = false
                continue
            }
            when {
                c == '\\' && inString -> escape = true

                c == '"' -> inString = !inString

                !inString && c == '{' -> depth++

                !inString && c == '}' -> {
                    depth--
                    if (depth == 0) return i
                }
            }
        }
        return null
    }

    companion object {
        const val MAX_TITLE_CHARS = 24
        const val MAX_CALLOUT_CHARS = 180
        private const val MAX_EVIDENCE_ROWS = 6
        private const val MAX_TEXT_CHARS = 180
        private const val TAG = "VestigePatternAnalysis"
        private val DISALLOWED_TITLE_CHARS: Regex = Regex("[^A-Za-z0-9\\- ]")

        private fun loadResource(path: String): String {
            val stream = PatternAnalysisGenerator::class.java.getResourceAsStream(path)
                ?: error("Pattern-analysis prompt resource missing: $path")
            return stream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        }
    }
}

private fun patternAnalysisPersonaTone(persona: Persona): String = when (persona) {
    Persona.WITNESS -> "Voice: quiet, calm, literal. State the relationship plainly. No judgment, no wordplay."
    Persona.HARDASS -> "Voice: blunt and direct. Name the relationship without softening. No insults, no advice."
    Persona.EDITOR -> "Voice: precise and economical. Tight phrasing. No filler, no advice."
}

data class PatternEvidenceEntry(
    val id: Long,
    val timestampEpochMs: Long,
    val text: String,
    val tags: List<String>,
    val templateLabel: String?,
)

data class PatternAnalysisResult(val title: String, val calloutText: String)
