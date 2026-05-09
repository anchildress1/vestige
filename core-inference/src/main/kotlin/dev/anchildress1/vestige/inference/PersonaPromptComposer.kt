package dev.anchildress1.vestige.inference

import dev.anchildress1.vestige.model.Persona

/**
 * Builds the system prompt for a foreground capture call by composing the shared cognition-
 * tracker rules with one of three persona-specific tone wrappers (per `concept-locked.md`
 * §Personas — tone-only variants).
 *
 * Persona text lives as classpath resources at `personas/{slug}.txt` so the prompts can be
 * iterated on without recompiling Kotlin and so the linter doesn't carry multi-page string
 * literals. Phase 1 ships the scaffold; Phase 2 may refine the wording in place.
 */
object PersonaPromptComposer {

    private const val SHARED_RESOURCE = "/personas/shared.txt"
    private const val WITNESS_RESOURCE = "/personas/witness.txt"
    private const val HARDASS_RESOURCE = "/personas/hardass.txt"
    private const val EDITOR_RESOURCE = "/personas/editor.txt"

    /**
     * Returns the assembled system prompt for [persona]. Shared rules first, persona-specific
     * tone wrapper second. Stable string — callers can cache.
     */
    fun compose(persona: Persona): String {
        val shared = loadResource(SHARED_RESOURCE)
        val personaText = loadResource(resourceFor(persona))
        return buildString {
            append(shared.trimEnd())
            append("\n\n")
            append(personaText.trimEnd())
            append('\n')
        }
    }

    private fun resourceFor(persona: Persona): String = when (persona) {
        Persona.WITNESS -> WITNESS_RESOURCE
        Persona.HARDASS -> HARDASS_RESOURCE
        Persona.EDITOR -> EDITOR_RESOURCE
    }

    private fun loadResource(path: String): String {
        val stream = PersonaPromptComposer::class.java.getResourceAsStream(path)
            ?: error("Persona resource missing: $path")
        return stream.bufferedReader(Charsets.UTF_8).use { it.readText() }
    }
}
