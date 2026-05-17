package dev.anchildress1.vestige.ui.onboarding

import dev.anchildress1.vestige.model.ModelArtifactState
import org.junit.Assert.assertEquals
import org.junit.Test

class DownloadProgressTrackerTest {

    @Test
    fun `first onProgress sets baseline and does not emit MB per s`() {
        val states = mutableListOf<ModelArtifactState>()
        var speed: Float? = -1f
        val tracker = DownloadProgressTracker(
            onState = { states += it },
            onSpeed = { speed = it },
            nowMillis = { 1_000L },
        )

        tracker.onProgress(currentBytes = 100, expectedBytes = 1_000)

        assertEquals(ModelArtifactState.Partial(100, 1_000), states.single())
        assertEquals("baseline tick must not emit MB/s", -1f, speed)
    }

    @Test
    fun `second onProgress within sample window does not emit MB per s`() {
        val speeds = mutableListOf<Float?>()
        var clock = 1_000L
        val tracker = DownloadProgressTracker(
            onState = {},
            onSpeed = { speeds += it },
            nowMillis = { clock },
        )

        tracker.onProgress(currentBytes = 0, expectedBytes = 1_000)
        clock = 1_500L // 500 ms — under SPEED_SAMPLE_INTERVAL_MS (1_000)
        tracker.onProgress(currentBytes = 500, expectedBytes = 1_000)

        assertEquals(emptyList<Float?>(), speeds)
    }

    @Test
    fun `onProgress past sample window emits MB per s computed from delta and elapsed`() {
        val speeds = mutableListOf<Float?>()
        var clock = 0L
        val tracker = DownloadProgressTracker(
            onState = {},
            onSpeed = { speeds += it },
            nowMillis = { clock },
        )
        // Baseline at 0 bytes / t=0.
        tracker.onProgress(currentBytes = 0, expectedBytes = BYTES_PER_MB.toLong() * 2)
        // Advance 1 s, +1 MB → exactly 1 MB/s.
        clock = MS_PER_SECOND.toLong()
        tracker.onProgress(currentBytes = BYTES_PER_MB.toLong(), expectedBytes = BYTES_PER_MB.toLong() * 2)

        assertEquals(1, speeds.size)
        assertEquals(1f, speeds.single()!!, 0.001f)
    }

    @Test
    fun `eta is null until a speed sample exists`() {
        val etas = mutableListOf<Long?>()
        var clock = 0L
        val tracker = DownloadProgressTracker(
            onState = {},
            onSpeed = {},
            onEta = { etas += it },
            nowMillis = { clock },
        )
        // Baseline tick — no rate yet.
        tracker.onProgress(currentBytes = 0, expectedBytes = BYTES_PER_MB.toLong() * 2)
        clock = 500L // still inside the sample window — rate remains unknown
        tracker.onProgress(currentBytes = BYTES_PER_MB.toLong() / 2, expectedBytes = BYTES_PER_MB.toLong() * 2)

        assertEquals(listOf<Long?>(null, null), etas)
    }

    @Test
    fun `eta is remaining bytes over the sampled rate once a sample lands`() {
        val etas = mutableListOf<Long?>()
        var clock = 0L
        val tracker = DownloadProgressTracker(
            onState = {},
            onSpeed = {},
            onEta = { etas += it },
            nowMillis = { clock },
        )
        tracker.onProgress(currentBytes = 0, expectedBytes = BYTES_PER_MB.toLong() * 2)
        // +1 s, +1 MB → 1 MB/s. 1 MB still to go → ~1 s remaining.
        clock = MS_PER_SECOND.toLong()
        tracker.onProgress(currentBytes = BYTES_PER_MB.toLong(), expectedBytes = BYTES_PER_MB.toLong() * 2)

        assertEquals(listOf<Long?>(null, 1L), etas)
    }

    @Test
    fun `unchanged percent does not re-log between ticks`() {
        // Same pct on two consecutive ticks — second tick must early-exit the log branch and not
        // emit a MB/s sample (within-window suppress + identical bytes = zero delta).
        val states = mutableListOf<ModelArtifactState>()
        val speeds = mutableListOf<Float?>()
        var clock = 1_000L
        val tracker = DownloadProgressTracker(
            onState = { states += it },
            onSpeed = { speeds += it },
            nowMillis = { clock },
        )
        tracker.onProgress(currentBytes = 100, expectedBytes = 1_000)
        clock += 200L
        tracker.onProgress(currentBytes = 100, expectedBytes = 1_000)

        assertEquals(2, states.size)
        assertEquals(emptyList<Float?>(), speeds)
    }
}
