package dev.anchildress1.vestige.inference

import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * Pre-state and lifecycle checks for [SttAProbe]. The pos-path that actually transcribes audio
 * through Gemma 4 lives in `:app/src/androidTest/.../SttAAudioPlumbingTest.kt`.
 */
class SttAProbeTest {

    @Test
    fun `transcribeAudioBytesAsFloat32Le rejects empty samples`() {
        val probe = SttAProbe(LiteRtLmEngine(modelPath = NOT_USED_PATH))
        assertThrows(IllegalArgumentException::class.java) {
            runTest { probe.transcribeAudioBytesAsFloat32Le(FloatArray(0)) }
        }
    }

    @Test
    fun `transcribeAudioFile rejects blank path`() {
        val probe = SttAProbe(LiteRtLmEngine(modelPath = NOT_USED_PATH))
        assertThrows(IllegalArgumentException::class.java) {
            runTest { probe.transcribeAudioFile("") }
        }
        assertThrows(IllegalArgumentException::class.java) {
            runTest { probe.transcribeAudioFile("   ") }
        }
    }

    @Test
    fun `transcribeViaTempWav rejects empty samples`(@TempDir dir: File) {
        val probe = SttAProbe(LiteRtLmEngine(modelPath = NOT_USED_PATH))
        assertThrows(IllegalArgumentException::class.java) {
            runTest { probe.transcribeViaTempWav(FloatArray(0), 16_000, dir) }
        }
    }

    @Test
    fun `transcribeViaTempWav rejects missing cache dir`(@TempDir dir: File) {
        val probe = SttAProbe(LiteRtLmEngine(modelPath = NOT_USED_PATH))
        val nonExistent = File(dir, "does-not-exist")
        assertThrows(IllegalArgumentException::class.java) {
            runTest { probe.transcribeViaTempWav(floatArrayOf(0.1f), 16_000, nonExistent) }
        }
    }

    private companion object {
        const val NOT_USED_PATH = "/tmp/never-loaded.litertlm"
    }
}
