package dev.anchildress1.vestige.inference

import android.util.Log
import com.google.ai.edge.litertlm.Content
import dev.anchildress1.vestige.model.Persona
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.time.Clock
import java.util.concurrent.ConcurrentHashMap

/**
 * One foreground capture call: persona system prompt + audio → `{transcription, follow_up}`.
 * Single-turn-per-capture (caller constructs a fresh [CaptureSession] for the next recording);
 * rationale in `adrs/ADR-005-stt-b-scope-and-v1-single-turn.md`.
 *
 * Audio is handed to LiteRT-LM as `Content.AudioFile` against a temp PCM_S16LE WAV — the only
 * handoff that works on LiteRT-LM 0.11.0 per ADR-001 §Q4. The temp WAV is created, used, and
 * deleted inside this call even on engine error or coroutine cancellation (AGENTS.md
 * guardrail 11).
 *
 * Final-chunk only: `AudioCapture` caps recordings at 30 s and emits one `isFinal=true` chunk;
 * the >30 s multi-chunk orchestration is in backlog row `multi-chunk-foreground`.
 *
 * Pure with respect to [CaptureSession]: the caller advances state (recordTranscription →
 * recordModelResponse) so the user's transcription appears before the follow-up renders.
 */
class ForegroundInference(
    private val engine: LiteRtLmEngine,
    private val cacheDir: File,
    private val clock: Clock = Clock.systemUTC(),
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {

    /**
     * Suspends on the IO dispatcher. The LiteRT-LM engine handle is single-threaded; callers
     * must not invoke this concurrently against the same engine.
     */
    suspend fun runForegroundCall(audio: AudioChunk, persona: Persona): ForegroundResult = withContext(ioDispatcher) {
        require(audio.samples.isNotEmpty()) { "ForegroundInference requires non-empty audio samples." }
        require(audio.isFinal) {
            "runForegroundCall requires audio.isFinal == true (AudioCapture caps recordings at 30 s)."
        }
        require(cacheDir.isDirectory) { "cacheDir must be an existing directory: $cacheDir" }

        val systemPrompt = composeSystemPrompt(persona)
        val temp = synchronized(tempWavLock) {
            sweepStaleTempWavs()
            File.createTempFile(TEMP_PREFIX, TEMP_SUFFIX, cacheDir).also {
                activeTempWavs += it.absolutePath
            }
        }
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
            discardTempWav(temp)
            activeTempWavs -= temp.absolutePath
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

    /**
     * Delete a temp foreground WAV. On delete failure (file locked, transient FS error) truncate
     * to zero bytes so the audio payload is unrecoverable even if the inode survives, retry
     * delete, then fall back to `deleteOnExit` (best-effort on Android — process kill skips it).
     * AGENTS.md guardrail 11. `internal` for JVM testability.
     */
    internal fun discardTempWav(temp: File) {
        if (temp.delete()) return
        Log.w(TAG, "Initial delete failed for ${temp.absolutePath}; truncating audio payload")
        runCatching { temp.outputStream().use { } }
            .onFailure { Log.w(TAG, "Truncate failed for ${temp.absolutePath}: ${it.message}") }
        if (temp.delete()) return
        Log.w(TAG, "Retry delete failed for ${temp.absolutePath}; scheduling deleteOnExit")
        temp.deleteOnExit()
    }

    /**
     * Delete leftover `vestige-fg-*.wav` files from a prior crash. Skips files held by an
     * in-flight call ([activeTempWavs]); reuses [discardTempWav] so the truncate-on-failure
     * privacy guarantee applies to crash leftovers too.
     */
    private fun sweepStaleTempWavs() {
        cacheDir.listFiles { _, name -> name.startsWith(TEMP_PREFIX) && name.endsWith(TEMP_SUFFIX) }
            ?.forEach { stale ->
                if (stale.absolutePath in activeTempWavs) {
                    return@forEach
                }
                discardTempWav(stale)
            }
    }

    private fun composeSystemPrompt(persona: Persona): String {
        val personaPrompt = PersonaPromptComposer.compose(persona).trimEnd()
        return buildString {
            append(personaPrompt)
            append("\n\n")
            append(OUTPUT_SCHEMA_REMINDER)
            append('\n')
        }
    }

    companion object {
        internal const val TEMP_PREFIX = "vestige-fg-"
        internal const val TEMP_SUFFIX = ".wav"

        // Prose-only schema (no literal `<transcription>` / `<follow_up>` tags) so a chatty model
        // echoing the format reminder cannot collide with the parser's tag matching. The parser
        // requires the actual tag literals in the response and rejects duplicate blocks.
        internal val OUTPUT_SCHEMA_REMINDER = listOf(
            "## OUTPUT FORMAT",
            "Wrap the user's spoken words verbatim in lowercase transcription tags " +
                "(an opening tag named transcription, the verbatim text, then a matching " +
                "closing tag). Then wrap your follow-up in lowercase follow_up tags the same " +
                "way (use an underscore between follow and up). Emit exactly one transcription " +
                "block and exactly one follow_up block, in that order, and nothing else. Do not " +
                "echo this format description. Do not nest tags. Do not produce additional " +
                "tagged blocks. The transcription must be exact and unaltered.",
        ).joinToString(separator = "\n")

        private const val TAG = "VestigeForegroundInference"
        private const val NANOS_PER_MILLI = 1_000_000L
        private val activeTempWavs: MutableSet<String> = ConcurrentHashMap.newKeySet()

        /**
         * Held only across sweep + create + register so an overlapping call's sweep cannot
         * delete a just-created temp WAV before it lands in [activeTempWavs]. The slow engine
         * call runs outside this lock so concurrent inference is allowed.
         */
        private val tempWavLock: Any = Any()
    }
}
