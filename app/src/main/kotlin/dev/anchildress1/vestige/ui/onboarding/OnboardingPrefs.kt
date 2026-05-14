package dev.anchildress1.vestige.ui.onboarding

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import dev.anchildress1.vestige.model.Persona

/** Onboarding completion flag + default persona. SharedPreferences-backed; durable across cold starts. */
class OnboardingPrefs(private val prefs: SharedPreferences) {

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

    /** Returns true on successful flush. Persona must survive process death between steps. */
    fun setDefaultPersona(persona: Persona): Boolean {
        val ok = prefs.edit().putString(KEY_PERSONA, persona.name).commit()
        if (!ok) Log.w(TAG, "setDefaultPersona($persona) did not flush — persona may revert on restart")
        return ok
    }

    /** Returns true on successful flush. Caller decides whether a missed write is worth surfacing. */
    fun setCurrentStep(step: OnboardingStep): Boolean {
        val ok = prefs.edit().putString(KEY_STEP, step.name).commit()
        if (!ok) Log.w(TAG, "setCurrentStep($step) did not flush — resume point may be lost")
        return ok
    }

    /**
     * Returns true on successful flush. Synchronous commit because a missed flush replays the
     * entire flow on the next cold start; the caller can refuse to advance if it returns false.
     */
    fun markComplete(): Boolean {
        val ok = prefs.edit()
            .putBoolean(KEY_COMPLETE, true)
            .remove(KEY_STEP)
            .commit()
        if (!ok) Log.w(TAG, "markComplete did not flush — onboarding will replay on next launch")
        return ok
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
