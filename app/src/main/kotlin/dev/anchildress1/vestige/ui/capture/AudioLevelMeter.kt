package dev.anchildress1.vestige.ui.capture

import kotlin.math.min
import kotlin.math.sqrt

/**
 * Reads PCM_FLOAT samples in `[-1, 1]` and emits a 0..1 RMS level plus a FIFO window of the most
 * recent levels for the live bar strip. Stateless across captures — the host VM constructs a new
 * meter per recording session so window state doesn't leak.
 *
 * No Android types — pure JVM, testable from the unit suite.
 */
class AudioLevelMeter(private val windowSize: Int = DEFAULT_WINDOW_SIZE) {
    init {
        require(windowSize > 0) { "windowSize must be positive (was $windowSize)" }
    }

    private val window: FloatArray = FloatArray(windowSize)
    private var nextIndex: Int = 0
    private var filled: Boolean = false

    /** Most recent levels in chronological order, oldest first. Size always equals [windowSize]. */
    val levels: List<Float>
        get() {
            val out = FloatArray(windowSize)
            for (i in 0 until windowSize) {
                out[i] = window[(nextIndex + i) % windowSize]
            }
            return out.toList()
        }

    /** Push the next chunk; returns the RMS level (clamped to `[0, 1]`). */
    fun push(samples: FloatArray, count: Int = samples.size): Float {
        require(count in 0..samples.size) { "count=$count out of range for samples.size=${samples.size}" }
        val level = rms(samples, count).coerceIn(0f, 1f)
        window[nextIndex] = level
        nextIndex = (nextIndex + 1) % windowSize
        if (nextIndex == 0) filled = true
        return level
    }

    /** True once the ring has rolled over at least once. */
    fun isWindowFull(): Boolean = filled

    private fun rms(samples: FloatArray, count: Int): Float {
        if (count == 0) return 0f
        var sumSq = 0.0
        val limit = min(count, samples.size)
        for (i in 0 until limit) {
            val s = samples[i]
            sumSq += (s * s).toDouble()
        }
        return sqrt(sumSq / limit).toFloat()
    }

    private companion object {
        const val DEFAULT_WINDOW_SIZE: Int = 42
    }
}
