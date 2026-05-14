package dev.anchildress1.vestige.inference

import android.util.Log
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.LogSeverity
import com.google.ai.edge.litertlm.SamplerConfig
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
@Suppress("LongParameterList") // Mirrors the SDK's EngineConfig + ConversationConfig surfaces.
class LiteRtLmEngine(
    private val modelPath: String,
    private val backend: BackendChoice = BackendChoice.Cpu,
    private val audioBackend: BackendChoice? = null,
    private val visionBackend: BackendChoice? = null,
    private val cacheDir: String? = null,
    private val samplerConfig: SamplerConfig = DETERMINISTIC_SAMPLER,
    private val maxNumTokens: Int? = DEFAULT_MAX_NUM_TOKENS,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : AutoCloseable {

    private var engine: Engine? = null
    private val callMutex = Mutex()

    suspend fun initialize() = withContext(ioDispatcher) {
        check(engine == null) { "LiteRtLmEngine already initialized; close() before re-init." }
        Log.d(
            TAG,
            "Loading $modelPath backend=${backend.label} " +
                "audio=${audioBackend?.label ?: "off"} vision=${visionBackend?.label ?: "off"} " +
                "maxTokens=$maxNumTokens sampler=topK=${samplerConfig.topK}," +
                "topP=${samplerConfig.topP},temp=${samplerConfig.temperature},seed=${samplerConfig.seed}",
        )
        Engine.setNativeMinLogSeverity(LogSeverity.WARNING)
        val started = System.nanoTime()
        engine = Engine(
            EngineConfig(
                modelPath = modelPath,
                backend = backend.toSdkBackend(),
                visionBackend = visionBackend?.toSdkBackend(),
                audioBackend = audioBackend?.toSdkBackend(),
                maxNumTokens = maxNumTokens,
                maxNumImages = null,
                cacheDir = cacheDir,
            ),
        ).also { it.initialize() }
        val elapsedMs = (System.nanoTime() - started) / NANOS_PER_MILLI
        Log.d(TAG, "Engine initialized in ${elapsedMs}ms")
    }

    /**
     * Pinned `ConversationConfig` for every `createConversation()` — empty system instruction
     * (callers stack system text into the message body for now), no initial history, no tools,
     * and the engine's deterministic sampler. Without this the SDK falls back to non-greedy
     * defaults that produce different output across CPU vs GPU on the same prompt — measured
     * 2026-05-12 on the STT-D corpus, CPU 80% / GPU 53% divergence on identical prompts.
     */
    private fun conversationConfig(): ConversationConfig = ConversationConfig(
        Contents.of(""),
        emptyList(),
        emptyList(),
        samplerConfig,
    )

    suspend fun generateText(prompt: String): String = withContext(ioDispatcher) {
        val active = checkNotNull(engine) {
            "LiteRtLmEngine.generateText called before initialize()."
        }
        val started = System.nanoTime()
        val response = callMutex.withLock {
            active.createConversation(conversationConfig()).use { conversation ->
                conversation.sendMessage(prompt).toString()
            }
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
        return flow {
            callMutex.withLock {
                val conversation = active.createConversation(conversationConfig())
                val started = System.nanoTime()
                var charsEmitted = 0
                try {
                    conversation.sendMessageAsync(prompt).collect { message ->
                        val chunk = message.toString()
                        charsEmitted += chunk.length
                        emit(chunk)
                    }
                    val elapsedMs = (System.nanoTime() - started) / NANOS_PER_MILLI
                    Log.d(
                        TAG,
                        "streamText completed in ${elapsedMs}ms (prompt=${prompt.length}c, " +
                            "emitted=${charsEmitted}c)",
                    )
                } catch (@Suppress("TooGenericExceptionCaught") error: Throwable) {
                    // Log-and-rethrow: native SDK + coroutine cancellation share no exception
                    // hierarchy worth enumerating; partial-emission state is the only thing we own.
                    val elapsedMs = (System.nanoTime() - started) / NANOS_PER_MILLI
                    Log.d(
                        TAG,
                        "streamText failed (${error.javaClass.simpleName}) in ${elapsedMs}ms " +
                            "(prompt=${prompt.length}c, emitted=${charsEmitted}c)",
                    )
                    throw error
                } finally {
                    conversation.close()
                }
            }
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
        val response = callMutex.withLock {
            active.createConversation(conversationConfig()).use { conversation ->
                conversation.sendMessage(Contents.of(*parts.toTypedArray())).toString()
            }
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

        /**
         * Engine-side token budget. Caps KV-cache reservation per call. 4096 comfortably covers
         * the largest composed multi-lens prompt (~2500-3000 tokens prefill + ≤1024 decode);
         * leaving the SDK default uncapped reserves the model's full ~8192 context, allocating
         * roughly double the KV memory we actually use. Tunable per harness.
         */
        const val DEFAULT_MAX_NUM_TOKENS: Int = 4096

        /**
         * Pinned greedy decode. `topK=1` makes generation deterministic regardless of `seed`
         * or `temperature`, but we set the rest explicitly so a future SDK change to defaults
         * doesn't quietly bring back sampling noise. Backend parity (CPU vs GPU producing
         * identical output for the same prompt) is the contract this constant defends —
         * measured before this knob existed: CPU 80% / GPU 53% STT-D divergence on identical
         * prompts (2026-05-12), with backend-conditional parse failures on C2 + D1.
         */
        val DETERMINISTIC_SAMPLER: SamplerConfig = SamplerConfig(
            topK = 1,
            topP = 1.0,
            temperature = 0.0,
            seed = 42,
        )
    }
}
