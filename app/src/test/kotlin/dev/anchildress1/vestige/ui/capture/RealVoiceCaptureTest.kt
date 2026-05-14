package dev.anchildress1.vestige.ui.capture

import dev.anchildress1.vestige.inference.AudioChunk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

/**
 * JVM contract tests for the [RealVoiceCapture] adapter. The seam [AudioSource] lets the suite
 * swap a deterministic fake; on-device behavior of the production [AudioRecordSource] is
 * covered by the existing [AudioCapture] unit tests + the STT-A round-trip.
 *
 * pos: a chunk emitted by the source becomes the adapter's return value; onLevel forwards.
 * neg: when the source flow emits nothing, the adapter returns null.
 * err: source throwing propagates to the caller.
 * edge: stopFlow.emit raises source.requestStop on the same coroutine scope.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RealVoiceCaptureTest {

    @Test
    fun `forwards onLevel and returns the emitted chunk`() = runTest {
        val seenLevels = mutableListOf<Float>()
        val emitted = AudioChunk(FloatArray(4), sampleRateHz = 16_000, isFinal = true)
        val factoryInvocations = AtomicInteger(0)
        val sut = RealVoiceCapture(
            sourceFactory = { onLevel ->
                factoryInvocations.incrementAndGet()
                FakeAudioSource(
                    onCaptureChunks = flow {
                        onLevel(0.42f)
                        emit(emitted)
                    },
                )
            },
        )
        val result = sut.invoke(
            onLevel = { seenLevels += it },
            stopFlow = MutableSharedFlow<Unit>().asSharedFlow(),
        )
        assertEquals(emitted, result)
        assertEquals(1, factoryInvocations.get())
        assertEquals(listOf(0.42f), seenLevels)
    }

    @Test
    fun `returns null when the source emits nothing`() = runTest {
        val sut = RealVoiceCapture(sourceFactory = { _ -> FakeAudioSource(onCaptureChunks = flow {}) })
        val result = sut.invoke(
            onLevel = {},
            stopFlow = MutableSharedFlow<Unit>().asSharedFlow(),
        )
        assertNull(result)
    }

    @Test
    fun `source error propagates to the caller`() = runTest {
        val sut = RealVoiceCapture(
            sourceFactory = { _ ->
                FakeAudioSource(onCaptureChunks = flow { error("mic failure") })
            },
        )
        val outcome = runCatching {
            sut.invoke(onLevel = {}, stopFlow = MutableSharedFlow<Unit>().asSharedFlow())
        }
        val ex = outcome.exceptionOrNull()
        assertNotNull(ex)
        assertTrue(ex!!.message!!.contains("mic failure"))
    }

    @Test
    fun `stopFlow requests source stop while capture is active`() = runTest {
        val stopRequests = AtomicInteger(0)
        val stopFlow = MutableSharedFlow<Unit>(replay = 1)
        val sut = RealVoiceCapture(
            sourceFactory = { _ ->
                FakeAudioSource(
                    onCaptureChunks = flow { awaitCancellation() },
                    onRequestStop = { stopRequests.incrementAndGet() },
                )
            },
        )

        val capture = async {
            sut.invoke(onLevel = {}, stopFlow = stopFlow.asSharedFlow())
        }
        stopFlow.emit(Unit)
        advanceUntilIdle()

        assertEquals(1, stopRequests.get())
        capture.cancelAndJoin()
    }

    private class FakeAudioSource(
        private val onCaptureChunks: Flow<AudioChunk>,
        private val onRequestStop: () -> Unit = {},
    ) : AudioSource {
        override fun captureChunks(): Flow<AudioChunk> = onCaptureChunks
        override fun requestStop() = onRequestStop()
    }
}
