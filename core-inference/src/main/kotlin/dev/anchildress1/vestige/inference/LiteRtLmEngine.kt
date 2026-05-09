package dev.anchildress1.vestige.inference

import android.util.Log
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.LogSeverity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Backend selection mapped to LiteRT-LM SDK options. Phase 1 only exercises [Cpu]; [Gpu] and
 * [Npu] are wired so STT-A and later phases can flip the flag without re-shaping this API.
 * NPU requires the host's `nativeLibraryDir`, which only an Android `Context` can hand over —
 * the caller passes it in.
 */
sealed interface BackendChoice {
    data object Cpu : BackendChoice
    data object Gpu : BackendChoice
    data class Npu(val nativeLibraryDir: String) : BackendChoice
}

/**
 * Thin lifecycle wrapper around LiteRT-LM's [Engine]. Long-lived: call [initialize] once after
 * the model artifact is verified, then [generateText] (text-only) or [sendMessageContents]
 * (multimodal — STT-A audio probes) for each call. The native engine is released on [close].
 *
 * Multi-turn conversation state is owned by `SessionState` in `:app` (Phase 2). This wrapper
 * opens a fresh conversation per call and closes it immediately.
 *
 * The on-device smoke test that exercises the actual Gemma 4 E4B model lives in
 * `:app/src/androidTest/.../LiteRtLmTextSmokeTest.kt` (Story 1.3) and the STT-A audio harness
 * lives at `:app/src/androidTest/.../SttAAudioPlumbingTest.kt` (Story 1.5).
 */
class LiteRtLmEngine(
    private val modelPath: String,
    private val backend: BackendChoice = BackendChoice.Cpu,
    private val audioBackend: BackendChoice? = null,
    private val visionBackend: BackendChoice? = null,
    private val cacheDir: String? = null,
) : AutoCloseable {

    private var engine: Engine? = null

    suspend fun initialize() = withContext(Dispatchers.IO) {
        check(engine == null) { "LiteRtLmEngine already initialized; close() before re-init." }
        Log.d(
            TAG,
            "Loading $modelPath backend=${backend.label} " +
                "audio=${audioBackend?.label ?: "off"} vision=${visionBackend?.label ?: "off"}",
        )
        Engine.setNativeMinLogSeverity(LogSeverity.WARNING)
        val started = System.nanoTime()
        engine = Engine(
            EngineConfig(
                modelPath = modelPath,
                backend = backend.toSdkBackend(),
                audioBackend = audioBackend?.toSdkBackend(),
                visionBackend = visionBackend?.toSdkBackend(),
                cacheDir = cacheDir,
            ),
        ).also { it.initialize() }
        val elapsedMs = (System.nanoTime() - started) / NANOS_PER_MILLI
        Log.d(TAG, "Engine initialized in ${elapsedMs}ms")
    }

    suspend fun generateText(prompt: String): String = withContext(Dispatchers.IO) {
        val active = checkNotNull(engine) {
            "LiteRtLmEngine.generateText called before initialize()."
        }
        val started = System.nanoTime()
        val response = active.createConversation().use { conversation ->
            conversation.sendMessage(prompt).toString()
        }
        val elapsedMs = (System.nanoTime() - started) / NANOS_PER_MILLI
        Log.d(
            TAG,
            "generateText completed in ${elapsedMs}ms (prompt=${prompt.length}c, reply=${response.length}c)",
        )
        response
    }

    /**
     * Multimodal one-shot. Used by Story 1.5's STT-A probe to send `Content.AudioBytes` or
     * `Content.AudioFile` alongside a transcription prompt. Single-turn — opens and closes a
     * conversation per call.
     */
    suspend fun sendMessageContents(parts: List<Content>): String = withContext(Dispatchers.IO) {
        val active = checkNotNull(engine) {
            "LiteRtLmEngine.sendMessageContents called before initialize()."
        }
        require(parts.isNotEmpty()) { "sendMessageContents requires at least one Content part." }
        val started = System.nanoTime()

        @Suppress("SpreadOperator") // Contents.of is a vararg factory; no List-accepting overload.
        val response = active.createConversation().use { conversation ->
            conversation.sendMessage(Contents.of(*parts.toTypedArray())).toString()
        }
        val elapsedMs = (System.nanoTime() - started) / NANOS_PER_MILLI
        Log.d(
            TAG,
            "sendMessageContents completed in ${elapsedMs}ms (parts=${parts.size}, reply=${response.length}c)",
        )
        response
    }

    override fun close() {
        engine?.close()
        engine = null
    }

    private fun BackendChoice.toSdkBackend(): Backend = when (this) {
        BackendChoice.Cpu -> Backend.CPU()
        BackendChoice.Gpu -> Backend.GPU()
        is BackendChoice.Npu -> Backend.NPU(nativeLibraryDir = nativeLibraryDir)
    }

    private val BackendChoice.label: String
        get() = when (this) {
            BackendChoice.Cpu -> "CPU"
            BackendChoice.Gpu -> "GPU"
            is BackendChoice.Npu -> "NPU"
        }

    companion object {
        private const val TAG = "VestigeLiteRtLm"
        private const val NANOS_PER_MILLI = 1_000_000L
    }
}
