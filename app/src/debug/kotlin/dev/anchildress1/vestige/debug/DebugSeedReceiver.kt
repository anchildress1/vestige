package dev.anchildress1.vestige.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import dev.anchildress1.vestige.VestigeApplication

/**
 * ADB-triggered fixture seeder for local dev iteration.
 *
 * Fire via:
 *   adb shell am broadcast -n dev.anchildress1.vestige/dev.anchildress1.vestige.debug.DebugSeedReceiver
 *
 * Registered in the debug manifest overlay only — never ships in release builds.
 * Delegates to [DebugPatternSeeder] which is idempotent (clears before seeding).
 */
class DebugSeedReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext as? VestigeApplication
        if (app == null) {
            Log.e(TAG, "applicationContext is not VestigeApplication — seed skipped")
            return
        }
        val container = app.appContainer
        Log.d(TAG, "seeding debug fixtures…")
        DebugPatternSeeder.seed(
            filesDir = context.filesDir,
            boxStore = container.boxStore,
            patternStore = container.patternStore,
        )
        Log.d(TAG, "seed complete")
    }

    private companion object {
        private const val TAG = "DebugSeedReceiver"
    }
}
