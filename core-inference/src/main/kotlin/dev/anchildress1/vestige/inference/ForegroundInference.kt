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
 * Foreground capture inference (Phase 2 Story 2.2; reframed by the STT-B fallback).
 *
 * Takes a normalized **final-chunk** [AudioChunk] from `AudioCapture` (Story 1.4) and runs one
 * Gemma 4 E4B call composed of:
 *  - the active [Persona]'s system prompt (via [PersonaPromptComposer]) — Story 1.8;
 *  - an XML-tag output schema reminder asking for `<transcription>`/`<follow_up>` blocks
 *    (ADR-002 §"Structured-output reliability"; the markdown-with-headers approach was
 *    rejected after codex review round 2 because verbatim transcriptions can legitimately
 *    contain `## TRANSCRIPTION` / `## FOLLOW_UP` lines and would be silently sliced);
 *  - the audio buffer handed off as `Content.AudioFile` against a temp PCM_S16LE WAV (the only
 *    handoff that works on LiteRT-LM 0.11.0 per ADR-001 §Q4 STT-A record).
 *
 * **Single-turn-per-capture** per the STT-B fallback (`adrs/ADR-002-multi-lens-extraction-pattern.md`
 * §"Multi-turn behavior"). The prompt no longer carries any prior-turn context — three on-device
 * rounds in May 2026 confirmed Gemma 4 E4B does not reference `## RECENT TURNS` history in its
 * follow-up regardless of count or instruction. Each capture is a self-contained exchange and the
 * caller is expected to construct a fresh [CaptureSession] for the next recording.
 *
 * **Final-chunk only.** Per ADR-001 §Q4 the audio path is capped at 30 s and `AudioCapture` emits
 * exactly one chunk per recording with `isFinal == true`. This API rejects non-final chunks at the
 * precondition; the >30 s multi-chunk orchestration was deferred to backlog row
 * `multi-chunk-foreground` and does not exist in v1.
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
    private val clock: Clock = Clock.systemUTC(),
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {

    /**
     * Run the foreground call. Suspends on the IO dispatcher; the engine handle is single-threaded
     * inside LiteRT-LM, so callers must not invoke this concurrently against the same engine.
     */
    suspend fun runForegroundCall(audio: AudioChunk, persona: Persona): ForegroundResult = withContext(ioDispatcher) {
        require(audio.samples.isNotEmpty()) { "ForegroundInference requires non-empty audio samples." }
        require(audio.isFinal) {
            "runForegroundCall is final-chunk only — `AudioCapture` caps each recording at " +
                "30 s and emits one isFinal=true chunk (ADR-001 §Q4 + STT-B fallback). " +
                "Multi-chunk orchestration is deferred to backlog `multi-chunk-foreground`."
        }
        require(cacheDir.isDirectory) { "cacheDir must be an existing directory: $cacheDir" }

        // Sweep crash leftovers, create the new temp WAV, and register it as active in a
        // single critical section. Without the lock, two overlapping `runForegroundCall`
        // invocations race: call A creates its temp file; before A registers it in
        // `activeTempWavs`, call B sweeps and deletes A's just-created file (codex review
        // round 6 P1). Holding `tempWavLock` makes sweep + create + register atomic
        // against the same lock taken by other in-flight calls. The expensive engine call
        // happens OUTSIDE the lock so concurrent inference is still allowed.
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
     * Discard a temp foreground WAV per AGENTS.md guardrail 11. Try delete first; if delete
     * fails (file locked, transient FS error), truncate the file to zero bytes so the audio
     * payload is gone even if the inode survives, then retry the delete and finally fall back
     * to `deleteOnExit` (which is best-effort on Android — process kill skips it). Every step
     * that fails gets a `Log.w` so a crash-leftover sweep can correlate.
     */
    private fun discardTempWav(temp: File) {
        if (temp.delete()) return
        Log.w(TAG, "Initial delete failed for ${temp.absolutePath}; truncating audio payload")
        runCatching { temp.outputStream().use { /* truncate to zero bytes */ } }
            .onFailure { Log.w(TAG, "Truncate failed for ${temp.absolutePath}: ${it.message}") }
        if (temp.delete()) return
        Log.w(TAG, "Retry delete failed for ${temp.absolutePath}; scheduling deleteOnExit")
        temp.deleteOnExit()
    }

    /**
     * Delete any leftover `vestige-fg-*.wav` files in [cacheDir] from a prior crash. Files that
     * are currently in use by another in-process foreground call are skipped via [activeTempWavs].
     * Reuses [discardTempWav] so a delete-failure on a crash leftover still gets the truncate
     * fallback — the audio bytes are zeroed even if the inode survives, matching the privacy
     * guarantee the active-call cleanup gives.
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

        // The reminder describes the output format without using literal opening/closing tag
        // pairs in the prompt, so a chatty model echoing the schema cannot collide with the
        // parser's tag matching. The parser still requires the actual tag literals in the
        // response and rejects duplicate blocks rather than guessing which one Gemma meant.
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
         * Guards the sweep + create + register sequence so a concurrent call's sweep cannot
         * delete this call's just-created temp WAV before it lands in [activeTempWavs] (codex
         * review round 6 P1). Held only for the short critical section; the slow engine call
         * runs outside the lock so concurrent inference is still allowed.
         */
        private val tempWavLock: Any = Any()
    }
}
