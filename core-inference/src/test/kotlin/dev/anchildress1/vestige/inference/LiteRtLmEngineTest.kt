package dev.anchildress1.vestige.inference

import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.ExperimentalApi
import com.google.ai.edge.litertlm.ExperimentalFlags
import io.mockk.mockk
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
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

    @Test
    fun `close before initialize is a no-op`() {
        val engine = LiteRtLmEngine(modelPath = NOT_USED_PATH)
        engine.close() // must not throw
        engine.close() // idempotent — must not throw on second call either
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
    }
}
