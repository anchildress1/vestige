package dev.anchildress1.vestige.ui.onboarding

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import dev.anchildress1.vestige.model.Persona

/**
 * Onboarding completion flag + default persona. SharedPreferences-backed; durable across cold
 * starts and process kills. Every mutator uses `commit()` — apply()'s async queue can be lost
 * if the process is killed before the flush lands, which would silently replay onboarding in
 * the wrong state. Callers on the main thread must hop to [kotlinx.coroutines.Dispatchers.IO]
 * to keep these writes off the UI thread (StrictMode `detectDiskWrites`).
 *
 * Open to support a test fake that fails [markComplete] in isolation.
 */
open class OnboardingPrefs(private val prefs: SharedPreferences) {

    val isComplete: Boolean
        get() = prefs.getBoolean(KEY_COMPLETE, false)

    val defaultPersona: Persona
        get() = prefs.getString(KEY_PERSONA, null)
            ?.let { runCatching { Persona.valueOf(it) }.getOrNull() }
            ?: Persona.WITNESS

    val currentStep: OnboardingStep
        get() = prefs.getString(KEY_STEP, null)
            ?.let { runCatching { OnboardingStep.valueOf(it) }.getOrNull() }
            ?: OnboardingStep.PersonaPick

    fun setDefaultPersona(persona: Persona) {
        if (!prefs.edit().putString(KEY_PERSONA, persona.name).commit()) {
            Log.w(TAG, "setDefaultPersona($persona) did not flush — persona may revert on restart")
        }
    }

    fun setCurrentStep(step: OnboardingStep) {
        if (!prefs.edit().putString(KEY_STEP, step.name).commit()) {
            Log.w(TAG, "setCurrentStep($step) did not flush — resume point may be lost")
        }
    }

    /**
     * Returns true on successful flush. Synchronous commit because a missed flush replays the
     * entire flow on the next cold start; the caller can refuse to advance if it returns false.
     */
    open fun markComplete(): Boolean {
        val ok = prefs.edit()
            .putBoolean(KEY_COMPLETE, true)
            .remove(KEY_STEP)
            .commit()
        if (!ok) Log.w(TAG, "markComplete did not flush — onboarding will replay on next launch")
        return ok
    }

    /** Wipe onboarding state so Delete-all returns the user to the first-run flow. */
    open fun reset() {
        if (!prefs.edit().clear().commit()) {
            Log.w(TAG, "reset did not flush — onboarding state may survive a Delete-all")
        }
    }

    companion object {
        const val PREFS_NAME: String = "vestige.onboarding"
        private const val TAG = "OnboardingPrefs"
        private const val KEY_COMPLETE = "complete"
        private const val KEY_PERSONA = "default_persona"
        private const val KEY_STEP = "current_step"

        fun from(context: Context): OnboardingPrefs =
            OnboardingPrefs(context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE))
    }
}
