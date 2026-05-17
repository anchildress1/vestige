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

    @Volatile
    private var engine: Engine? = null

    // Readers/writer lifecycle (ADR-008 §Correction 2026-05-16): calls are concurrent "readers"
    // — each opens its own independent SDK conversation off one Engine, so foreground and the
    // three background lenses no longer serialize on a shared Kotlin mutex. close() is the
    // exclusive "writer": it stops admitting calls and drains in-flight ones before freeing the
    // native handle. The single GPU still serializes at its command queue (no literal speedup);
    // the win is non-blocking preemption. lifecycleLock guards only the tiny ref/counter
    // critical sections — never the slow inference call itself.
    private val lifecycleLock = Mutex()
    private var activeCalls = 0
    private var closing = false
    private var drained: CompletableDeferred<Unit>? = null

    /**
     * Acquire the live [Engine] under [lifecycleLock], run [block] concurrently with other calls
     * (the lock is released for the slow inference), then release. Rejects calls before
     * `initialize()`, after `close()`, and once a `close()` drain is in progress — all with the
     * one documented contract message so existing call sites and tests stay valid.
     */
    private suspend fun <T> withEngine(caller: String, block: suspend (Engine) -> T): T {
        val active = lifecycleLock.withLock {
            val current = engine
            check(current != null && !closing) {
                "LiteRtLmEngine.$caller called before initialize() (or after close())."
            }
            activeCalls += 1
            current
        }
        try {
            return block(active)
        } finally {
            lifecycleLock.withLock {
                activeCalls -= 1
                if (activeCalls == 0) drained?.complete(Unit)
            }
        }
    }

    @OptIn(ExperimentalApi::class)
    suspend fun initialize() = withContext(ioDispatcher) {
        check(engine == null) { "LiteRtLmEngine already initialized; close() before re-init." }
        // MTP single-position speculative decoding — process-global SDK flag, must be set before
        // any Engine is constructed. Idempotent across re-init. Decode-path only: prompt, sampler,
        // and output format are unaffected, so it stays on for CPU and GPU alike.
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
     * Pinned `ConversationConfig` for every `createConversation()` — empty system instruction
     * (callers stack system text into the message body for now), no initial history, no tools,
     * and the engine's deterministic sampler. Without the pinned sampler the SDK defaults pick
     * a stochastic path that produces different output across CPU vs GPU on the same prompt.
     */
    private fun conversationConfig(): ConversationConfig = ConversationConfig(
        Contents.of(""),
        emptyList(),
        emptyList(),
        samplerConfig,
    )

    suspend fun generateText(prompt: String): String = withContext(ioDispatcher) {
        val started = System.nanoTime()
        val response = withEngine("generateText") { active ->
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
    fun streamText(prompt: String): Flow<String> = flow {
        withEngine("streamText") { active ->
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
                runCatching { conversation.close() }
                    .onFailure { Log.w(TAG, "conversation.close() after streamText failed: ${it.message}") }
            }
        }
    }.flowOn(ioDispatcher)

    /**
     * Streaming counterpart to [sendMessageContents] — the multimodal `AudioFile + Text`
     * foreground path. Mirrors [streamText]: one conversation per call, closed on flow
     * completion or cancellation. Each emitted chunk is one SDK [com.google.ai.edge.litertlm.Message]
     * rendered to text.
     */
    fun streamMessageContents(parts: List<Content>): Flow<String> = flow {
        require(parts.isNotEmpty()) { "streamMessageContents requires at least one Content part." }

        @Suppress("SpreadOperator") // Contents.of is a vararg factory; no List-accepting overload.
        val contents = Contents.of(*parts.toTypedArray())
        withEngine("streamMessageContents") { active ->
            val conversation = active.createConversation(conversationConfig())
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
        }
    }.flowOn(ioDispatcher)

    /**
     * Multimodal one-shot for `Content.AudioBytes` / `Content.AudioFile` alongside a text prompt.
     * Opens and closes a conversation per call.
     */
    suspend fun sendMessageContents(parts: List<Content>): String = withContext(ioDispatcher) {
        require(parts.isNotEmpty()) { "sendMessageContents requires at least one Content part." }
        val started = System.nanoTime()

        @Suppress("SpreadOperator") // Contents.of is a vararg factory; no List-accepting overload.
        val response = withEngine("sendMessageContents") { active ->
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
        // Writer side of the readers/writer lifecycle: stop admitting calls, drain any in-flight
        // ones, then free the native handle. Without the drain a concurrent call could deref a
        // freed engine through `active.createConversation(...)`. Idempotent — a second close()
        // sees a null engine and no-ops.
        runBlocking {
            val pending: CompletableDeferred<Unit>? = lifecycleLock.withLock {
                when {
                    engine == null -> null

                    activeCalls == 0 -> {
                        engine?.close()
                        engine = null
                        null
                    }

                    else -> {
                        closing = true
                        CompletableDeferred<Unit>().also { drained = it }
                    }
                }
            }
            if (pending != null) {
                pending.await()
                lifecycleLock.withLock {
                    engine?.close()
                    engine = null
                    drained = null
                    closing = false
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
