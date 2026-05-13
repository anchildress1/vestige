package dev.anchildress1.vestige.ui.onboarding

import android.content.Context
import android.content.SharedPreferences
import dev.anchildress1.vestige.model.Persona

/**
 * Onboarding completion + default-persona persistence (Story 4.2). Backed by
 * SharedPreferences so the gate survives process death and uninstall-reinstall reuses no state.
 * Two flags only — anything more elaborate belongs in Settings (Story 4.9), not here.
 */
class OnboardingPrefs(private val prefs: SharedPreferences) {

    val isComplete: Boolean
        get() = prefs.getBoolean(KEY_COMPLETE, false)

    /** Default persona selected during onboarding. Witness when unset. */
    val defaultPersona: Persona
        get() = prefs.getString(KEY_PERSONA, null)
            ?.let { runCatching { Persona.valueOf(it) }.getOrNull() }
            ?: Persona.WITNESS

    fun setDefaultPersona(persona: Persona) {
        prefs.edit().putString(KEY_PERSONA, persona.name).apply()
    }

    /** Marks onboarding as complete. Next launch skips straight to the post-onboarding shell. */
    fun markComplete() {
        prefs.edit().putBoolean(KEY_COMPLETE, true).apply()
    }

    companion object {
        const val PREFS_NAME: String = "vestige.onboarding"
        private const val KEY_COMPLETE = "complete"
        private const val KEY_PERSONA = "default_persona"

        fun from(context: Context): OnboardingPrefs =
            OnboardingPrefs(context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE))
    }
}
