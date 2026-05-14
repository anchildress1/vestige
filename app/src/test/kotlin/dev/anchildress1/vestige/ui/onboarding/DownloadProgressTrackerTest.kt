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
    fun `expectedBytes of zero logs pct as -1 without dividing`() {
        // Smoke-only: branch coverage for the `expectedBytes <= 0` log path. The Log.d call is
        // a side effect with no observable return; touching the path is the assertion.
        val tracker = DownloadProgressTracker(onState = {}, onSpeed = {})
        tracker.onProgress(currentBytes = 42, expectedBytes = 0)
        tracker.onProgress(currentBytes = 84, expectedBytes = 0)
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
