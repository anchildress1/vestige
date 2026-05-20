package dev.anchildress1.vestige.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import dev.anchildress1.vestige.VestigeApplication
import dev.anchildress1.vestige.ui.onboarding.OnboardingPrefs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * ADB-triggered fixture seeder for local dev iteration.
 *
 * Fire via:
 *   adb shell am broadcast -n dev.anchildress1.vestige/dev.anchildress1.vestige.debug.DebugSeedReceiver
 *   adb shell am broadcast --ez run_extraction true -n ...
 *
 * Registered in the debug manifest overlay only — never ships in release builds.
 * Delegates to [DebugPatternSeeder] which is idempotent (clears before seeding).
 */
class DebugSeedReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val pendingResult = goAsync()
        val appContext = context.applicationContext
        val runExtraction = intent.getBooleanExtra(EXTRA_RUN_EXTRACTION, false)
        seedScope.launch {
            try {
                val app = appContext as? VestigeApplication
                if (app == null) {
                    Log.e(TAG, "applicationContext is not VestigeApplication — seed skipped")
                    return@launch
                }
                val container = app.appContainer
                Log.d(TAG, "seeding debug fixtures…")
                DebugPatternSeeder.seed(
                    filesDir = appContext.filesDir,
                    boxStore = container.boxStore,
                    patternStore = container.patternStore,
                )
                OnboardingPrefs.from(appContext).markComplete()
                if (runExtraction) {
                    container.launchMissingExtractionBackfill()
                    Log.d(TAG, "queued missing extraction backfill")
                }
                Log.d(TAG, "seed complete")
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (@Suppress("TooGenericExceptionCaught") error: Throwable) {
                // Without this catch the launch swallows the throw and pendingResult.finish() in
                // `finally` reports success to ADB. Devs would then chase phantom "why isn't the
                // demo data here" failures on the next reinstall.
                Log.e(TAG, "Debug seed FAILED", error)
            } finally {
                pendingResult.finish()
            }
        }
    }

    private companion object {
        private const val TAG = "DebugSeedReceiver"
        private const val EXTRA_RUN_EXTRACTION = "run_extraction"
        private val seedScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }
}
