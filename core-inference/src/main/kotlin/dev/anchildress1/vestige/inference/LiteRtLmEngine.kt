package dev.anchildress1.vestige.inference

import android.util.Log
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.ExperimentalApi
import com.google.ai.edge.litertlm.ExperimentalFlags
import com.google.ai.edge.litertlm.LogSeverity
import com.google.ai.edge.litertlm.SamplerConfig
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Backend selection. NPU needs the host's `nativeLibraryDir` — only Android `Context` has it. */
sealed interface BackendChoice {
    object Cpu : BackendChoice
    object Gpu : BackendChoice
    data class Npu(val nativeLibraryDir: String) : BackendChoice
}

/**
 * Lifecycle wrapper around LiteRT-LM's [Engine]. [initialize] once, then [generateText] /
 * [sendMessageContents] per call. Each call opens its own independent conversation and closes
 * it when done; calls run concurrently on the shared engine. [close] flips a closing gate and
 * waits for in-flight calls to drain before freeing the native handle.
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

    @Volatile
    private var engine: Engine? = null

    // Concurrency model (ADR-008 §Correction / ADR-001:424): independent Conversation contexts
    // run in parallel on the shared Engine. [stateMutex] is held only microscopically — to read
    // the engine pointer and adjust the in-flight count — never across an inference. [close]
    // flips [closing], then drains in-flight calls before freeing the native handle.
    private val stateMutex = Mutex()

    @Volatile
    private var closing = false
    private var inFlight = 0
    private var drainGate: CompletableDeferred<Unit>? = null

    private suspend fun acquireEngine(unavailableMessage: String): Engine = stateMutex.withLock {
        check(!closing) { unavailableMessage }
        val active = checkNotNull(engine) { unavailableMessage }
        inFlight++
        active
    }

    private suspend fun releaseEngine() = stateMutex.withLock {
        if (--inFlight == 0) drainGate?.complete(Unit)
    }

    @OptIn(ExperimentalApi::class)
    suspend fun initialize() = withContext(ioDispatcher) {
        check(engine == null) { "LiteRtLmEngine already initialized; close() before re-init." }
        // Process-global SDK flag — must be set before any Engine is constructed.
        ExperimentalFlags.enableSpeculativeDecoding = true
        Log.d(
            TAG,
            "Loading $modelPath backend=${backend.label} " +
                "audio=${audioBackend?.label ?: "off"} vision=${visionBackend?.label ?: "off"} " +
                "maxTokens=$maxNumTokens speculativeDecoding=on sampler=topK=${samplerConfig.topK}," +
                "topP=${samplerConfig.topP},temp=${samplerConfig.temperature},seed=${samplerConfig.seed}",
        )
        Engine.setNativeMinLogSeverity(LogSeverity.INFO)
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
     * Pinned `ConversationConfig` for every `createConversation()` — [systemInstruction] is the
     * SDK's instruction channel (the prompt's role/schema/context, no longer stuffed into the
     * message body), no initial history, no tools, and the engine's deterministic sampler.
     * Without the pinned sampler the SDK defaults pick a stochastic path that produces different
     * output across CPU vs GPU on the same prompt.
     */
    private fun conversationConfig(systemInstruction: String): ConversationConfig = ConversationConfig(
        Contents.of(systemInstruction),
        emptyList(),
        emptyList(),
        samplerConfig,
    )

    suspend fun generateText(systemInstruction: String, prompt: String): String = withContext(ioDispatcher) {
        val started = System.nanoTime()
        val active = acquireEngine(
            "LiteRtLmEngine.generateText called before initialize() (or after close()).",
        )
        val response = try {
            active.createConversation(conversationConfig(systemInstruction)).use { conversation ->
                conversation.sendMessage(prompt).toString()
            }
        } finally {
            releaseEngine()
        }
        val elapsedMs = (System.nanoTime() - started) / NANOS_PER_MILLI
        Log.d(
            TAG,
            "generateText completed in ${elapsedMs}ms (prompt=${prompt.length}c, reply=${response.length}c)",
        )
        response
    }

    /** Streaming counterpart to [generateText]. Closes the conversation on flow completion. */
    fun streamText(systemInstruction: String, prompt: String): Flow<String> = flow {
        val active = acquireEngine(
            "LiteRtLmEngine.streamText called before initialize() (or after close()).",
        )
        try {
            val conversation = active.createConversation(conversationConfig(systemInstruction))
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
                runCatching { conversation.close() }
                    .onFailure { Log.w(TAG, "conversation.close() after streamText failed: ${it.message}") }
            }
        } finally {
            releaseEngine()
        }
    }.flowOn(ioDispatcher)

    /**
     * Streaming counterpart to [sendMessageContents] for the multimodal `AudioFile + Text`
     * foreground path. One conversation per call, closed on flow completion or cancellation.
     */
    fun streamMessageContents(systemInstruction: String, parts: List<Content>): Flow<String> = flow {
        require(parts.isNotEmpty()) { "streamMessageContents requires at least one Content part." }

        val contents = Contents.of(parts)
        val active = acquireEngine(
            "LiteRtLmEngine.streamMessageContents called before initialize() (or after close()).",
        )
        try {
            val conversation = active.createConversation(conversationConfig(systemInstruction))
            val started = System.nanoTime()
            var charsEmitted = 0
            try {
                conversation.sendMessageAsync(contents).collect { message ->
                    val chunk = message.toString()
                    charsEmitted += chunk.length
                    emit(chunk)
                }
                val elapsedMs = (System.nanoTime() - started) / NANOS_PER_MILLI
                Log.d(
                    TAG,
                    "streamMessageContents completed in ${elapsedMs}ms (parts=${parts.size}, " +
                        "emitted=${charsEmitted}c)",
                )
            } catch (@Suppress("TooGenericExceptionCaught") error: Throwable) {
                // Log-and-rethrow, same rationale as streamText: native SDK + coroutine
                // cancellation share no exception hierarchy worth enumerating.
                val elapsedMs = (System.nanoTime() - started) / NANOS_PER_MILLI
                Log.d(
                    TAG,
                    "streamMessageContents failed (${error.javaClass.simpleName}) in ${elapsedMs}ms " +
                        "(parts=${parts.size}, emitted=${charsEmitted}c)",
                )
                throw error
            } finally {
                runCatching { conversation.close() }
                    .onFailure {
                        Log.w(TAG, "conversation.close() after streamMessageContents failed: ${it.message}")
                    }
            }
        } finally {
            releaseEngine()
        }
    }.flowOn(ioDispatcher)

    /**
     * Multimodal one-shot for `Content.AudioBytes` / `Content.AudioFile` alongside a text prompt.
     * Opens and closes a conversation per call.
     */
    suspend fun sendMessageContents(systemInstruction: String, parts: List<Content>): String =
        withContext(ioDispatcher) {
            require(parts.isNotEmpty()) { "sendMessageContents requires at least one Content part." }
            val started = System.nanoTime()
            val active = acquireEngine(
                "LiteRtLmEngine.sendMessageContents called before initialize() (or after close()).",
            )

            val response = try {
                active.createConversation(conversationConfig(systemInstruction)).use { conversation ->
                    conversation.sendMessage(Contents.of(parts)).toString()
                }
            } finally {
                releaseEngine()
            }
            val elapsedMs = (System.nanoTime() - started) / NANOS_PER_MILLI
            Log.d(
                TAG,
                "sendMessageContents completed in ${elapsedMs}ms (parts=${parts.size}, reply=${response.length}c)",
            )
            response
        }

    override fun close() {
        // Flip `closing` (new calls fail their acquireEngine check), then wait for in-flight
        // calls to drain before freeing the native handle — so a concurrent caller can never
        // dereference a closed engine. Calls themselves run unlocked; only teardown is
        // exclusive. runBlocking: AutoCloseable.close() is non-suspend.
        runBlocking {
            val gate = stateMutex.withLock {
                closing = true
                if (inFlight == 0) {
                    engine?.close()
                    engine = null
                    null
                } else {
                    CompletableDeferred<Unit>().also { drainGate = it }
                }
            }
            if (gate != null) {
                gate.await()
                stateMutex.withLock {
                    engine?.close()
                    engine = null
                }
            }
        }
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
         * identical output for the same prompt) is the contract this constant defends.
         */
        val DETERMINISTIC_SAMPLER: SamplerConfig = SamplerConfig(
            topK = 1,
            topP = 1.0,
            temperature = 0.0,
            seed = 42,
        )
    }
}
