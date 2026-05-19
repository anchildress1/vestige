package dev.anchildress1.vestige.inference

import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * Pre-state and lifecycle checks for [SttAProbe]. The pos-path that actually transcribes audio
 * through Gemma 4 lives in `:app/src/androidTest/.../SttAAudioPlumbingTest.kt`.
 */
class SttAProbeTest {

    // --- success paths (engine mocked so SDK is not invoked) ---

    @Test
    fun `transcribeAudioBytesAsWav encodes samples and returns engine result`(@TempDir dir: File) = runTest {
        val engine = mockk<LiteRtLmEngine>()
        coEvery { engine.sendMessageContents(any(), any()) } returns "hello world"
        val result = SttAProbe(engine).transcribeAudioBytesAsWav(floatArrayOf(0.5f, -0.5f), 16_000, dir)
        assertEquals("hello world", result)
    }

    @Test
    fun `transcribeAudioBytesAsWav uses default prompt when none supplied`(@TempDir dir: File) = runTest {
        val engine = mockk<LiteRtLmEngine>()
        coEvery { engine.sendMessageContents(any(), any()) } returns "text"
        SttAProbe(engine).transcribeAudioBytesAsWav(floatArrayOf(0.1f), 16_000, dir)
    }

    @Test
    fun `transcribeAudioFile delegates path to engine and returns result`() = runTest {
        val engine = mockk<LiteRtLmEngine>()
        coEvery { engine.sendMessageContents(any(), any()) } returns "file transcript"
        val result = SttAProbe(engine).transcribeAudioFile("/valid/path.wav")
        assertEquals("file transcript", result)
    }

    @Test
    fun `transcribeViaTempWav writes wav calls engine and deletes temp file`(@TempDir dir: File) = runTest {
        val engine = mockk<LiteRtLmEngine>()
        coEvery { engine.sendMessageContents(any(), any()) } returns "wav transcript"
        val result = SttAProbe(engine).transcribeViaTempWav(floatArrayOf(0.1f, 0.2f), 16_000, dir)
        assertEquals("wav transcript", result)
    }

    @Test
    fun `DEFAULT_PROMPT instructs the model to respond with only spoken text`() {
        assertEquals(
            "Transcribe the audio. Respond with only the spoken text.",
            SttAProbe.DEFAULT_PROMPT,
        )
    }

    @Test
    fun `transcribeAudioBytesAsWav rejects empty samples`(@TempDir dir: File) {
        val probe = SttAProbe(LiteRtLmEngine(modelPath = NOT_USED_PATH))
        assertThrows(IllegalArgumentException::class.java) {
            runTest { probe.transcribeAudioBytesAsWav(FloatArray(0), 16_000, dir) }
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

    @Test
    fun `transcribeAudioBytesAsWav rejects missing cache dir`(@TempDir dir: File) {
        val probe = SttAProbe(LiteRtLmEngine(modelPath = NOT_USED_PATH))
        val nonExistent = File(dir, "does-not-exist")
        assertThrows(IllegalArgumentException::class.java) {
            runTest { probe.transcribeAudioBytesAsWav(floatArrayOf(0.1f), 16_000, nonExistent) }
        }
    }

    private companion object {
        const val NOT_USED_PATH = "/tmp/never-loaded.litertlm"
    }
}
