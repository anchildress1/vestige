package dev.anchildress1.vestige

import android.content.Context
import android.content.Intent
import dev.anchildress1.vestige.lifecycle.BackgroundExtractionLifecycleState
import dev.anchildress1.vestige.model.ExtractionStatus
import dev.anchildress1.vestige.storage.MarkdownEntryStore
import io.mockk.mockk
import io.objectbox.BoxStore
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AppContainerTest {

    @Test
    fun `cold-start recovery seeds recovered entries before service promotion`() = runTest {
        var serviceStarts = 0
        val container = AppContainer(
            applicationContext = mockk<Context>(relaxed = true),
            boxStoreFactory = { mockk<BoxStore>(relaxed = true) },
            markdownStoreFactory = { mockk<MarkdownEntryStore>(relaxed = true) },
            recoveredEntryIdsLoader = { listOf(11L, 12L) },
            foregroundServiceIntentFactory = { Intent("dev.anchildress1.vestige.TEST_START") },
            foregroundServiceStarter = { serviceStarts += 1 },
            scope = backgroundScope,
        )

        advanceUntilIdle()

        assertEquals(BackgroundExtractionLifecycleState.PROMOTING, container.lifecycleStateMachine.state.value)
        assertEquals(1, serviceStarts)
    }

    @Test
    fun `startForegroundService rejection schedules a retry for the active extraction`() = runTest {
        var serviceStarts = 0
        val container = AppContainer(
            applicationContext = mockk<Context>(relaxed = true),
            boxStoreFactory = { mockk<BoxStore>(relaxed = true) },
            markdownStoreFactory = { mockk<MarkdownEntryStore>(relaxed = true) },
            recoveredEntryIdsLoader = { emptyList() },
            foregroundServiceIntentFactory = { Intent("dev.anchildress1.vestige.TEST_START") },
            foregroundServiceStarter = {
                serviceStarts += 1
                if (serviceStarts == 1) error("background-start denied")
            },
            scope = backgroundScope,
        )

        container.reportExtractionStatus(entryId = 7L, status = ExtractionStatus.RUNNING)
        assertEquals(BackgroundExtractionLifecycleState.NORMAL, container.lifecycleStateMachine.state.value)

        advanceTimeBy(5_001L)
        advanceUntilIdle()

        assertEquals(2, serviceStarts)
        assertEquals(BackgroundExtractionLifecycleState.PROMOTING, container.lifecycleStateMachine.state.value)
    }

    @Test
    fun `work arriving during DEMOTING re-dispatches the foreground service after the platform ack`() = runTest {
        var serviceStarts = 0
        val container = AppContainer(
            applicationContext = mockk<Context>(relaxed = true),
            boxStoreFactory = { mockk<BoxStore>(relaxed = true) },
            markdownStoreFactory = { mockk<MarkdownEntryStore>(relaxed = true) },
            recoveredEntryIdsLoader = { emptyList() },
            foregroundServiceIntentFactory = { Intent("dev.anchildress1.vestige.TEST_START") },
            foregroundServiceStarter = { serviceStarts += 1 },
            scope = backgroundScope,
        )

        container.reportExtractionStatus(entryId = 1L, status = ExtractionStatus.RUNNING)
        container.lifecycleStateMachine.onForegroundStartConfirmed()
        container.reportExtractionStatus(entryId = 1L, status = ExtractionStatus.COMPLETED)
        advanceTimeBy(30_001L)
        assertEquals(BackgroundExtractionLifecycleState.DEMOTING, container.lifecycleStateMachine.state.value)
        assertEquals(1, serviceStarts)

        // New capture lands while we're awaiting the platform stop ack.
        container.reportExtractionStatus(entryId = 2L, status = ExtractionStatus.RUNNING)
        container.lifecycleStateMachine.onForegroundStopConfirmed()
        advanceUntilIdle()

        assertEquals(BackgroundExtractionLifecycleState.PROMOTING, container.lifecycleStateMachine.state.value)
        assertEquals(2, serviceStarts)
    }

    @Test
    fun `extractionStatusListener forwards worker updates into the lifecycle machine`() = runTest {
        val container = AppContainer(
            applicationContext = mockk<Context>(relaxed = true),
            boxStoreFactory = { mockk<BoxStore>(relaxed = true) },
            markdownStoreFactory = { mockk<MarkdownEntryStore>(relaxed = true) },
            recoveredEntryIdsLoader = { emptyList() },
            foregroundServiceIntentFactory = { Intent("dev.anchildress1.vestige.TEST_START") },
            foregroundServiceStarter = {},
            scope = backgroundScope,
        )

        val listener = container.extractionStatusListener(entryId = 42L)
        listener.onUpdate(ExtractionStatus.RUNNING, entryAttemptCount = 0, lastError = null)
        assertEquals(BackgroundExtractionLifecycleState.PROMOTING, container.lifecycleStateMachine.state.value)

        container.lifecycleStateMachine.onForegroundStartConfirmed()
        listener.onUpdate(ExtractionStatus.COMPLETED, entryAttemptCount = 0, lastError = null)

        assertEquals(BackgroundExtractionLifecycleState.KEEP_ALIVE, container.lifecycleStateMachine.state.value)
    }
}
