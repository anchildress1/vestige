package dev.anchildress1.vestige.ui.onboarding

import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class PersistedStateWriteLaneTest {

    @Test
    fun `run serializes overlapping writes in arrival order`() = runBlocking {
        Executors.newFixedThreadPool(2).asCoroutineDispatcher().use { dispatcher ->
            val lane = PersistedStateWriteLane(dispatcher)
            val order = Collections.synchronizedList(mutableListOf<String>())
            val firstEntered = CountDownLatch(1)
            val releaseFirst = CountDownLatch(1)

            val first = async(dispatcher) {
                lane.run {
                    order += "first-start"
                    firstEntered.countDown()
                    assertTrue(releaseFirst.await(1, TimeUnit.SECONDS))
                    order += "first-end"
                }
            }
            assertTrue(firstEntered.await(1, TimeUnit.SECONDS))

            val second = async(dispatcher) {
                lane.run {
                    order += "second"
                }
            }

            delay(50)
            assertEquals(listOf("first-start"), order)

            releaseFirst.countDown()
            awaitAll(first, second)

            assertEquals(
                listOf("first-start", "first-end", "second"),
                order,
            )
        }
    }
}
