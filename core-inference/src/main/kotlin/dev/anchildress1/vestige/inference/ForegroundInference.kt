package dev.anchildress1.vestige.inference

import android.util.Log
import com.google.ai.edge.litertlm.Content
import dev.anchildress1.vestige.model.Persona
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import java.io.File
import java.time.Clock
import java.util.concurrent.ConcurrentHashMap

/**
 * Single-turn foreground call — persona prompt + audio or typed text → the same streaming
 * `{transcription, follow_up}` envelope. The temp WAV is always deleted before the flow ends,
 * even on cancellation.
 */
class ForegroundInference(
    private val engine: LiteRtLmEngine,
    private val cacheDir: File,
    private val clock: Clock = Clock.systemUTC(),
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {

    /**
     * Voice path → progressive [ForegroundStreamEvent]s. Engine handle is single-threaded; do not
     * collect concurrently against the same engine.
     */
    fun runForegroundCall(audio: AudioChunk, persona: Persona): Flow<ForegroundStreamEvent> {
        require(audio.samples.isNotEmpty()) { "ForegroundInference requires non-empty audio samples." }
        require(audio.isFinal) {
            "runForegroundCall requires audio.isFinal == true (AudioCapture caps recordings at 30 s)."
        }
        require(cacheDir.isDirectory) { "cacheDir must be an existing directory: $cacheDir" }

        return flow {
            val systemPrompt = composeSystemPrompt(persona)
            val temp = synchronized(tempWavLock) {
                sweepStaleTempWavs()
                File.createTempFile(TEMP_PREFIX, TEMP_SUFFIX, cacheDir).also {
                    activeTempWavs += it.absolutePath
                }
            }
            try {
                WavWriter.writeMonoFloatWav(temp, audio.samples, audio.sampleRateHz)
                emitEnvelope(
                    persona = persona,
                    label = "runForegroundCall",
                    systemInstruction = systemPrompt,
                    parts = listOf(Content.AudioFile(temp.absolutePath)),
                )
            } finally {
                discardTempWav(temp)
                activeTempWavs -= temp.absolutePath
            }
        }.flowOn(ioDispatcher)
    }

    /** Typed-entry counterpart of [runForegroundCall] — no temp WAV; model required. */
    fun runForegroundTextCall(text: String, persona: Persona): Flow<ForegroundStreamEvent> {
        require(text.isNotBlank()) { "ForegroundInference requires non-blank typed text." }

        return flow {
            val systemPrompt = composeSystemPrompt(persona)
            emitEnvelope(
                persona = persona,
                label = "runForegroundTextCall",
                systemInstruction = systemPrompt,
                parts = listOf(Content.Text(text)),
            )
        }.flowOn(ioDispatcher)
    }

    // Shared by the voice and typed paths so scan/parse logic exists once.
    private suspend fun FlowCollector<ForegroundStreamEvent>.emitEnvelope(
        persona: Persona,
        label: String,
        systemInstruction: String,
        parts: List<Content>,
    ) {
        val scanner = ForegroundStreamScanner()
        val started = System.nanoTime()
        var firstTokenAtNanos = 0L
        engine.streamMessageContents(systemInstruction, parts).collect { chunk ->
            // TTFT is the model's first emitted chunk, not the scanner's first surfaced event —
            // the scanner withholds until a tag closes, which would inflate the metric by the
            // whole transcription block and misrepresent the latency this story validates.
            if (firstTokenAtNanos == 0L) {
                firstTokenAtNanos = System.nanoTime()
                Log.d(TAG, "$label persona=$persona ttft=${(firstTokenAtNanos - started) / NANOS_PER_MILLI}ms")
            }
            scanner.accept(chunk).forEach { emit(it) }
        }
        val elapsedMs = (System.nanoTime() - started) / NANOS_PER_MILLI
        val raw = scanner.accumulated
        Log.d(TAG, "$label persona=$persona elapsed=${elapsedMs}ms raw=${raw.length}c")
        emit(
            ForegroundStreamEvent.Terminal(
                ForegroundResponseParser.parse(
                    raw = raw,
                    persona = persona,
                    elapsedMs = elapsedMs,
                    completedAt = clock.instant(),
                ),
            ),
        )
    }

    // Truncate-then-retry-delete on failure so audio bytes are unrecoverable even if the inode
    // survives. `internal` for JVM testability.
    internal fun discardTempWav(temp: File) {
        if (temp.delete()) return
        Log.w(TAG, "Initial delete failed for ${temp.absolutePath}; truncating audio payload")
        runCatching { temp.outputStream().use { } }
            .onFailure { Log.w(TAG, "Truncate failed for ${temp.absolutePath}: ${it.message}") }
        if (temp.delete()) return
        Log.w(TAG, "Retry delete failed for ${temp.absolutePath}; scheduling deleteOnExit")
        temp.deleteOnExit()
    }

    // Cleans up leftovers from prior crashes. Skips files held by an in-flight call.
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

        // Prose-only — naming the tag literals here would collide with the parser if the model
        // echoes the reminder back.
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

        // Held only across sweep+create+register so an overlapping call's sweep can't delete a
        // just-created WAV before it lands in [activeTempWavs]. The slow engine call runs
        // outside this lock.
        private val tempWavLock: Any = Any()
    }
}
