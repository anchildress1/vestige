package dev.anchildress1.vestige.lifecycle

import dev.anchildress1.vestige.model.ExtractionStatus
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class BackgroundExtractionStatusBusTest {

    @Test
    fun `pending and running entries count as in-flight, terminal flips drop them`() {
        val bus = BackgroundExtractionStatusBus()

        bus.report(entryId = 1L, status = ExtractionStatus.PENDING)
        bus.report(entryId = 2L, status = ExtractionStatus.RUNNING)
        assertEquals(2, bus.inFlightCount.value)

        bus.report(entryId = 1L, status = ExtractionStatus.COMPLETED)
        bus.report(entryId = 2L, status = ExtractionStatus.FAILED)
        assertEquals(0, bus.inFlightCount.value)
    }

    @Test
    fun `re-reporting the same entry does not double-count`() {
        val bus = BackgroundExtractionStatusBus()

        bus.report(entryId = 5L, status = ExtractionStatus.PENDING)
        bus.report(entryId = 5L, status = ExtractionStatus.RUNNING)
        bus.report(entryId = 5L, status = ExtractionStatus.RUNNING)
        assertEquals(1, bus.inFlightCount.value)
    }

    @Test
    fun `seedFromColdStart replaces tracked set with recovered ids`() {
        val bus = BackgroundExtractionStatusBus()
        bus.report(entryId = 1L, status = ExtractionStatus.RUNNING)

        bus.seedFromColdStart(listOf(10L, 11L, 12L))

        assertEquals(3, bus.inFlightCount.value)
    }

    @Test
    fun `terminal status for an unknown entry is a no-op`() {
        val bus = BackgroundExtractionStatusBus()

        bus.report(entryId = 99L, status = ExtractionStatus.COMPLETED)

        assertEquals(0, bus.inFlightCount.value)
    }

    @Test
    fun `timed-out also drains the in-flight count`() {
        val bus = BackgroundExtractionStatusBus()
        bus.report(entryId = 7L, status = ExtractionStatus.RUNNING)

        bus.report(entryId = 7L, status = ExtractionStatus.TIMED_OUT)

        assertEquals(0, bus.inFlightCount.value)
    }
}
