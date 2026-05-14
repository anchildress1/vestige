package dev.anchildress1.vestige.lifecycle

import android.app.Application
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import dev.anchildress1.vestige.AppContainer
import dev.anchildress1.vestige.TestVestigeApplication
import dev.anchildress1.vestige.VestigeApplication
import dev.anchildress1.vestige.model.ExtractionStatus
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ServiceController
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowApplication

/**
 * Service ↔ machine handshake coverage per ADR-007 §"Test coverage contract". The state-machine
 * unit tests cover the transition table; this test exercises the actual `Service` lifecycle
 * (`onCreate` / `onStartCommand` / `onDestroy`) against a real [AppContainer]. Catches the
 * "unit tests pass, the Android lifecycle disagrees" class of bug.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = TestVestigeApplication::class)
class BackgroundExtractionServiceIntegrationTest {

    private lateinit var app: VestigeApplication
    private lateinit var shadowApp: ShadowApplication
    private lateinit var serviceController: ServiceController<BackgroundExtractionService>

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext<Application>() as VestigeApplication
        shadowApp = shadowOf(app)
        serviceController = Robolectric.buildService(BackgroundExtractionService::class.java)
    }

    @After
    fun tearDown() {
        runCatching { serviceController.destroy() }
        (app as? TestVestigeApplication)?.releaseTempStorage()
    }

    @Test
    fun `onStartCommand with state PROMOTING starts foreground and confirms the machine`() {
        val machine = app.appContainer.lifecycleStateMachine
        app.appContainer.reportExtractionStatus(entryId = 1L, status = ExtractionStatus.RUNNING)
        assertEquals(BackgroundExtractionLifecycleState.PROMOTING, machine.state.value)

        serviceController.create().startCommand(0, 0)

        assertEquals(BackgroundExtractionLifecycleState.FOREGROUND, machine.state.value)
    }

    @Test
    fun `onStartCommand with stale intent (state not PROMOTING) stops self without touching the machine`() {
        val machine = app.appContainer.lifecycleStateMachine
        assertEquals(BackgroundExtractionLifecycleState.NORMAL, machine.state.value)

        serviceController.create().startCommand(0, 0)

        assertEquals(BackgroundExtractionLifecycleState.NORMAL, machine.state.value)
        assertTrue(
            "service must call stopSelf when state is not PROMOTING",
            shadowOf(serviceController.get()).isStoppedBySelf,
        )
    }

    @Test
    fun `OS-only kill triggers onServiceKilled and re-promotes if work remains`() {
        val machine = app.appContainer.lifecycleStateMachine
        app.appContainer.reportExtractionStatus(entryId = 5L, status = ExtractionStatus.RUNNING)
        serviceController.create().startCommand(0, 0)
        assertEquals(BackgroundExtractionLifecycleState.FOREGROUND, machine.state.value)

        // Simulate OS-only kill: destroy the service without it self-initiating shutdown.
        serviceController.destroy()

        assertEquals(BackgroundExtractionLifecycleState.PROMOTING, machine.state.value)
    }

    @Test
    fun `late-start race — state drained to KEEP_ALIVE before onStartCommand stays alive`() {
        val machine = app.appContainer.lifecycleStateMachine

        // Production race: AppContainer dispatches startForegroundService while state is PROMOTING.
        // Before Android schedules onCreate / onStartCommand, the worker reports COMPLETED, which
        // drains the in-flight count and transitions PROMOTING → KEEP_ALIVE. The service must
        // (a) satisfy the OS startForeground deadline and (b) stay alive so the keep-alive timer
        // can transition KEEP_ALIVE → DEMOTING with a live collector to ack it.
        app.appContainer.reportExtractionStatus(entryId = 11L, status = ExtractionStatus.RUNNING)
        app.appContainer.reportExtractionStatus(entryId = 11L, status = ExtractionStatus.COMPLETED)
        assertEquals(BackgroundExtractionLifecycleState.KEEP_ALIVE, machine.state.value)

        serviceController.create().startCommand(0, 0)

        assertEquals(
            "machine must remain in KEEP_ALIVE — keep-alive timer still owns the demote transition",
            BackgroundExtractionLifecycleState.KEEP_ALIVE,
            machine.state.value,
        )
        assertTrue(
            "service must remain alive to handle the eventual DEMOTING transition",
            !shadowOf(serviceController.get()).isStoppedBySelf,
        )
    }

    @Test
    fun `late-start race — already-FOREGROUND on onStartCommand keeps the service alive without re-acking`() {
        val machine = app.appContainer.lifecycleStateMachine
        app.appContainer.reportExtractionStatus(entryId = 21L, status = ExtractionStatus.RUNNING)
        // Simulate ack already arriving via a parallel path: state is FOREGROUND when the
        // dispatched intent finally lands.
        machine.onForegroundStartConfirmed()
        assertEquals(BackgroundExtractionLifecycleState.FOREGROUND, machine.state.value)

        serviceController.create().startCommand(0, 0)

        assertEquals(BackgroundExtractionLifecycleState.FOREGROUND, machine.state.value)
        assertTrue(!shadowOf(serviceController.get()).isStoppedBySelf)
    }

    @Test
    fun `dispatch fires startForegroundService on the application context`() {
        // The plumbing test: AppContainer's promote callback must produce a real intent the OS
        // can route. The default `foregroundServiceStarter` calls `applicationContext.startForegroundService`,
        // so a PROMOTING transition must enqueue a service intent on the shadow application.
        shadowApp.clearStartedServices()
        app.appContainer.reportExtractionStatus(entryId = 9L, status = ExtractionStatus.RUNNING)

        val started: Intent? = shadowApp.peekNextStartedService()
        assertEquals(
            BackgroundExtractionService::class.java.name,
            started?.component?.className,
        )
    }
}
