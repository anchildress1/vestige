package dev.anchildress1.vestige.ui.onboarding

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class PersistedStateWriteLaneTest {

    @Test
    fun `run serializes overlapping writes in arrival order`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val lane = PersistedStateWriteLane(dispatcher)
        val order = mutableListOf<String>()
        val firstEntered = CompletableDeferred<Unit>()
        val releaseFirst = CompletableDeferred<Unit>()

        val first = async {
            lane.run {
                order += "first-start"
                firstEntered.complete(Unit)
                releaseFirst.await()
                order += "first-end"
            }
        }
        firstEntered.await()
        val second = async {
            lane.run {
                order += "second"
            }
        }

        testScheduler.runCurrent()
        assertEquals(listOf("first-start"), order)

        releaseFirst.complete(Unit)
        awaitAll(first, second)

        assertEquals(
            listOf("first-start", "first-end", "second"),
            order,
        )
    }
}
