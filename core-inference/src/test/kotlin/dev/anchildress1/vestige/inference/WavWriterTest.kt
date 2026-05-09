package dev.anchildress1.vestige.inference

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

// STT-A (Story 1.5) established PCM_S16LE as the required format for LiteRT-LM's miniaudio.
class WavWriterTest {

    @Test
    fun `writes RIFF WAVE header with PCM_S16LE format and mono channel`(@TempDir dir: File) {
        val target = File(dir, "out.wav")
        val samples = floatArrayOf(0.0f, 0.5f, -0.5f, 1.0f, -1.0f)
        WavWriter.writeMonoFloatWav(target, samples, sampleRateHz = 16_000)

        val header = target.readBytes().copyOfRange(0, 44)
        assertEquals("RIFF", header.copyOfRange(0, 4).toString(Charsets.US_ASCII))
        assertEquals("WAVE", header.copyOfRange(8, 12).toString(Charsets.US_ASCII))
        assertEquals("fmt ", header.copyOfRange(12, 16).toString(Charsets.US_ASCII))
        assertEquals("data", header.copyOfRange(36, 40).toString(Charsets.US_ASCII))

        val buf = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(target.length().toInt() - 8, buf.getInt(4))  // riffSize
        assertEquals(16, buf.getInt(16))                           // fmt chunk size
        assertEquals(1.toShort(), buf.getShort(20))                // audioFormat = 1 (PCM)
        assertEquals(1.toShort(), buf.getShort(22))                // numChannels = 1
        assertEquals(16_000, buf.getInt(24))                       // sampleRate
        assertEquals(32_000, buf.getInt(28))                       // byteRate = 16000 * 2
        assertEquals(2.toShort(), buf.getShort(32))                // blockAlign = 2
        assertEquals(16.toShort(), buf.getShort(34))               // bitsPerSample = 16
        assertEquals(samples.size * 2, buf.getInt(40))             // dataSize = samples * 2
    }

    @Test
    fun `float samples are scaled and clipped to int16 range`(@TempDir dir: File) {
        val target = File(dir, "rt.wav")
        val samples = floatArrayOf(0.0f, 0.5f, -0.5f, 1.0f, -1.0f)
        WavWriter.writeMonoFloatWav(target, samples, sampleRateHz = 16_000)

        val payload = target.readBytes().copyOfRange(44, target.length().toInt())
        val buf = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN)
        val readback = ShortArray(samples.size) { buf.short }

        assertEquals(0, readback[0])
        // 0.5 * 32767 ≈ 16383
        assertEquals((0.5f * 32767f).toInt().toShort(), readback[1])
        // -0.5 * 32767 ≈ -16383
        assertEquals((-0.5f * 32767f).toInt().toShort(), readback[2])
        // 1.0 clips to 32767
        assertEquals(32767.toShort(), readback[3])
        // -1.0 clips to -32767
        assertEquals((-32767).toShort(), readback[4])
    }

    @Test
    fun `samples outside -1 to 1 range are clipped before scaling`(@TempDir dir: File) {
        val target = File(dir, "clip.wav")
        WavWriter.writeMonoFloatWav(target, floatArrayOf(2.0f, -2.0f), sampleRateHz = 16_000)

        val payload = target.readBytes().copyOfRange(44, target.length().toInt())
        val buf = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN)
        // Both should be clamped to ±32767, not overflow
        assertEquals(32767.toShort(), buf.short)
        assertEquals((-32767).toShort(), buf.short)
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
        // 44-byte header + 2-byte int16 sample
        assertEquals(46L, target.length())
    }
}
