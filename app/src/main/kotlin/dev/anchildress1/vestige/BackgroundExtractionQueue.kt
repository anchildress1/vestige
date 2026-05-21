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
    private val createExtractionJob: (PendingExtractionWork) -> Job,
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
            val active = activeExtraction?.takeUnless { it.job.isCompleted }?.also {
                it.preemptedByForeground = true
                requeueActiveLocked(it)
            }
            if (active != null) {
                activeExtraction = null
            }
            val drain = if (activeExtraction?.job?.isCompleted == true) null else drainJob
            if (drain != null) {
                drainJob = null
            }
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
            val active = nextActiveExtraction() ?: return
            active.job.start()
            active.job.join()
            val shouldRequeue = mutex.withLock {
                val preempted = active.preemptedByForeground
                if (activeExtraction === active) activeExtraction = null
                preempted && !active.queued.completion.isCancelled
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
                active.queued.completion.complete()
            }
        }
    }

    private suspend fun nextActiveExtraction(): ActiveExtraction? = mutex.withLock {
        var active: ActiveExtraction? = null
        while (active == null) {
            if (foregroundDepth > 0 || pending.isEmpty()) {
                drainJob = null
                return@withLock null
            }
            val queued = pending.removeFirst()
            if (queued.completion.isCancelled) {
                queued.completion.complete()
                continue
            }
            active = ActiveExtraction(
                queued = queued,
                job = createExtractionJob(queued.work),
            ).also { activeExtraction = it }
        }
        active
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
