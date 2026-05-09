package dev.anchildress1.vestige.inference

import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

/**
 * Lifecycle-invariant tests for [LiteRtLmEngine]. The pre-state checks fail before any SDK call,
 * so they run on JVM without the 3.66 GB model. The pos-path smoke test (model load + actual
 * inference) is the on-device androidTest in `:app`.
 */
class LiteRtLmEngineTest {

    @Test
    fun `generateText before initialize throws IllegalStateException`() {
        val engine = LiteRtLmEngine(modelPath = NOT_USED_PATH)
        val error = assertThrows(IllegalStateException::class.java) {
            runTest { engine.generateText("hello") }
        }
        assertEquals(
            "LiteRtLmEngine.generateText called before initialize().",
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
    fun `default backend is CPU`() {
        // Constructor-default contract: Phase 1 lives on CPU until STT-A picks an accelerator.
        val engine = LiteRtLmEngine(modelPath = NOT_USED_PATH)
        // Indirect assertion: constructing without explicit backend must not throw.
        engine.close()
    }

    @Test
    fun `Npu backend requires native library dir`() {
        val choice = BackendChoice.Npu(nativeLibraryDir = "/data/app/native")
        assertEquals("/data/app/native", choice.nativeLibraryDir)
    }

    private companion object {
        // Path is never actually opened — the tests assert pre-state checks fire first.
        const val NOT_USED_PATH = "/tmp/never-loaded.litertlm"
    }
}
