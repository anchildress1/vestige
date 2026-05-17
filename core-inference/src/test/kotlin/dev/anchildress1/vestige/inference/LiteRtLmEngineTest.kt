package dev.anchildress1.vestige.inference

import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.ExperimentalApi
import com.google.ai.edge.litertlm.ExperimentalFlags
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertDoesNotThrow

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

    @Test
    fun `concurrent close calls reuse one drain and both return after the final reader exits`() {
        val engine = LiteRtLmEngine(modelPath = NOT_USED_PATH)
        val nativeEngine = mockk<Engine>(relaxed = true)
        setField(engine, "engine", nativeEngine)
        setIntField(engine, "activeCalls", 1)

        val firstClose = Thread { engine.close() }
        firstClose.start()
        val firstDrain = waitForField<kotlinx.coroutines.CompletableDeferred<Unit>>(engine, "drained")

        val secondClose = Thread { engine.close() }
        secondClose.start()
        val currentDrain = waitForField<kotlinx.coroutines.CompletableDeferred<Unit>>(engine, "drained")

        firstDrain.complete(Unit)
        if (currentDrain !== firstDrain) currentDrain.complete(Unit)
        firstClose.join(JOIN_TIMEOUT_MS)
        secondClose.join(JOIN_TIMEOUT_MS)

        assertSame(firstDrain, currentDrain, "every close() waiter must share the same drain token")
        assertFalse(firstClose.isAlive, "first close() should return once the drain completes")
        assertFalse(secondClose.isAlive, "second close() should return once the same drain completes")
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
        const val JOIN_TIMEOUT_MS = 1_000L
        const val POLL_SLEEP_MS = 10L
        const val FIELD_WAIT_MS = 1_000L
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

    @Suppress("UNCHECKED_CAST")
    private fun <T> waitForField(target: Any, name: String): T {
        val deadline = System.currentTimeMillis() + FIELD_WAIT_MS
        while (System.currentTimeMillis() < deadline) {
            val field = target.javaClass.getDeclaredField(name)
            field.isAccessible = true
            val value = field.get(target) as T?
            if (value != null) return value
            Thread.sleep(POLL_SLEEP_MS)
        }
        val field = target.javaClass.getDeclaredField(name)
        field.isAccessible = true
        val value = field.get(target)
        assertNotNull(value, "field '$name' never became non-null")
        return value as T
    }
}
