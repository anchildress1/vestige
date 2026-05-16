package dev.anchildress1.vestige.inference

import android.util.Log
import com.google.ai.edge.litertlm.Content
import dev.anchildress1.vestige.model.Persona
import dev.anchildress1.vestige.model.TemplateLabel
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.time.Clock
import java.time.ZoneId
import java.util.concurrent.ConcurrentHashMap

/**
 * Single-turn foreground call: persona prompt + audio (voice) or typed text → the same
 * `{transcription, follow_up}` envelope, so voice and typed entries review identically. Audio is
 * handed off as a temp PCM_S16LE WAV (the only handoff that works on LiteRT-LM 0.11.0); the file
 * is always deleted before this call returns, even on cancellation. Pure with respect to
 * [CaptureSession] — the caller advances session state from the parsed result.
 */
class ForegroundInference(
    private val engine: LiteRtLmEngine,
    private val cacheDir: File,
    private val clock: Clock = Clock.systemUTC(),
    private val zoneId: ZoneId = ZoneId.systemDefault(),
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {

    // Engine handle is single-threaded; do not call concurrently against the same engine.
    suspend fun runForegroundCall(audio: AudioChunk, persona: Persona): ForegroundResult = withContext(ioDispatcher) {
        require(audio.samples.isNotEmpty()) { "ForegroundInference requires non-empty audio samples." }
        require(audio.isFinal) {
            "runForegroundCall requires audio.isFinal == true (AudioCapture caps recordings at 30 s)."
        }
        require(cacheDir.isDirectory) { "cacheDir must be an existing directory: $cacheDir" }

        val callStartedAt = clock.instant()
        val systemPrompt = composeSystemPrompt(persona, callStartedAt)
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
     * Typed-entry counterpart of [runForegroundCall]. Same persona system prompt + same
     * `{transcription, follow_up}` parser, so a typed entry produces the identical Reviewing
     * surface a voice entry does — no temp WAV because there is no audio to hand off. The model
     * is required (no model-free typed path); the caller gates on readiness.
     */
    // Engine handle is single-threaded; do not call concurrently against the same engine.
    suspend fun runForegroundTextCall(text: String, persona: Persona): ForegroundResult = withContext(ioDispatcher) {
        require(text.isNotBlank()) { "ForegroundInference requires non-blank typed text." }

        val callStartedAt = clock.instant()
        val systemPrompt = composeSystemPrompt(persona, callStartedAt)
        val started = System.nanoTime()
        val rawResponse = engine.sendMessageContents(
            listOf(
                Content.Text(systemPrompt),
                Content.Text(text),
            ),
        )
        val elapsedMs = (System.nanoTime() - started) / NANOS_PER_MILLI
        Log.d(
            TAG,
            "runForegroundTextCall persona=$persona elapsed=${elapsedMs}ms raw=${rawResponse.length}c",
        )

        ForegroundResponseParser.parse(
            raw = rawResponse,
            persona = persona,
            elapsedMs = elapsedMs,
            completedAt = clock.instant(),
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

    private fun composeSystemPrompt(persona: Persona, startedAt: java.time.Instant): String {
        val personaPrompt = PersonaPromptComposer.compose(persona).trimEnd()
        return buildString {
            append(personaPrompt)
            append("\n\n")
            append(OUTPUT_SCHEMA_REMINDER)
            if (isGoblinHours(startedAt)) {
                append("\n\n")
                append(GOBLIN_HOURS_ADDENDUM.trimEnd())
            }
            append('\n')
        }
    }

    private fun isGoblinHours(startedAt: java.time.Instant): Boolean =
        startedAt.atZone(zoneId).hour in GOBLIN_HOURS_RANGE

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

        // Shared with TemplateLabeler so the addendum window and the post-extraction label
        // window cannot drift. Source of truth: TemplateLabel.GOBLIN_HOURS_LOCAL_HOUR_RANGE.
        internal val GOBLIN_HOURS_RANGE: IntRange = TemplateLabel.GOBLIN_HOURS_LOCAL_HOUR_RANGE
        internal const val GOBLIN_HOURS_RESOURCE = "/foreground/goblin-hours-addendum.txt"

        private val GOBLIN_HOURS_ADDENDUM: String by lazy {
            val stream = ForegroundInference::class.java.getResourceAsStream(GOBLIN_HOURS_RESOURCE)
                ?: error("Goblin-hours addendum resource missing: $GOBLIN_HOURS_RESOURCE")
            stream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        }

        private const val TAG = "VestigeForegroundInference"
        private const val NANOS_PER_MILLI = 1_000_000L
        private val activeTempWavs: MutableSet<String> = ConcurrentHashMap.newKeySet()

        // Held only across sweep+create+register so an overlapping call's sweep can't delete a
        // just-created WAV before it lands in [activeTempWavs]. The slow engine call runs
        // outside this lock.
        private val tempWavLock: Any = Any()
    }
}
