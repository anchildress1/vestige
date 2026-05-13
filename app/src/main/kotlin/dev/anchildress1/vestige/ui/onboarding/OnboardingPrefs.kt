package dev.anchildress1.vestige.ui.onboarding

import android.content.Context
import android.content.SharedPreferences
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

    fun setDefaultPersona(persona: Persona) {
        prefs.edit().putString(KEY_PERSONA, persona.name).apply()
    }

    fun setCurrentStep(step: OnboardingStep) {
        prefs.edit().putString(KEY_STEP, step.name).commit()
    }

    // Synchronous commit — one-shot gate where a missed disk flush would replay the entire flow.
    fun markComplete() {
        prefs.edit()
            .putBoolean(KEY_COMPLETE, true)
            .remove(KEY_STEP)
            .commit()
    }

    companion object {
        const val PREFS_NAME: String = "vestige.onboarding"
        private const val KEY_COMPLETE = "complete"
        private const val KEY_PERSONA = "default_persona"
        private const val KEY_STEP = "current_step"

        fun from(context: Context): OnboardingPrefs =
            OnboardingPrefs(context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE))
    }
}
