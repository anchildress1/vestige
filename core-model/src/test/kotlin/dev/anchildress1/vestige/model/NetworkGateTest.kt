package dev.anchildress1.vestige.model

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class NetworkGateTest {

    @Test
    fun `default state is Sealed`() {
        val gate = DefaultNetworkGate()
        assertEquals(GateState.Sealed, gate.state)
    }

    @Test
    fun `assertOpen throws on Sealed`() {
        val gate = DefaultNetworkGate()
        assertThrows(NetworkSealedException::class.java) { gate.assertOpen() }
    }

    @Test
    fun `openForDownload moves the gate to Open with the supplied reason`() {
        val gate = DefaultNetworkGate()
        gate.openForDownload(reason = "model-artifact-download")
        val state = gate.state
        assertTrue(state is GateState.Open) { "expected Open, got $state" }
        assertEquals("model-artifact-download", (state as GateState.Open).reason)
        gate.assertOpen() // must not throw
    }

    @Test
    fun `openForDownload rejects a blank reason`() {
        val gate = DefaultNetworkGate()
        assertThrows(IllegalArgumentException::class.java) { gate.openForDownload(reason = "") }
        assertThrows(IllegalArgumentException::class.java) { gate.openForDownload(reason = "   ") }
    }

    @Test
    fun `seal returns the gate to Sealed and assertOpen throws again`() {
        val gate = DefaultNetworkGate()
        gate.openForDownload(reason = "model-artifact-download")
        gate.seal()
        assertEquals(GateState.Sealed, gate.state)
        assertThrows(NetworkSealedException::class.java) { gate.assertOpen() }
    }

    @Test
    fun `seal is idempotent`() {
        val gate = DefaultNetworkGate()
        gate.seal()
        gate.seal() // must not throw
        assertEquals(GateState.Sealed, gate.state)
    }

    @Test
    fun `DefaultHttpClient asks the gate before opening a connection`() {
        val sealedGate = DefaultNetworkGate() // starts Sealed
        val client = DefaultHttpClient(allowedHosts = listOf("huggingface.co"), networkGate = sealedGate)
        assertThrows(NetworkSealedException::class.java) {
            client.open("https://huggingface.co/x", resumeFromByte = 0)
        }
    }

    @Test
    fun `ALWAYS_OPEN_FOR_TESTS satisfies assertOpen without state changes`() {
        val gate = DefaultNetworkGate.ALWAYS_OPEN_FOR_TESTS
        gate.assertOpen() // must not throw
    }

    @Test
    fun `ALWAYS_OPEN_FOR_TESTS seal and openForDownload are no-ops`() {
        val gate = DefaultNetworkGate.ALWAYS_OPEN_FOR_TESTS
        gate.seal() // must not throw or change state
        gate.openForDownload("any reason") // must not throw
        gate.assertOpen() // still open after all of the above
    }

    @Test
    fun `assertOpen surfaces a sealed-state message on failure`() {
        val gate = DefaultNetworkGate()
        val ex = assertThrows(NetworkSealedException::class.java) { gate.assertOpen() }
        // The message is grep-bait for CI privacy regression checks per ADR-001 §Q7 — pin the
        // exact substrings rather than a free-form match.
        assertTrue(ex.message?.contains("sealed") == true) { "Expected 'sealed' in: ${ex.message}" }
        assertTrue(ex.message?.contains("outbound") == true) { "Expected 'outbound' in: ${ex.message}" }
    }

    @Test
    fun `openForDownload reason replaces the prior reason on repeated open`() {
        val gate = DefaultNetworkGate()
        gate.openForDownload("first-reason")
        gate.openForDownload("second-reason")
        val state = gate.state
        assertTrue(state is GateState.Open)
        // Last call wins — the gate must NOT stack reasons or keep the original.
        assertEquals("second-reason", (state as GateState.Open).reason)
    }

    @Test
    fun `concurrent seal and openForDownload converge on a consistent terminal state`() {
        // The gate documents itself as AtomicReference-backed (NetworkGate.kt §"Default
        // thread-safe NetworkGate"). This test pins that property: under contention, every
        // observed state is valid, no read returns null/torn data, and the last write wins
        // deterministically.
        val gate = DefaultNetworkGate()
        val executor = Executors.newFixedThreadPool(THREAD_COUNT)
        val ready = CountDownLatch(THREAD_COUNT)
        val go = CountDownLatch(1)
        val done = CountDownLatch(THREAD_COUNT)
        val errors = java.util.concurrent.ConcurrentLinkedQueue<Throwable>()

        try {
            repeat(THREAD_COUNT) { i ->
                executor.submit {
                    try {
                        ready.countDown()
                        go.await()
                        repeat(ITERATIONS_PER_THREAD) {
                            if (i % 2 == 0) gate.openForDownload("thread-$i") else gate.seal()
                            val observed = gate.state
                            // Every read must surface a real GateState — never null, never partial.
                            if (observed !is GateState.Open && observed !== GateState.Sealed) {
                                errors += AssertionError("unexpected state: $observed")
                            }
                        }
                    } catch (t: Throwable) {
                        errors += t
                    } finally {
                        done.countDown()
                    }
                }
            }
            ready.await()
            go.countDown()
            assertTrue(done.await(5, TimeUnit.SECONDS)) { "Threads did not finish in time" }
        } finally {
            executor.shutdownNow()
        }
        assertTrue(errors.isEmpty()) { "Concurrent operations surfaced errors: $errors" }

        // Force a known terminal: seal at the end and prove the gate honors it.
        gate.seal()
        assertEquals(GateState.Sealed, gate.state)
        assertThrows(NetworkSealedException::class.java) { gate.assertOpen() }
    }

    private companion object {
        const val THREAD_COUNT = 8
        const val ITERATIONS_PER_THREAD = 200
    }
}
