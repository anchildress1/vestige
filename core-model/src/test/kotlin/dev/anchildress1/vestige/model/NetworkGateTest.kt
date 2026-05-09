package dev.anchildress1.vestige.model

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

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
}
