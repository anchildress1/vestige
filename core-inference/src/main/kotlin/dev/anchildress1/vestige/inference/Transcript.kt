package dev.anchildress1.vestige.inference

import java.time.Instant

/**
 * Who produced the [Turn]. `USER` turns carry Gemma's transcription of the user's spoken audio;
 * `MODEL` turns carry the follow-up response. There is no third party — `concept-locked.md`
 * §Personas keeps tone variants on the model side, not on the speaker enum.
 */
enum class Speaker { USER, MODEL }

/**
 * One contribution inside a [Transcript]. Per `AGENTS.md` guardrail 11 the payload is text only
 * — audio bytes are never persisted on a turn (or anywhere else), even transiently.
 *
 * [timestamp] is when the turn entered the transcript (post-transcription for `USER`, post-response
 * for `MODEL`), not when audio capture began.
 */
data class Turn(val speaker: Speaker, val text: String, val timestamp: Instant)

/**
 * Append-only ordered log of [Turn]s for a single capture session. One [CaptureSession] owns one
 * `Transcript`; the joined text becomes the entry's `entry_text` at save time (Story 2.12).
 *
 * Read access returns a snapshot — callers cannot mutate the underlying list. Writes go through
 * `CaptureSession`'s state-machine methods so transcript advances stay synchronized with state.
 */
class Transcript {
    private val mutableTurns = mutableListOf<Turn>()

    val turns: List<Turn> get() = mutableTurns.toList()

    val size: Int get() = mutableTurns.size

    fun isEmpty(): Boolean = mutableTurns.isEmpty()

    internal fun append(turn: Turn) {
        mutableTurns += turn
    }
}
