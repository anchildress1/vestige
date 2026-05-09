package dev.anchildress1.vestige

import android.app.Application
import android.content.pm.ApplicationInfo
import android.os.StrictMode

/**
 * Application entry point. Phase 1 wires the dev-build StrictMode policy from ADR-001 §Q7 so
 * accidental main-thread network or disk I/O surfaces during development. Production builds
 * skip StrictMode so a release crash stays an actual product defect, not a penalty death.
 */
class VestigeApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        installStrictModeOnDebugBuilds()
    }

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
