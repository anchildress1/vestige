package dev.anchildress1.vestige.inference

import dev.anchildress1.vestige.model.Persona

/**
 * Foreground system prompt = shared cognition-tracker rules + a persona tone wrapper. Personas
 * are tone-only — they never alter extraction. Text lives under `resources/personas/`.
 */
object PersonaPromptComposer {

    private const val SHARED_RESOURCE = "/personas/shared.txt"
    private const val WITNESS_RESOURCE = "/personas/witness.txt"
    private const val HARDASS_RESOURCE = "/personas/hardass.txt"
    private const val EDITOR_RESOURCE = "/personas/editor.txt"

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
