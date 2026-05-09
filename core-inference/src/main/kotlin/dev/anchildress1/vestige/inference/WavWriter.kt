package dev.anchildress1.vestige.inference

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Writes mono PCM_S16LE WAV files for LiteRT-LM's `Content.AudioFile` path.
 *
 * STT-A (Story 1.5) established that LiteRT-LM 0.11.0's miniaudio decoder accepts PCM_S16LE
 * (format code 1) but rejects IEEE_FLOAT (format code 3) with MA_INVALID_DATA. Float32 samples
 * from [AudioCapture] are scaled to the int16 range [-32768, 32767] on write.
 *
 * Used by [SttAProbe.transcribeViaTempWav] — the file is created, handed to the model, and
 * deleted within the same call. No persisted audio.
 */
internal object WavWriter {

    private const val WAV_HEADER_BYTES = 44
    private const val FMT_CHUNK_SIZE = 16
    private const val FORMAT_PCM: Short = 1
    private const val MONO_CHANNEL_COUNT: Short = 1
    private const val BYTES_PER_SAMPLE = 2 // int16
    private const val BITS_PER_SAMPLE: Short = 16
    private const val PCM16_SCALE = 32767f

    /**
     * Write [samples] (float32, `[-1, 1]`) to [target] as mono [sampleRateHz] PCM_S16LE WAV.
     * Clips samples outside `[-1, 1]` before scaling to avoid int16 overflow.
     */
    fun writeMonoFloatWav(target: File, samples: FloatArray, sampleRateHz: Int) {
        require(samples.isNotEmpty()) { "WAV write requires at least one sample." }
        require(sampleRateHz > 0) { "sampleRateHz must be positive, was $sampleRateHz" }

        val dataSize = samples.size * BYTES_PER_SAMPLE
        val byteRate = sampleRateHz * BYTES_PER_SAMPLE
        val blockAlign: Short = BYTES_PER_SAMPLE.toShort()
        val riffSize = WAV_HEADER_BYTES - 8 + dataSize

        val header = ByteBuffer.allocate(WAV_HEADER_BYTES).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray(Charsets.US_ASCII))
            putInt(riffSize)
            put("WAVE".toByteArray(Charsets.US_ASCII))
            put("fmt ".toByteArray(Charsets.US_ASCII))
            putInt(FMT_CHUNK_SIZE)
            putShort(FORMAT_PCM)
            putShort(MONO_CHANNEL_COUNT)
            putInt(sampleRateHz)
            putInt(byteRate)
            putShort(blockAlign)
            putShort(BITS_PER_SAMPLE)
            put("data".toByteArray(Charsets.US_ASCII))
            putInt(dataSize)
        }.array()

        val sampleBytes = ByteBuffer.allocate(dataSize).order(ByteOrder.LITTLE_ENDIAN).also { buf ->
            samples.forEach { sample ->
                buf.putShort((sample.coerceIn(-1f, 1f) * PCM16_SCALE).toInt().toShort())
            }
        }.array()

        RandomAccessFile(target, "rw").use { raf ->
            raf.setLength(0)
            raf.write(header)
            raf.write(sampleBytes)
        }
    }
}
