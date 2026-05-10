package dev.anchildress1.vestige.model

import java.util.concurrent.atomic.AtomicReference

/**
 * Privacy posture in code: `Sealed` by default; flipped to `Open` only for the model-download
 * flow and sealed again the moment it completes. Outbound HTTP primitives must call [assertOpen]
 * before dialing. Adding a second outbound surface either flips the gate or routes through this
 * interface — silent additions are impossible.
 */
interface NetworkGate {

    val state: GateState

    /** Pair with [seal] in try/finally so a download exception still reseals. */
    fun openForDownload(reason: String)

    /** Idempotent. */
    fun seal()

    /** Throws [NetworkSealedException] when sealed. */
    fun assertOpen()
}

sealed interface GateState {
    data class Open(val reason: String) : GateState
    data object Sealed : GateState
}

class NetworkSealedException(message: String) : SecurityException(message)

class DefaultNetworkGate(initial: GateState = GateState.Sealed) : NetworkGate {

    private val ref = AtomicReference<GateState>(initial)

    override val state: GateState get() = ref.get()

    override fun openForDownload(reason: String) {
        require(reason.isNotBlank()) { "openForDownload requires a reason for telemetry/audit." }
        ref.set(GateState.Open(reason))
    }

    override fun seal() {
        ref.set(GateState.Sealed)
    }

    override fun assertOpen() {
        val current = ref.get()
        if (current !is GateState.Open) {
            throw NetworkSealedException(
                "NetworkGate is sealed; outbound network forbidden in normal operation.",
            )
        }
    }

    companion object {
        /** Test-only. */
        val ALWAYS_OPEN_FOR_TESTS: NetworkGate = object : NetworkGate {
            override val state: GateState = GateState.Open("test")
            override fun openForDownload(reason: String) = Unit
            override fun seal() = Unit
            override fun assertOpen() = Unit
        }
    }
}
