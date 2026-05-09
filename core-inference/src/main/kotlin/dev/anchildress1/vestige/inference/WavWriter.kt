package dev.anchildress1.vestige.inference

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Writes mono PCM_FLOAT (IEEE 754) WAV files matching Gemma's audio model intake spec
 * (mono 16 kHz float32 in `[-1, 1]`, 30s max). WAV format code `3` = `WAVE_FORMAT_IEEE_FLOAT`.
 *
 * Used by [SttAProbe] when LiteRT-LM's `Content.AudioFile` is the working handoff — the temp
 * file is created, handed to the model, and deleted within the same call. No persisted audio.
 */
internal object WavWriter {

    private const val WAV_HEADER_BYTES = 44
    private const val FMT_CHUNK_SIZE = 16
    private const val FORMAT_IEEE_FLOAT: Short = 3
    private const val MONO_CHANNEL_COUNT: Short = 1
    private const val BYTES_PER_FLOAT = 4
    private const val BITS_PER_FLOAT_SAMPLE: Short = 32

    /**
     * Write [samples] to a brand-new file at [target] as mono [sampleRateHz] PCM_FLOAT WAV.
     * Throws [IllegalArgumentException] if `samples` is empty.
     */
    fun writeMonoFloatWav(target: File, samples: FloatArray, sampleRateHz: Int) {
        require(samples.isNotEmpty()) { "WAV write requires at least one sample." }
        require(sampleRateHz > 0) { "sampleRateHz must be positive, was $sampleRateHz" }

        val dataSize = samples.size * BYTES_PER_FLOAT
        val byteRate = sampleRateHz * BYTES_PER_FLOAT
        val blockAlign: Short = BYTES_PER_FLOAT.toShort()
        val riffSize = WAV_HEADER_BYTES - 8 + dataSize

        val header = ByteBuffer.allocate(WAV_HEADER_BYTES).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray(Charsets.US_ASCII))
            putInt(riffSize)
            put("WAVE".toByteArray(Charsets.US_ASCII))
            put("fmt ".toByteArray(Charsets.US_ASCII))
            putInt(FMT_CHUNK_SIZE)
            putShort(FORMAT_IEEE_FLOAT)
            putShort(MONO_CHANNEL_COUNT)
            putInt(sampleRateHz)
            putInt(byteRate)
            putShort(blockAlign)
            putShort(BITS_PER_FLOAT_SAMPLE)
            put("data".toByteArray(Charsets.US_ASCII))
            putInt(dataSize)
        }.array()

        val sampleBytes = ByteBuffer.allocate(dataSize).order(ByteOrder.LITTLE_ENDIAN).also {
            samples.forEach { sample -> it.putFloat(sample) }
        }.array()

        RandomAccessFile(target, "rw").use { raf ->
            raf.setLength(0)
            raf.write(header)
            raf.write(sampleBytes)
        }
    }
}
