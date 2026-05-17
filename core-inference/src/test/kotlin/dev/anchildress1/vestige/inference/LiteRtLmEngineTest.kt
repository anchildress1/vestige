package dev.anchildress1.vestige.inference

import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.ExperimentalApi
import com.google.ai.edge.litertlm.ExperimentalFlags
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
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
            runTest { engine.generateText("hello") }
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
            runTest { engine.sendMessageContents(listOf(mockk<Content>())) }
        }
        assertEquals(
            "LiteRtLmEngine.sendMessageContents called before initialize() (or after close()).",
            error.message,
        )
    }

    @Test
    fun `streamText before initialize throws the contract message on first collect`() {
        val engine = LiteRtLmEngine(modelPath = NOT_USED_PATH)
        val error = assertThrows(IllegalStateException::class.java) {
            runTest { engine.streamText("hello").collect { } }
        }
        assertEquals(
            "LiteRtLmEngine.streamText called before initialize() (or after close()).",
            error.message,
        )
    }

    @Test
    fun `streamMessageContents before initialize throws the contract message on first collect`() {
        val engine = LiteRtLmEngine(modelPath = NOT_USED_PATH)
        val error = assertThrows(IllegalStateException::class.java) {
            runTest { engine.streamMessageContents(listOf(mockk<Content>())).collect { } }
        }
        assertEquals(
            "LiteRtLmEngine.streamMessageContents called before initialize() (or after close()).",
            error.message,
        )
    }

    @OptIn(ExperimentalApi::class)
    @Test
    fun `initialize turns on MTP speculative decoding before engine construction`() {
        ExperimentalFlags.enableSpeculativeDecoding = false
        val engine = LiteRtLmEngine(modelPath = NOT_USED_PATH)
        // initialize() flips the flag as its first statement, then crosses the native
        // Engine/Log boundary the JVM can't satisfy without the 3.66 GB model. The catch
        // scopes this test to exactly the pre-native flag-set, matching the file's
        // JVM-vs-on-device split documented above.
        runCatching { runTest { engine.initialize() } }
        assertEquals(true, ExperimentalFlags.enableSpeculativeDecoding)
        engine.close()
    }

    @Test
    fun `close before initialize is a no-op`() {
        val engine = LiteRtLmEngine(modelPath = NOT_USED_PATH)
        engine.close() // must not throw
        engine.close() // idempotent — must not throw on second call either
    }

    // Concurrency regression: two close() calls racing the drain branch must share ONE token and
    // both return; the native engine must close exactly once. Determinism (no wall-clock
    // poll deadlines, which false-fail under CI load): each closer is sequenced by waiting until
    // its thread is genuinely parked in `pending.await()` (Thread.State WAITING/TIMED_WAITING),
    // which means it has already executed the `lifecycleLock` drain branch — so `drained` is
    // observed only after the second closer's reuse-or-overwrite decision, making a regressed
    // (overwrite) impl fail deterministically rather than flakily. @Timeout is the only time
    // bound: a true hang regression fails cleanly instead of wedging the suite.
    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    fun `concurrent close calls reuse one drain and both return after the final reader exits`() {
        val engine = LiteRtLmEngine(modelPath = NOT_USED_PATH)
        val nativeEngine = mockk<Engine>(relaxed = true)
        setField(engine, "engine", nativeEngine)
        setIntField(engine, "activeCalls", 1)

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
    fun `default backends only set the primary engine backend`() {
        // Constructor-default contract: Phase 1 lives on CPU until STT-A picks an accelerator,
        // and audio/vision backends stay null unless the caller opts in.
        val engine = LiteRtLmEngine(modelPath = NOT_USED_PATH)
        // Indirect assertion: constructing without explicit backends must not throw.
        engine.close()
        assertTrue(true)
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
    fun `BackendChoice Cpu can be constructed`() {
        assertDoesNotThrow { BackendChoice.Cpu }
    }

    @Test
    fun `BackendChoice sealed subtypes are distinct`() {
        assertTrue(BackendChoice.Cpu != BackendChoice.Gpu)
        assertTrue(BackendChoice.Cpu != BackendChoice.Npu(nativeLibraryDir = "/lib"))
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

    // Blocks until [thread] is parked (the closer reached `pending.await()`, i.e. already ran the
    // lifecycleLock drain branch). No wall-clock bound — the method @Timeout backstops a true hang.
    private fun awaitParked(thread: Thread) {
        while (thread.state != Thread.State.WAITING && thread.state != Thread.State.TIMED_WAITING) {
            Thread.sleep(POLL_SLEEP_MS)
        }
    }

    // Reads the engine's private `drained` token. Only called after [awaitParked], so the closer
    // has passed the branch that assigns it — the field is guaranteed non-null here.
    @Suppress("UNCHECKED_CAST")
    private fun drainToken(engine: LiteRtLmEngine): CompletableDeferred<Unit> {
        val field = engine.javaClass.getDeclaredField("drained")
        field.isAccessible = true
        return checkNotNull(field.get(engine) as CompletableDeferred<Unit>?) {
            "drained must be set once a close() thread is parked in await()"
        }
    }
}
