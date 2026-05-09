package dev.anchildress1.vestige.inference

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

class WavWriterTest {

    @Test
    fun `writes RIFF WAVE header with IEEE_FLOAT format and mono channel`(@TempDir dir: File) {
        val target = File(dir, "out.wav")
        val samples = floatArrayOf(0.0f, 0.5f, -0.5f, 1.0f, -1.0f)
        WavWriter.writeMonoFloatWav(target, samples, sampleRateHz = 16_000)

        val header = target.readBytes().copyOfRange(0, 44)
        assertEquals("RIFF", header.copyOfRange(0, 4).toString(Charsets.US_ASCII))
        assertEquals("WAVE", header.copyOfRange(8, 12).toString(Charsets.US_ASCII))
        assertEquals("fmt ", header.copyOfRange(12, 16).toString(Charsets.US_ASCII))
        assertEquals("data", header.copyOfRange(36, 40).toString(Charsets.US_ASCII))

        val buf = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
        // riffSize (offset 4) = total file size - 8
        assertEquals(target.length().toInt() - 8, buf.getInt(4))
        // fmt chunk size = 16
        assertEquals(16, buf.getInt(16))
        // audioFormat = 3 (IEEE_FLOAT)
        assertEquals(3.toShort(), buf.getShort(20))
        // numChannels = 1 (mono)
        assertEquals(1.toShort(), buf.getShort(22))
        // sampleRate = 16000
        assertEquals(16_000, buf.getInt(24))
        // byteRate = sampleRate * 4
        assertEquals(64_000, buf.getInt(28))
        // blockAlign = 4
        assertEquals(4.toShort(), buf.getShort(32))
        // bitsPerSample = 32
        assertEquals(32.toShort(), buf.getShort(34))
        // dataSize = samples * 4
        assertEquals(samples.size * 4, buf.getInt(40))
    }

    @Test
    fun `samples round-trip as little-endian float32 in payload`(@TempDir dir: File) {
        val target = File(dir, "rt.wav")
        val original = floatArrayOf(0.1f, -0.2f, 0.999f, -0.999f)
        WavWriter.writeMonoFloatWav(target, original, sampleRateHz = 16_000)

        val payload = target.readBytes().copyOfRange(44, target.length().toInt())
        val readback = FloatArray(original.size)
        ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().get(readback)
        assertArrayEquals(original, readback)
    }

    @Test
    fun `empty samples are rejected`(@TempDir dir: File) {
        assertThrows(IllegalArgumentException::class.java) {
            WavWriter.writeMonoFloatWav(File(dir, "x.wav"), FloatArray(0), 16_000)
        }
    }

    @Test
    fun `non-positive sample rate is rejected`(@TempDir dir: File) {
        assertThrows(IllegalArgumentException::class.java) {
            WavWriter.writeMonoFloatWav(File(dir, "x.wav"), floatArrayOf(0.1f), 0)
        }
        assertThrows(IllegalArgumentException::class.java) {
            WavWriter.writeMonoFloatWav(File(dir, "x.wav"), floatArrayOf(0.1f), -1)
        }
    }

    @Test
    fun `existing file is overwritten`(@TempDir dir: File) {
        val target = File(dir, "ow.wav")
        target.writeBytes(ByteArray(1024) { 0xFF.toByte() })
        WavWriter.writeMonoFloatWav(target, floatArrayOf(0.5f), 16_000)
        // 44-byte header + 4-byte sample
        assertEquals(48L, target.length())
    }
}
