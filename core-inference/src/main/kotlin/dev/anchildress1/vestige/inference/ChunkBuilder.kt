package dev.anchildress1.vestige.inference

/**
 * Buffers incoming PCM_FLOAT samples and emits a fresh `FloatArray` every [samplesPerChunk]
 * samples. The trailing partial chunk (less than one full window) is returned via [drainFinal].
 *
 * Each emitted array is owned by the caller — [ChunkBuilder] does not mutate it after handoff.
 */
internal class ChunkBuilder(val samplesPerChunk: Int) {

    init {
        require(samplesPerChunk > 0) { "samplesPerChunk must be positive, was $samplesPerChunk" }
    }

    private var current = FloatArray(samplesPerChunk)
    private var pos = 0

    /**
     * Append the first [count] samples from [source] and return any complete chunks. Each
     * returned array is exactly [samplesPerChunk] long and represents one non-final 30-second
     * window. Returns an empty list when no chunk completes inside this call.
     */
    fun append(source: FloatArray, count: Int): List<FloatArray> {
        require(count >= 0) { "count must be non-negative, was $count" }
        require(count <= source.size) { "count $count exceeds source.size ${source.size}" }
        if (count == 0) return emptyList()

        val out = mutableListOf<FloatArray>()
        var offset = 0
        while (offset < count) {
            val take = minOf(samplesPerChunk - pos, count - offset)
            System.arraycopy(source, offset, current, pos, take)
            pos += take
            offset += take
            if (pos == samplesPerChunk) {
                out += current
                current = FloatArray(samplesPerChunk)
                pos = 0
            }
        }
        return out
    }

    /**
     * Drain the trailing < [samplesPerChunk] samples buffered so far. Returns `null` when the
     * builder is empty (i.e. capture stopped on an exact chunk boundary).
     */
    fun drainFinal(): FloatArray? {
        if (pos == 0) return null
        val tail = current.copyOf(pos)
        pos = 0
        return tail
    }

    /**
     * Zero the in-progress buffer and reset the write head so accumulated PCM samples don't
     * linger after cancel / discard.
     */
    fun clear() {
        current.fill(0f)
        pos = 0
    }
}
