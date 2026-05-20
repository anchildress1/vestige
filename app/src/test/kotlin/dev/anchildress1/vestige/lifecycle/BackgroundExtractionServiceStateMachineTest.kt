package dev.anchildress1.vestige.lifecycle

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class)
class BackgroundExtractionServiceStateMachineTest {

    private fun machine(
        scope: TestScope,
        foregroundStartRetryDelay: Duration = 5.seconds,
        backgroundStartRetryDelay: Duration = 60.seconds,
        onPromoteRequested: () -> Unit = {},
    ): BackgroundExtractionLifecycleStateMachine = BackgroundExtractionLifecycleStateMachine(
        scope = scope,
        keepAlive = 30.seconds,
        foregroundStartRetryDelay = foregroundStartRetryDelay,
        backgroundStartRetryDelay = backgroundStartRetryDelay,
        onPromoteRequested = onPromoteRequested,
    )

    @Test
    fun `cold start with no pending entries stays NORMAL`() = runTest {
        val machine = machine(this)

        machine.onInFlightCountChange(0)
        advanceUntilIdle()

        assertEquals(BackgroundExtractionLifecycleState.NORMAL, machine.state.value)
    }

    @Test
    fun `single entry promote demote cycle walks every transition`() = runTest {
        val machine = machine(this)

        machine.onInFlightCountChange(1)
        assertEquals(BackgroundExtractionLifecycleState.PROMOTING, machine.state.value)

        machine.onForegroundStartConfirmed()
        assertEquals(BackgroundExtractionLifecycleState.FOREGROUND, machine.state.value)

        machine.onInFlightCountChange(0)
        assertEquals(BackgroundExtractionLifecycleState.KEEP_ALIVE, machine.state.value)

        advanceTimeBy(30_001L)
        assertEquals(BackgroundExtractionLifecycleState.DEMOTING, machine.state.value)

        machine.onForegroundStopConfirmed()
        assertEquals(BackgroundExtractionLifecycleState.NORMAL, machine.state.value)
    }

    @Test
    fun `back-to-back captures during keep-alive bounce back to FOREGROUND without flicker`() = runTest {
        val machine = machine(this)
        machine.onInFlightCountChange(1)
        machine.onForegroundStartConfirmed()
        machine.onInFlightCountChange(0)
        assertEquals(BackgroundExtractionLifecycleState.KEEP_ALIVE, machine.state.value)

        // Mid-keep-alive: a new capture arrives.
        advanceTimeBy(10_000L)
        machine.onInFlightCountChange(1)
        advanceUntilIdle()

        assertEquals(BackgroundExtractionLifecycleState.FOREGROUND, machine.state.value)

        // Cancelled keep-alive must not fire later.
        advanceTimeBy(60_000L)
        assertEquals(BackgroundExtractionLifecycleState.FOREGROUND, machine.state.value)
    }

    @Test
    fun `keep-alive expiry without new work transitions to DEMOTING`() = runTest {
        val machine = machine(this)
        machine.onInFlightCountChange(1)
        machine.onForegroundStartConfirmed()
        machine.onInFlightCountChange(0)

        advanceTimeBy(29_999L)
        assertEquals(BackgroundExtractionLifecycleState.KEEP_ALIVE, machine.state.value)

        advanceTimeBy(2L)
        assertEquals(BackgroundExtractionLifecycleState.DEMOTING, machine.state.value)
    }

    @Test
    fun `cold-start sweep with non-terminal entries promotes immediately`() = runTest {
        val machine = machine(this)
        // Simulating the cold-start sweep: the bus seeds non-terminal entries; the wiring
        // forwards the count delta to the machine.
        machine.onInFlightCountChange(3)
        advanceUntilIdle()

        assertEquals(BackgroundExtractionLifecycleState.PROMOTING, machine.state.value)
    }

    @Test
    fun `multi-entry queue stays FOREGROUND until the count drains to zero`() = runTest {
        val machine = machine(this)
        machine.onInFlightCountChange(1)
        machine.onForegroundStartConfirmed()
        machine.onInFlightCountChange(2)
        machine.onInFlightCountChange(3)
        machine.onInFlightCountChange(2)
        machine.onInFlightCountChange(1)

        assertEquals(BackgroundExtractionLifecycleState.FOREGROUND, machine.state.value)

        machine.onInFlightCountChange(0)
        assertEquals(BackgroundExtractionLifecycleState.KEEP_ALIVE, machine.state.value)
    }

    @Test
    fun `work arriving during DEMOTING re-promotes after the platform ack`() = runTest {
        val machine = machine(this)
        machine.onInFlightCountChange(1)
        machine.onForegroundStartConfirmed()
        machine.onInFlightCountChange(0)
        advanceTimeBy(30_001L)
        assertEquals(BackgroundExtractionLifecycleState.DEMOTING, machine.state.value)

        // Platform stop hasn't yet been ack'd; a new capture arrives during DEMOTING.
        machine.onInFlightCountChange(1)
        machine.onForegroundStopConfirmed()

        assertEquals(BackgroundExtractionLifecycleState.PROMOTING, machine.state.value)
    }

    @Test
    fun `negative count is rejected loudly`() = runTest {
        val machine = machine(this)
        assertThrows(IllegalArgumentException::class.java) {
            machine.onInFlightCountChange(-1)
        }
    }

    @Test
    fun `promote-then-drain-then-ack arms keep-alive immediately on the platform ack`() = runTest {
        val machine = machine(this)
        machine.onInFlightCountChange(1)
        machine.onInFlightCountChange(0)
        // KEEP_ALIVE was armed by the PROMOTING-with-zero branch; ack should land FOREGROUND
        // and immediately re-arm keep-alive since nothing is pending.
        machine.onForegroundStartConfirmed()

        assertEquals(BackgroundExtractionLifecycleState.KEEP_ALIVE, machine.state.value)
    }

    @Test
    fun `start failure resets PROMOTING to NORMAL then retries while work is still queued`() = runTest {
        val machine = machine(this, foregroundStartRetryDelay = 1.seconds)
        machine.onInFlightCountChange(1)
        assertEquals(BackgroundExtractionLifecycleState.PROMOTING, machine.state.value)

        machine.onForegroundStartFailed()
        assertEquals(BackgroundExtractionLifecycleState.NORMAL, machine.state.value)

        advanceTimeBy(1_001L)
        assertEquals(BackgroundExtractionLifecycleState.PROMOTING, machine.state.value)
    }

    @Test
    fun `draining the queue cancels a pending foreground-start retry`() = runTest {
        val machine = machine(this, foregroundStartRetryDelay = 1.seconds)
        machine.onInFlightCountChange(1)
        machine.onForegroundStartFailed()
        machine.onInFlightCountChange(0)

        advanceTimeBy(1_001L)
        assertEquals(BackgroundExtractionLifecycleState.NORMAL, machine.state.value)
    }

    @Test
    fun `start failure can reset PROMOTING without scheduling retry`() = runTest {
        var promoteCount = 0
        val machine = machine(this, foregroundStartRetryDelay = 1.seconds) { promoteCount += 1 }
        machine.onInFlightCountChange(1)

        machine.onForegroundStartFailed(retry = false)
        machine.onInFlightCountChange(2)
        advanceTimeBy(1_001L)

        assertEquals(BackgroundExtractionLifecycleState.NORMAL, machine.state.value)
        assertEquals(1, promoteCount)
    }

    @Test
    fun `foreground start suppression clears when queue drains`() = runTest {
        var promoteCount = 0
        val machine = machine(this, foregroundStartRetryDelay = 1.seconds) { promoteCount += 1 }
        machine.onInFlightCountChange(1)
        machine.onForegroundStartFailed(retry = false)

        machine.onInFlightCountChange(0)
        machine.onInFlightCountChange(1)

        assertEquals(BackgroundExtractionLifecycleState.PROMOTING, machine.state.value)
        assertEquals(2, promoteCount)
    }

    @Test
    fun `foreground start suppression retries when app returns to foreground`() = runTest {
        var promoteCount = 0
        val machine = machine(this, foregroundStartRetryDelay = 1.seconds) { promoteCount += 1 }
        machine.onInFlightCountChange(1)
        machine.onForegroundStartFailed(retry = false)

        machine.onInFlightCountChange(1, allowSuppressedPromotion = true)

        assertEquals(BackgroundExtractionLifecycleState.PROMOTING, machine.state.value)
        assertEquals(2, promoteCount)
    }

    @Test
    fun `onPromoteRequested fires on every PROMOTING transition including the DEMOTING bounce`() = runTest {
        var promoteCount = 0
        val machine = machine(this) { promoteCount += 1 }

        machine.onInFlightCountChange(1)
        assertEquals(1, promoteCount)

        machine.onForegroundStartConfirmed()
        machine.onInFlightCountChange(0)
        advanceTimeBy(30_001L)
        assertEquals(BackgroundExtractionLifecycleState.DEMOTING, machine.state.value)

        machine.onInFlightCountChange(1)
        machine.onForegroundStopConfirmed()

        assertEquals(BackgroundExtractionLifecycleState.PROMOTING, machine.state.value)
        assertEquals(2, promoteCount)
    }

    @Test
    fun `OS kill resets state and re-promotes if work is still queued`() = runTest {
        val machine = machine(this)
        machine.onInFlightCountChange(2)
        machine.onForegroundStartConfirmed()
        assertEquals(BackgroundExtractionLifecycleState.FOREGROUND, machine.state.value)

        machine.onServiceKilled()

        assertEquals(BackgroundExtractionLifecycleState.PROMOTING, machine.state.value)
    }

    @Test
    fun `OS kill with no queued work lands at NORMAL`() = runTest {
        val machine = machine(this)
        machine.onInFlightCountChange(1)
        machine.onForegroundStartConfirmed()
        machine.onInFlightCountChange(0)
        assertEquals(BackgroundExtractionLifecycleState.KEEP_ALIVE, machine.state.value)

        machine.onServiceKilled()

        assertEquals(BackgroundExtractionLifecycleState.NORMAL, machine.state.value)
    }

    @Test
    fun `late acks from non-target states are ignored`() = runTest {
        val machine = machine(this)
        machine.onForegroundStartConfirmed()
        assertEquals(BackgroundExtractionLifecycleState.NORMAL, machine.state.value)

        machine.onForegroundStopConfirmed()
        assertEquals(BackgroundExtractionLifecycleState.NORMAL, machine.state.value)
    }

    @Test
    fun `backgrounded suppression self-heals after the timed backoff with work still in flight`() = runTest {
        var promoteCount = 0
        val machine = machine(this, backgroundStartRetryDelay = 60.seconds) { promoteCount += 1 }
        machine.onInFlightCountChange(1)
        assertEquals(1, promoteCount)

        machine.onForegroundStartFailed(retry = false)
        assertEquals(BackgroundExtractionLifecycleState.NORMAL, machine.state.value)

        // While the app stays backgrounded the in-flight count never drops to zero, but a delayed
        // retry must still fire — otherwise a queued extraction is stranded for the rest of the run.
        machine.onInFlightCountChange(1)
        advanceTimeBy(60_001L)
        advanceUntilIdle()

        assertEquals(BackgroundExtractionLifecycleState.PROMOTING, machine.state.value)
        assertEquals(2, promoteCount)
    }
}
