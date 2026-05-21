package dev.anchildress1.vestige

import dev.anchildress1.vestige.inference.BackgroundExtractionRequest
import dev.anchildress1.vestige.inference.ExtractionStatusListener
import dev.anchildress1.vestige.model.Persona
import dev.anchildress1.vestige.save.BackgroundExtractionSaveFlow
import dev.anchildress1.vestige.save.PendingExtractionWork
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.ArrayDeque

@OptIn(ExperimentalCoroutinesApi::class)
class BackgroundExtractionQueueTest {

    @Test
    fun `enqueue launches immediately when no foreground inference is active`() = runTest {
        val activeJob = Job()
        val launches = mutableListOf<Long>()
        val queue = BackgroundExtractionQueue(backgroundScope) { work ->
            launches += work.entryId
            activeJob
        }

        val completion = queue.enqueue(work(entryId = 1L))
        runCurrent()

        assertEquals(listOf(1L), launches)
        assertFalse(completion.isCompleted)

        activeJob.complete()
        runCurrent()

        assertTrue(completion.isCompleted)
    }

    @Test
    fun `foreground depth holds queued work until the final foreground exits`() = runTest {
        val launches = mutableListOf<Long>()
        val queue = BackgroundExtractionQueue(backgroundScope) { work ->
            launches += work.entryId
            completedJob()
        }

        queue.beginForeground()
        queue.beginForeground()
        val completion = queue.enqueue(work(entryId = 2L))
        runCurrent()

        assertEquals(emptyList<Long>(), launches)
        assertFalse(completion.isCompleted)

        queue.endForeground()
        runCurrent()
        assertEquals(emptyList<Long>(), launches)

        queue.endForeground()
        runCurrent()

        assertEquals(listOf(2L), launches)
        assertTrue(completion.isCompleted)
    }

    @Test
    fun `queued work drains in FIFO order`() = runTest {
        val launches = mutableListOf<Long>()
        val queue = BackgroundExtractionQueue(backgroundScope) { work ->
            launches += work.entryId
            completedJob()
        }

        val first = queue.enqueue(work(entryId = 1L))
        val second = queue.enqueue(work(entryId = 2L))
        val third = queue.enqueue(work(entryId = 3L))
        runCurrent()

        assertEquals(listOf(1L, 2L, 3L), launches)
        assertTrue(first.isCompleted)
        assertTrue(second.isCompleted)
        assertTrue(third.isCompleted)
    }

    @Test
    fun `foreground preempts active work and reruns it before later queued work`() = runTest {
        val firstRun = Job()
        val jobs = ArrayDeque<Job>().apply {
            add(firstRun)
            add(completedJob())
            add(completedJob())
        }
        val launches = mutableListOf<Long>()
        val queue = BackgroundExtractionQueue(backgroundScope) { work ->
            launches += work.entryId
            jobs.removeFirst()
        }

        val firstCompletion = queue.enqueue(work(entryId = 1L))
        runCurrent()
        val secondCompletion = queue.enqueue(work(entryId = 2L))
        runCurrent()

        assertEquals(listOf(1L), launches)
        assertFalse(firstCompletion.isCompleted)
        assertFalse(secondCompletion.isCompleted)

        queue.beginForeground()
        runCurrent()
        assertTrue(firstRun.isCancelled)

        queue.endForeground()
        runCurrent()

        assertEquals(listOf(1L, 1L, 2L), launches)
        assertTrue(firstCompletion.isCompleted)
        assertTrue(secondCompletion.isCompleted)
    }

    @Test
    fun `foreground preempts lazily created extraction after it is published`() = runTest {
        val activeRun = backgroundScope.launch(start = CoroutineStart.LAZY) {
            awaitCancellation()
        }
        val jobs = ArrayDeque<Job>().apply {
            add(activeRun)
            add(completedJob())
        }
        val launches = mutableListOf<Long>()
        val queue = BackgroundExtractionQueue(backgroundScope) { work ->
            launches += work.entryId
            jobs.removeFirst()
        }

        val completion = queue.enqueue(work(entryId = 1L))
        runCurrent()

        assertEquals(listOf(1L), launches)
        assertFalse(completion.isCompleted)

        queue.beginForeground()
        runCurrent()

        assertTrue(activeRun.isCancelled)
        assertFalse(completion.isCompleted)

        queue.endForeground()
        runCurrent()

        assertEquals(listOf(1L, 1L), launches)
        assertTrue(completion.isCompleted)
    }

    @Test
    fun `cancelAll cancels active and queued logical work`() = runTest {
        val activeRun = Job()
        val launches = mutableListOf<Long>()
        val queue = BackgroundExtractionQueue(backgroundScope) { work ->
            launches += work.entryId
            activeRun
        }

        val activeCompletion = queue.enqueue(work(entryId = 1L))
        runCurrent()
        val queuedCompletion = queue.enqueue(work(entryId = 2L))
        runCurrent()

        queue.cancelAll()
        runCurrent()

        assertEquals(listOf(1L), launches)
        assertTrue(activeRun.isCancelled)
        assertTrue(activeCompletion.isCancelled)
        assertTrue(queuedCompletion.isCancelled)
    }

    @Test
    fun `cancelled queued work is skipped when foreground releases`() = runTest {
        val launches = mutableListOf<Long>()
        val queue = BackgroundExtractionQueue(backgroundScope) { work ->
            launches += work.entryId
            completedJob()
        }

        queue.beginForeground()
        val cancelledCompletion = queue.enqueue(work(entryId = 1L))
        val liveCompletion = queue.enqueue(work(entryId = 2L))
        cancelledCompletion.cancel()
        runCurrent()

        queue.endForeground()
        runCurrent()

        assertEquals(listOf(2L), launches)
        assertTrue(cancelledCompletion.isCancelled)
        assertTrue(liveCompletion.isCompleted)
    }

    @Test
    fun `queue can drain new work after cancelAll clears active state`() = runTest {
        val activeRun = Job()
        val launches = mutableListOf<Long>()
        val queue = BackgroundExtractionQueue(backgroundScope) { work ->
            launches += work.entryId
            if (work.entryId == 1L) activeRun else completedJob()
        }

        queue.enqueue(work(entryId = 1L))
        runCurrent()
        queue.cancelAll()
        runCurrent()
        val laterCompletion = queue.enqueue(work(entryId = 2L))
        runCurrent()

        assertEquals(listOf(1L, 2L), launches)
        assertTrue(laterCompletion.isCompleted)
    }

    @Test
    fun `external active cancellation completes logical work without requeueing`() = runTest {
        val activeRun = Job()
        val launches = mutableListOf<Long>()
        val queue = BackgroundExtractionQueue(backgroundScope) { work ->
            launches += work.entryId
            activeRun
        }

        val completion = queue.enqueue(work(entryId = 1L))
        runCurrent()
        activeRun.cancel()
        runCurrent()

        assertEquals(listOf(1L), launches)
        assertTrue(completion.isCompleted)
        assertFalse(completion.isCancelled)
    }

    private fun completedJob(): Job = Job().also { it.complete() }

    private fun work(entryId: Long): PendingExtractionWork {
        val capturedAt = ZonedDateTime.of(2026, 5, 20, 22, 0, 0, 0, ZoneId.of("America/New_York"))
        return PendingExtractionWork(
            entryId = entryId,
            entryText = "entry $entryId",
            capturedAt = capturedAt,
            request = BackgroundExtractionRequest(
                entryText = "entry $entryId",
                capturedAt = capturedAt,
                retrievedHistory = emptyList(),
                entryAttemptCount = 0,
                timeoutMs = null,
            ),
            terminalRelay = BackgroundExtractionSaveFlow.DeferredTerminalRelay(
                ExtractionStatusListener { _, _, _ -> },
            ),
            persona = Persona.WITNESS,
        )
    }
}
