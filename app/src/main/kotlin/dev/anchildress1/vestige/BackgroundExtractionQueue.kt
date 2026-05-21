package dev.anchildress1.vestige

import dev.anchildress1.vestige.save.PendingExtractionWork
import kotlinx.coroutines.CompletableJob
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.ArrayDeque

internal class BackgroundExtractionQueue(
    private val scope: CoroutineScope,
    private val launchExtraction: (PendingExtractionWork) -> Job,
) {
    private val mutex = Mutex()
    private val pending: ArrayDeque<QueuedExtraction> = ArrayDeque()
    private var activeExtraction: ActiveExtraction? = null
    private var drainJob: Job? = null
    private var foregroundDepth: Int = 0

    suspend fun enqueue(work: PendingExtractionWork): Job {
        val queued = QueuedExtraction(work = work)
        mutex.withLock {
            pending.addLast(queued)
            startDrainLocked()
        }
        return queued.completion
    }

    suspend fun beginForeground() {
        val (active, drain) = mutex.withLock {
            foregroundDepth += 1
            val active = activeExtraction?.also {
                it.preemptedByForeground = true
                requeueActiveLocked(it)
            }
            activeExtraction = null
            val drain = drainJob
            drainJob = null
            active to drain
        }
        drain?.cancel()
        active?.job?.cancelAndJoin()
    }

    suspend fun endForeground() {
        mutex.withLock {
            foregroundDepth = (foregroundDepth - 1).coerceAtLeast(0)
            startDrainLocked(restart = true)
        }
    }

    suspend fun cancelAll() {
        val active = mutex.withLock {
            drainJob?.cancel()
            drainJob = null
            pending.forEach { it.completion.cancel() }
            pending.clear()
            activeExtraction?.also { it.queued.completion.cancel() }
        }
        active?.job?.cancelAndJoin()
        mutex.withLock {
            if (activeExtraction === active) {
                activeExtraction = null
            }
        }
    }

    private fun startDrainLocked(restart: Boolean = false) {
        if (foregroundDepth > 0) return
        if (restart) {
            drainJob?.cancel()
            drainJob = null
        } else if (drainJob?.isActive == true) {
            return
        }
        drainJob = scope.launch { drain() }
    }

    private suspend fun drain() {
        while (true) {
            val queued = mutex.withLock {
                if (foregroundDepth > 0 || pending.isEmpty()) {
                    drainJob = null
                    return
                }
                pending.removeFirst()
            }
            if (queued.completion.isCancelled) {
                queued.completion.complete()
                continue
            }
            val extractionJob = launchExtraction(queued.work)
            val active = ActiveExtraction(queued = queued, job = extractionJob)
            mutex.withLock { activeExtraction = active }
            extractionJob.join()
            val shouldRequeue = mutex.withLock {
                val preempted = active.preemptedByForeground
                if (activeExtraction === active) activeExtraction = null
                preempted && !queued.completion.isCancelled
            }
            if (shouldRequeue) {
                mutex.withLock {
                    requeueActiveLocked(active)
                    if (foregroundDepth > 0) {
                        drainJob = null
                        return
                    }
                }
            } else {
                queued.completion.complete()
            }
        }
    }

    private fun requeueActiveLocked(active: ActiveExtraction) {
        if (!active.requeuedByForeground) {
            pending.addFirst(active.queued)
            active.requeuedByForeground = true
        }
    }

    private data class QueuedExtraction(val work: PendingExtractionWork, val completion: CompletableJob = Job())

    private class ActiveExtraction(
        val queued: QueuedExtraction,
        val job: Job,
        var preemptedByForeground: Boolean = false,
        var requeuedByForeground: Boolean = false,
    )
}
