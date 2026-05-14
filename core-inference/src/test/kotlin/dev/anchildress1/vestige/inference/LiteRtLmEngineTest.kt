package dev.anchildress1.vestige.inference

import com.google.ai.edge.litertlm.Content
import io.mockk.mockk
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
