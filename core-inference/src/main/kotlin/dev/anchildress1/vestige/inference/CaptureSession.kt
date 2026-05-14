package dev.anchildress1.vestige.inference

import dev.anchildress1.vestige.model.Persona
import java.time.Clock

/**
 * Single-use turn-by-turn state for one capture. After RESPONDED, DISCARDED, or ERROR the
 * instance is terminal — the next recording requires a fresh session.
 *
 * ```
 *   IDLE ──startRecording──▶ RECORDING ──submitForInference──▶ INFERRING
 *                                │                                 │
 *                                │ discard                         ▼
 *                                ▼          RESPONDED ◀──recordModelResponse── TRANSCRIBED
 *                            DISCARDED
 *
 *   any ──fail──▶ ERROR
 * ```
 *
 * Illegal transitions throw. The transcript stores text only — no audio bytes. `DISCARDED` is
 * the user-initiated cancel path per ADR-001 §Q8 — reachable only from `RECORDING`, terminal,
 * persists nothing, no `Undo`.
 */
class CaptureSession(private val clock: Clock = Clock.systemUTC(), defaultPersona: Persona = Persona.WITNESS) {

    enum class State { IDLE, RECORDING, INFERRING, TRANSCRIBED, RESPONDED, DISCARDED, ERROR }

    val transcript: Transcript = Transcript()

    var state: State = State.IDLE
        private set

    var lastError: Throwable? = null
        private set

    /** Active persona for the upcoming foreground call. Background extraction is persona-agnostic. */
    var activePersona: Persona = defaultPersona
        private set

    fun startRecording() {
        requireState("startRecording", State.IDLE)
        state = State.RECORDING
    }

    fun submitForInference() {
        requireState("submitForInference", State.RECORDING)
        state = State.INFERRING
    }

    fun recordTranscription(userText: String) {
        requireState("recordTranscription", State.INFERRING)
        transcript.append(Turn(Speaker.USER, userText, clock.instant()))
        state = State.TRANSCRIBED
    }

    fun recordModelResponse(modelText: String, persona: Persona) {
        requireState("recordModelResponse", State.TRANSCRIBED)
        transcript.append(Turn(Speaker.MODEL, modelText, clock.instant(), persona))
        state = State.RESPONDED
    }

    fun discard() {
        requireState("discard", State.RECORDING)
        state = State.DISCARDED
    }

    fun fail(error: Throwable) {
        lastError = error
        state = State.ERROR
    }

    // The Turn.persona comes from recordModelResponse's explicit param, not from this — setting
    // the default here doesn't retroactively re-tag.
    fun setPersona(persona: Persona) {
        activePersona = persona
    }

    private fun requireState(action: String, vararg allowed: State) {
        check(state in allowed) {
            "Illegal CaptureSession transition: $action requires state in ${allowed.toList()}, was $state"
        }
    }
}
