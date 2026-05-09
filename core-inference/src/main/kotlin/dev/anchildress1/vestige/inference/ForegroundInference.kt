package dev.anchildress1.vestige.inference

import android.util.Log
import com.google.ai.edge.litertlm.Content
import dev.anchildress1.vestige.model.Persona
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.time.Clock

/**
 * Foreground capture inference (Phase 2 Story 2.2).
 *
 * Takes a normalized **final-chunk** [AudioChunk] from `AudioCapture` (Story 1.4) plus the
 * in-session [Transcript] and runs one Gemma 4 E4B call composed of:
 *  - the active [Persona]'s system prompt (via [PersonaPromptComposer]) — Story 1.8;
 *  - the last [historyTurnLimit] turns of the transcript as recent context, oldest-first
 *    (ADR-002 §Q5 — default last 4);
 *  - a markdown-with-headers output schema reminder (ADR-002 §"Structured-output reliability");
 *  - the audio buffer handed off as `Content.AudioFile` against a temp PCM_S16LE WAV (the only
 *    handoff that works on LiteRT-LM 0.11.0 per ADR-001 §Q4 STT-A record).
 *
 * **Final-chunk only.** Per ADR-002 §"For >30s captures" intermediate chunks (`isFinal == false`)
 * must run a stripped-down transcription-only call — no persona, no session context, no follow-up.
 * This API rejects non-final chunks at the precondition; multi-chunk orchestration (concatenating
 * transcript-so-far before the final foreground call) is a separate story. A non-final chunk
 * arriving here is a caller bug, not a fallback to handle silently.
 *
 * Per AGENTS.md guardrail 11 the temp WAV is created, used, and deleted inside this call — even
 * on engine error or coroutine cancellation. No audio bytes survive past the response.
 *
 * Returns a [ForegroundResult] whose latency fields (`elapsedMs`, `completedAt`) feed ADR-002
 * §"Latency budget" instrumentation. Parse failures surface as [ForegroundResult.ParseFailure]
 * — the call does **not** silently retry; STT-C measures the rate.
 *
 * The call is pure with respect to [CaptureSession]: it does not advance state or append to the
 * transcript. The caller threads the result through `recordTranscription` then
 * `recordModelResponse` so the user transcription appears before the follow-up renders.
 */
class ForegroundInference(
    private val engine: LiteRtLmEngine,
    private val cacheDir: File,
    private val historyTurnLimit: Int = DEFAULT_HISTORY_TURN_LIMIT,
    private val clock: Clock = Clock.systemUTC(),
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {

    init {
        require(historyTurnLimit > 0) { "historyTurnLimit must be positive, was $historyTurnLimit" }
    }

    /**
     * Run the foreground call. Suspends on the IO dispatcher; the engine handle is single-threaded
     * inside LiteRT-LM, so callers must not invoke this concurrently against the same engine.
     */
    suspend fun runForegroundCall(audio: AudioChunk, transcript: Transcript, persona: Persona): ForegroundResult =
        withContext(ioDispatcher) {
            require(audio.samples.isNotEmpty()) { "ForegroundInference requires non-empty audio samples." }
            require(audio.isFinal) {
                "runForegroundCall is final-chunk only (ADR-002 §\"For >30s captures\"); " +
                    "intermediate chunks need a transcription-only call."
            }
            require(cacheDir.isDirectory) { "cacheDir must be an existing directory: $cacheDir" }

            val systemPrompt = composeSystemPrompt(persona, transcript)
            val temp = File.createTempFile(TEMP_PREFIX, TEMP_SUFFIX, cacheDir)
            val started = System.nanoTime()
            val rawResponse = try {
                WavWriter.writeMonoFloatWav(temp, audio.samples, audio.sampleRateHz)
                engine.sendMessageContents(
                    listOf(
                        Content.Text(systemPrompt),
                        Content.AudioFile(temp.absolutePath),
                    ),
                )
            } finally {
                if (!temp.delete()) {
                    temp.deleteOnExit()
                }
            }
            val elapsedMs = (System.nanoTime() - started) / NANOS_PER_MILLI
            Log.d(
                TAG,
                "runForegroundCall persona=$persona elapsed=${elapsedMs}ms raw=${rawResponse.length}c",
            )

            ForegroundResponseParser.parse(
                raw = rawResponse,
                persona = persona,
                elapsedMs = elapsedMs,
                completedAt = clock.instant(),
            )
        }

    private fun composeSystemPrompt(persona: Persona, transcript: Transcript): String {
        val personaPrompt = PersonaPromptComposer.compose(persona).trimEnd()
        val recentTurns = transcript.turns.takeLast(historyTurnLimit)
        return buildString {
            append(personaPrompt)
            append("\n\n")
            append(RECENT_TURNS_HEADER)
            append('\n')
            if (recentTurns.isEmpty()) {
                append(NO_RECENT_TURNS)
                append('\n')
            } else {
                recentTurns.forEach { turn ->
                    append("- ")
                    append(turn.speaker.name)
                    append(": ")
                    append(turn.text)
                    append('\n')
                }
            }
            append('\n')
            append(OUTPUT_SCHEMA_REMINDER)
            append('\n')
        }
    }

    companion object {
        const val DEFAULT_HISTORY_TURN_LIMIT: Int = 4

        internal const val RECENT_TURNS_HEADER = "## RECENT TURNS"
        internal const val NO_RECENT_TURNS = "(no prior turns in this session)"
        internal const val OUTPUT_SCHEMA_REMINDER = """## OUTPUT FORMAT
Reply with exactly the following two sections, headers included, nothing else before or between them:

## TRANSCRIPTION
<the user's spoken words verbatim>

## FOLLOW_UP
<your follow-up question or remark in the active persona's voice>"""

        private const val TAG = "VestigeForegroundInference"
        private const val TEMP_PREFIX = "vestige-fg-"
        private const val TEMP_SUFFIX = ".wav"
        private const val NANOS_PER_MILLI = 1_000_000L
    }
}
