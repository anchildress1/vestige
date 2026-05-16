package dev.anchildress1.vestige.lifecycle

import dev.anchildress1.vestige.model.ExtractionStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** Tracks the count of entries with non-terminal `extraction_status`; drives service promote/demote. */
class BackgroundExtractionStatusBus {

    private val tracked: MutableMap<Long, ExtractionStatus> = mutableMapOf()
    private val countState: MutableStateFlow<Int> = MutableStateFlow(0)

    val inFlightCount: StateFlow<Int> = countState.asStateFlow()

    @Synchronized
    fun report(entryId: Long, status: ExtractionStatus) {
        if (status.isTerminal()) {
            tracked.remove(entryId)
        } else {
            tracked[entryId] = status
        }
        countState.update { tracked.size }
    }

    @Synchronized
    fun seedFromColdStart(nonTerminalEntryIds: Collection<Long>) {
        tracked.clear()
        nonTerminalEntryIds.forEach { tracked[it] = ExtractionStatus.PENDING }
        countState.update { tracked.size }
    }

    @Synchronized
    fun clear() {
        tracked.clear()
        countState.update { 0 }
    }

    private fun ExtractionStatus.isTerminal(): Boolean = when (this) {
        ExtractionStatus.COMPLETED, ExtractionStatus.TIMED_OUT, ExtractionStatus.FAILED -> true
        ExtractionStatus.PENDING, ExtractionStatus.RUNNING -> false
    }
}
