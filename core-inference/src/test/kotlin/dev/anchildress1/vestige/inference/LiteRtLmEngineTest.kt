package dev.anchildress1.vestige.inference

import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.ExperimentalApi
import com.google.ai.edge.litertlm.ExperimentalFlags
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.assertDoesNotThrow
import java.util.concurrent.TimeUnit

/**
 * Lifecycle-invariant tests for [LiteRtLmEngine]. The pre-state checks fail before any SDK call,
 * so they run on JVM without the 3.66 GB model. The pos-path smoke tests (model load + actual
 * inference, multimodal audio handoff) are the on-device androidTests in `:app`.
 */
class LiteRtLmEngineTest {

    @Test
    fun `generateText before initialize throws IllegalStateException`() {
        val engine = LiteRtLmEngine(modelPath = NOT_USED_PATH)
        val error = assertThrows(IllegalStateException::class.java) {
            runTest { engine.generateText("sys", "hello") }
        }
        assertEquals(
            "LiteRtLmEngine.generateText called before initialize() (or after close()).",
            error.message,
        )
    }

    @Test
    fun `sendMessageContents before initialize throws IllegalStateException`() {
        val engine = LiteRtLmEngine(modelPath = NOT_USED_PATH)
        val error = assertThrows(IllegalStateException::class.java) {
            runTest { engine.sendMessageContents("sys", listOf(mockk<Content>())) }
        }
        assertEquals(
            "LiteRtLmEngine.sendMessageContents called before initialize() (or after close()).",
            error.message,
        )
    }

    @Test
    fun `streamMessageContents before initialize throws on collection`() {
        val engine = LiteRtLmEngine(modelPath = NOT_USED_PATH)
        val error = assertThrows(IllegalStateException::class.java) {
            runTest { engine.streamMessageContents("sys", listOf(mockk<Content>())).toList() }
        }
        assertEquals(
            "LiteRtLmEngine.streamMessageContents called before initialize() (or after close()).",
            error.message,
        )
    }

    // Concurrency regression: two close() calls racing the drain branch must share ONE token and
    // both return; the native engine must close exactly once. Determinism (no wall-clock
    // poll deadlines, which false-fail under CI load): each closer is sequenced by waiting until
    // its thread is genuinely parked in `gate.await()` (Thread.State WAITING/TIMED_WAITING),
    // which means it has already executed the `stateMutex` drain branch — so `drainGate` is
    // observed only after the second closer's reuse-or-overwrite decision, making a regressed
    // (overwrite) impl fail deterministically rather than flakily. @Timeout is the only time
    // bound: a true hang regression fails cleanly instead of wedging the suite.
    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    fun `concurrent close calls reuse one drain and both return after the final reader exits`() {
        val engine = LiteRtLmEngine(modelPath = NOT_USED_PATH)
        val nativeEngine = mockk<Engine>(relaxed = true)
        setField(engine, "engine", nativeEngine)
        setIntField(engine, "inFlight", 1)

        val firstClose = Thread { engine.close() }
        firstClose.start()
        awaitParked(firstClose)
        val firstDrain = drainToken(engine)

        val secondClose = Thread { engine.close() }
        secondClose.start()
        awaitParked(secondClose)
        val currentDrain = drainToken(engine)

        // Asserted before completing the token: on a regressed (overwrite) impl currentDrain is a
        // different object, so this fails deterministically — not on a timing fluke.
        assertSame(firstDrain, currentDrain, "every close() waiter must share the same drain token")

        firstDrain.complete(Unit)
        if (currentDrain !== firstDrain) currentDrain.complete(Unit) // unblock a regressed impl too
        firstClose.join()
        secondClose.join()

        verify(exactly = 1) { nativeEngine.close() }
    }

    @Test
    fun `call after close is rejected by the closing gate`() {
        // Exercises the drain-on-close gate: close() flips `closing`, so a later call fails its
        // acquireEngine check rather than dereferencing a freed handle. JVM-safe — the rejection
        // fires before any native crossing. (Concurrent in-flight drain is on-device only.)
        val engine = LiteRtLmEngine(modelPath = NOT_USED_PATH)
        engine.close()
        val error = assertThrows(IllegalStateException::class.java) {
            runTest { engine.generateText("sys", "hello") }
        }
        assertEquals(
            "LiteRtLmEngine.generateText called before initialize() (or after close()).",
            error.message,
        )
    }

    @OptIn(ExperimentalApi::class)
    @Test
    fun `initialize turns on MTP speculative decoding before engine construction`() {
        // ExperimentalFlags is a process-global object — save and restore so this test
        // can't leak the flipped flag into other tests sharing the JVM fork.
        val original = ExperimentalFlags.enableSpeculativeDecoding
        try {
            ExperimentalFlags.enableSpeculativeDecoding = false
            val engine = LiteRtLmEngine(modelPath = NOT_USED_PATH)
            // initialize() flips the flag as its first statement, then crosses the native
            // Engine/Log boundary the JVM can't satisfy without the 3.66 GB model. The catch
            // scopes this test to exactly the pre-native flag-set, matching the file's
            // JVM-vs-on-device split documented above.
            runCatching { runTest { engine.initialize() } }
            assertEquals(true, ExperimentalFlags.enableSpeculativeDecoding)
            engine.close()
        } finally {
            ExperimentalFlags.enableSpeculativeDecoding = original
        }
    }

    @OptIn(ExperimentalApi::class)
    @Test
    fun `initialize disables MTP speculative decoding when audio backend is active`() {
        val original = ExperimentalFlags.enableSpeculativeDecoding
        try {
            ExperimentalFlags.enableSpeculativeDecoding = true
            val engine = LiteRtLmEngine(modelPath = NOT_USED_PATH, audioBackend = AudioBackendChoice.Cpu)
            runCatching { runTest { engine.initialize() } }
            assertEquals(false, ExperimentalFlags.enableSpeculativeDecoding)
            engine.close()
        } finally {
            ExperimentalFlags.enableSpeculativeDecoding = original
        }
    }

    @Test
    fun `close before initialize does not leave the wrapper permanently closing`() {
        val engine = LiteRtLmEngine(modelPath = NOT_USED_PATH)
        engine.close() // must not throw
        engine.close() // idempotent — must not throw on second call either

        assertEquals(false, engine.readBoolean("closing"))
        assertNull(engine.readNullable("drainGate"))
    }

    @Test
    fun `Npu backend requires native library dir`() {
        val choice = BackendChoice.Npu(nativeLibraryDir = "/data/app/native")
        assertEquals("/data/app/native", choice.nativeLibraryDir)
    }

    @Test
    fun `BackendChoice Gpu can be constructed`() {
        assertDoesNotThrow { BackendChoice.Gpu }
    }

    @Test
    fun `BackendChoice sealed subtypes are distinct`() {
        assertTrue(BackendChoice.Gpu != BackendChoice.Npu(nativeLibraryDir = "/lib"))
    }

    private companion object {
        // Path is never actually opened — the tests assert pre-state checks fire first.
        const val NOT_USED_PATH = "/tmp/never-loaded.litertlm"
        const val POLL_SLEEP_MS = 5L
    }

    private fun setField(target: Any, name: String, value: Any?) {
        val field = target.javaClass.getDeclaredField(name)
        field.isAccessible = true
        field.set(target, value)
    }

    private fun setIntField(target: Any, name: String, value: Int) {
        val field = target.javaClass.getDeclaredField(name)
        field.isAccessible = true
        field.setInt(target, value)
    }

    // Blocks until [thread] is parked (the closer reached `gate.await()`, i.e. already ran the
    // stateMutex drain branch). No wall-clock bound — the method @Timeout backstops a true hang.
    private fun awaitParked(thread: Thread) {
        while (thread.state != Thread.State.WAITING && thread.state != Thread.State.TIMED_WAITING) {
            Thread.sleep(POLL_SLEEP_MS)
        }
    }

    // Reads the engine's private `drainGate` token. Only called after [awaitParked], so the closer
    // has passed the branch that assigns it — the field is guaranteed non-null here.
    @Suppress("UNCHECKED_CAST")
    private fun drainToken(engine: LiteRtLmEngine): CompletableDeferred<Unit> {
        val field = engine.javaClass.getDeclaredField("drainGate")
        field.isAccessible = true
        return checkNotNull(field.get(engine) as CompletableDeferred<Unit>?) {
            "drainGate must be set once a close() thread is parked in await()"
        }
    }
}

private fun LiteRtLmEngine.readBoolean(name: String): Boolean {
    val field = LiteRtLmEngine::class.java.getDeclaredField(name)
    field.isAccessible = true
    return field.getBoolean(this)
}

private fun LiteRtLmEngine.readNullable(name: String): Any? {
    val field = LiteRtLmEngine::class.java.getDeclaredField(name)
    field.isAccessible = true
    return field.get(this)
}
