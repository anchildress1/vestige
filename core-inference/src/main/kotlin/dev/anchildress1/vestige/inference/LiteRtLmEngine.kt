package dev.anchildress1.vestige.inference

import android.util.Log
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.LogSeverity
import kotlinx.coroutines.flow.fold

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
 * the model artifact is verified, then [generateText] for each prompt. The native engine is
 * released on [close]. Single-turn only — multi-turn conversation state is owned by
 * `SessionState` in `:app` (Phase 2).
 *
 * The on-device smoke test that exercises the actual Gemma 4 E4B model lives in
 * `:app/src/androidTest/.../LiteRtLmTextSmokeTest.kt` per Story 1.3.
 */
class LiteRtLmEngine(
    private val modelPath: String,
    private val backend: BackendChoice = BackendChoice.Cpu,
    private val cacheDir: String? = null,
) : AutoCloseable {

    private var engine: Engine? = null

    suspend fun initialize() {
        check(engine == null) { "LiteRtLmEngine already initialized; close() before re-init." }
        Log.d(TAG, "Loading model from $modelPath on backend=${backend.label}")
        val started = System.nanoTime()
        engine = Engine(
            EngineConfig(
                modelPath = modelPath,
                backend = backend.toSdkBackend(),
                cacheDir = cacheDir,
            ),
        ).also { it.initialize() }
        val elapsedMs = (System.nanoTime() - started) / NANOS_PER_MILLI
        Log.d(TAG, "Engine initialized in ${elapsedMs}ms")
    }

    suspend fun generateText(prompt: String): String {
        val active = checkNotNull(engine) {
            "LiteRtLmEngine.generateText called before initialize()."
        }
        val started = System.nanoTime()
        val response = active.createConversation().use { conversation ->
            conversation.sendMessageAsync(prompt)
                .fold(StringBuilder()) { acc, chunk -> acc.append(chunk) }
                .toString()
        }
        val elapsedMs = (System.nanoTime() - started) / NANOS_PER_MILLI
        Log.d(
            TAG,
            "generateText completed in ${elapsedMs}ms (prompt=${prompt.length}c, reply=${response.length}c)",
        )
        return response
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

        /** Quiet the LiteRT-LM native logs. Useful for tests. */
        fun setNativeLogSeverity(severity: LogSeverity) {
            Engine.setNativeMinLogSeverity(severity)
        }
    }
}
