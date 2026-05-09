package dev.anchildress1.vestige.inference

import dev.anchildress1.vestige.model.Persona
import java.time.Instant

/**
 * Who produced the [Turn]. `USER` turns carry Gemma's transcription of the user's spoken audio;
 * `MODEL` turns carry the follow-up response, with the [Turn.persona] that produced them per
 * `concept-locked.md` §Personas. There is no third speaker — personas are a property of the
 * model turn, not a separate participant.
 */
enum class Speaker { USER, MODEL }

/**
 * One contribution inside a [Transcript]. Per `AGENTS.md` guardrail 11 the payload is text only
 * — audio bytes are not persisted on a `Turn` or anywhere else durably. Audio still flows through
 * memory (and a short-lived temp WAV when LiteRT-LM needs `Content.AudioFile`) per Story 1.4 /
 * Story 1.5; that is transient by design and discarded inside the same call.
 *
 * [timestamp] is when the turn entered the transcript (post-transcription for `USER`, post-response
 * for `MODEL`), not when audio capture began.
 */
data class Turn(val speaker: Speaker, val text: String, val timestamp: Instant, val persona: Persona? = null) {
    init {
        when (speaker) {
            Speaker.USER -> require(persona == null) { "USER turns cannot carry a persona" }
            Speaker.MODEL -> require(persona != null) { "MODEL turns must record their persona" }
        }
    }
}

/**
 * Append-only ordered log of [Turn]s for a single capture session. One [CaptureSession] owns one
 * `Transcript`; `entry_text` is derived from the ordered USER turns only, never from model output.
 *
 * Read access returns a snapshot — callers cannot mutate the underlying list. Writes go through
 * `CaptureSession`'s state-machine methods so transcript advances stay synchronized with state.
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
