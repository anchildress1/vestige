package dev.anchildress1.vestige.inference

import dev.anchildress1.vestige.model.Persona
import java.time.Instant

enum class Speaker { USER, MODEL }

/** One transcript entry — text only, no audio bytes. [timestamp] is when this entered the transcript. */
data class Turn(val speaker: Speaker, val text: String, val timestamp: Instant, val persona: Persona? = null) {
    init {
        when (speaker) {
            Speaker.USER -> require(persona == null) { "USER turns cannot carry a persona" }
            Speaker.MODEL -> require(persona != null) { "MODEL turns must record their persona" }
        }
    }
}

/**
 * Append-only ordered log of [Turn]s for one capture. `entry_text` derives from USER turns only.
 * Reads return a snapshot; writes go through [CaptureSession] so state stays synchronized.
 */
class Transcript {
    private val mutableTurns = mutableListOf<Turn>()

    val turns: List<Turn> get() = mutableTurns.toList()

    val size: Int get() = mutableTurns.size

    fun isEmpty(): Boolean = mutableTurns.isEmpty()

    fun entryText(): String = mutableTurns
        .asSequence()
        .filter { it.speaker == Speaker.USER }
        .map(Turn::text)
        .joinToString(separator = "\n\n")

    internal fun append(turn: Turn) {
        mutableTurns += turn
    }
}
