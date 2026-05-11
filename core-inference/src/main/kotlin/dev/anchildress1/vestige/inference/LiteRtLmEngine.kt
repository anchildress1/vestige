package dev.anchildress1.vestige.inference

import android.util.Log
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.LogSeverity
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.withContext

/** Backend selection. NPU needs the host's `nativeLibraryDir` — only Android `Context` has it. */
sealed interface BackendChoice {
    data object Cpu : BackendChoice
    data object Gpu : BackendChoice
    data class Npu(val nativeLibraryDir: String) : BackendChoice
}

/**
 * Lifecycle wrapper around LiteRT-LM's [Engine]. [initialize] once, then [generateText] /
 * [sendMessageContents] per call. Each call opens and closes a fresh conversation — the SDK's
 * stateful KV-cache Conversation handle is not exposed in v1.
 */
class LiteRtLmEngine(
    private val modelPath: String,
    private val backend: BackendChoice = BackendChoice.Cpu,
    private val audioBackend: BackendChoice? = null,
    private val visionBackend: BackendChoice? = null,
    private val cacheDir: String? = null,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : AutoCloseable {

    private var engine: Engine? = null

    suspend fun initialize() = withContext(ioDispatcher) {
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

    suspend fun generateText(prompt: String): String = withContext(ioDispatcher) {
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

    /** Streaming counterpart to [generateText]. Closes the conversation on flow completion. */
    fun streamText(prompt: String): Flow<String> {
        val active = checkNotNull(engine) {
            "LiteRtLmEngine.streamText called before initialize()."
        }
        val conversation = active.createConversation()
        val started = System.nanoTime()
        var charsEmitted = 0
        return flow {
            conversation.sendMessageAsync(prompt).collect { message ->
                val chunk = message.toString()
                charsEmitted += chunk.length
                emit(chunk)
            }
        }.onCompletion { cause ->
            conversation.close()
            val elapsedMs = (System.nanoTime() - started) / NANOS_PER_MILLI
            val outcome = cause?.let { "failed (${it.javaClass.simpleName})" } ?: "completed"
            Log.d(
                TAG,
                "streamText $outcome in ${elapsedMs}ms (prompt=${prompt.length}c, " +
                    "emitted=${charsEmitted}c)",
            )
        }.flowOn(ioDispatcher)
    }

    /**
     * Multimodal one-shot for `Content.AudioBytes` / `Content.AudioFile` alongside a text prompt.
     * Opens and closes a conversation per call.
     */
    suspend fun sendMessageContents(parts: List<Content>): String = withContext(ioDispatcher) {
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
