package dev.anchildress1.vestige

import android.app.Application
import android.content.pm.ApplicationInfo
import android.os.StrictMode
import dev.anchildress1.vestige.lifecycle.LocalProcessingNotification

/** Application entry point: wires StrictMode (debug only), AppContainer, and notification channel. */
open class VestigeApplication : Application() {

    lateinit var appContainer: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        // Container first: if construction throws, StrictMode and the notification channel stay
        // unarmed so Robolectric's reused process doesn't inherit half-installed global state.
        appContainer = createAppContainer()
        installStrictModeOnDebugBuilds()
        LocalProcessingNotification.registerChannel(this)
        appContainer.launchVectorBackfillIfReady()
    }

    /** Test seam: subclasses inject in-memory factories. Production has no reason to override. */
    protected open fun createAppContainer(): AppContainer = AppContainer(this)

    private fun installStrictModeOnDebugBuilds() {
        val isDebuggable = (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
        if (!isDebuggable) return

        // Network violations throw per ADR-001 §Q7 ("a violation throws and fails the build's
        // instrumented tests"). Disk violations only log — Compose + ObjectBox unavoidably touch
        // disk during init, and those reads aren't a privacy concern.
        StrictMode.setThreadPolicy(
            StrictMode.ThreadPolicy.Builder()
                .detectNetwork()
                .detectDiskReads()
                .detectDiskWrites()
                .penaltyLog()
                .penaltyDeathOnNetwork()
                .build(),
        )
        StrictMode.setVmPolicy(
            StrictMode.VmPolicy.Builder()
                .detectLeakedClosableObjects()
                .detectLeakedRegistrationObjects()
                .penaltyLog()
                .build(),
        )
    }
}
