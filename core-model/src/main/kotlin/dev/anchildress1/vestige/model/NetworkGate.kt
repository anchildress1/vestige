package dev.anchildress1.vestige.model

import java.util.concurrent.atomic.AtomicReference

/**
 * Privacy posture in code per ADR-001 §Q7. The gate is `Sealed` by default; it can be flipped
 * to `Open` only for the duration of the Story 1.9 model-download flow and is sealed again
 * the moment the download completes. All outbound HTTP primitives consult [assertOpen] before
 * dialing — failing fast on a sealed gate.
 *
 * v1 has exactly one outbound primitive (the model download). The gate exists so adding a
 * second outbound surface in the future (analytics, crash reporting, telemetry) is not a
 * silent change — it requires either flipping the gate open or routing the call through
 * `NetworkGate`, both of which are visible at code review.
 */
interface NetworkGate {

    val state: GateState

    /**
     * Open the gate during the model-download flow. Pair with [seal] in a try/finally so a
     * thrown exception during download still reseals the gate.
     */
    fun openForDownload(reason: String)

    /** Seal the gate. Idempotent. */
    fun seal()

    /**
     * Throw [NetworkSealedException] when the gate is sealed. Outbound HTTP primitives call
     * this before each connect.
     */
    fun assertOpen()
}

sealed interface GateState {
    /** Outbound network is permitted. Only valid during the model-download window. */
    data class Open(val reason: String) : GateState

    /** Outbound network is forbidden. Default. */
    data object Sealed : GateState
}

/** Thrown by [NetworkGate.assertOpen] when the gate is sealed. */
class NetworkSealedException(message: String) : SecurityException(message)

/** Default thread-safe [NetworkGate] backed by an [AtomicReference]. */
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
        /** Test-only convenience: a permanently-open gate. Production never instantiates this. */
        val ALWAYS_OPEN_FOR_TESTS: NetworkGate = object : NetworkGate {
            override val state: GateState = GateState.Open("test")
            override fun openForDownload(reason: String) = Unit
            override fun seal() = Unit
            override fun assertOpen() = Unit
        }
    }
}
