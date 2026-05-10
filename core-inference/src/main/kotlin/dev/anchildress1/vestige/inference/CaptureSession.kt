package dev.anchildress1.vestige.inference

import dev.anchildress1.vestige.model.Persona
import java.time.Clock

/**
 * Turn-by-turn state for one capture session per `phase-2-core-loop.md` §Story 2.1. A session is
 * one [Transcript] worth of conversation between the user and Gemma; it produces one saved entry
 * (Story 2.12) at end of session.
 *
 * State machine:
 * ```
 *   IDLE ──startRecording──▶ RECORDING ──submitForInference──▶ INFERRING
 *    ▲                                                              │
 *    │                                                              ▼
 *    └──acknowledgeResponse──── RESPONDED ◀──recordModelResponse── TRANSCRIBED
 *                                                                  ▲
 *                                                                  │
 *                                                    recordTranscription
 *
 *   any ──fail──▶ ERROR ──clearError──▶ IDLE
 * ```
 *
 * Illegal transitions throw `IllegalStateException`. Per `AGENTS.md` guardrail 11 the transcript
 * stores text only; no audio bytes flow through this class. Persona switching (Story 2.3) updates
 * [activePersona] for the next foreground call; prior turns retain whichever persona authored
 * them via [Turn.persona]. The foreground inference plumbing (Story 2.2) wraps this state machine
 * — it does not bypass it.
 */
class CaptureSession(private val clock: Clock = Clock.systemUTC(), defaultPersona: Persona = Persona.WITNESS) {

    enum class State { IDLE, RECORDING, INFERRING, TRANSCRIBED, RESPONDED, ERROR }

    val transcript: Transcript = Transcript()

    var state: State = State.IDLE
        private set

    var lastError: Throwable? = null
        private set

    /**
     * Active persona for the *next* foreground call (Story 2.3). Defaults to [Persona.WITNESS]
     * per `concept-locked.md` §Personas. Prior turns in [transcript] keep the persona that
     * authored them — switching does not rewrite history. Background extraction is persona-
     * agnostic per `AGENTS.md` guardrail 9, so this value never reaches the lens prompts.
     */
    var activePersona: Persona = defaultPersona
        private set

    /** IDLE → RECORDING. */
    fun startRecording() {
        requireState("startRecording", State.IDLE)
        state = State.RECORDING
    }

    /** RECORDING → INFERRING — caller has captured audio and dispatched it to the model. */
    fun submitForInference() {
        requireState("submitForInference", State.RECORDING)
        state = State.INFERRING
    }

    /** INFERRING → TRANSCRIBED. Appends the user's transcription before the follow-up renders. */
    fun recordTranscription(userText: String) {
        requireState("recordTranscription", State.INFERRING)
        transcript.append(Turn(Speaker.USER, userText, clock.instant()))
        state = State.TRANSCRIBED
    }

    /**
     * TRANSCRIBED → RESPONDED. Appends the model's follow-up after the user's transcription is
     * already visible in the transcript.
     */
    fun recordModelResponse(modelText: String, persona: Persona) {
        requireState("recordModelResponse", State.TRANSCRIBED)
        transcript.append(Turn(Speaker.MODEL, modelText, clock.instant(), persona))
        state = State.RESPONDED
    }

    /** RESPONDED → IDLE — UI has shown the response, ready for the next turn or save. */
    fun acknowledgeResponse() {
        requireState("acknowledgeResponse", State.RESPONDED)
        state = State.IDLE
    }

    /**
     * Any → ERROR. Stores [error] for the foreground UI to render and stops further transitions
     * until [clearError]. The transcript is preserved — error recovery does not lose history.
     */
    fun fail(error: Throwable) {
        lastError = error
        state = State.ERROR
    }

    /** ERROR → IDLE. Clears [lastError]; transcript is untouched so the user can retry. */
    fun clearError() {
        requireState("clearError", State.ERROR)
        lastError = null
        state = State.IDLE
    }

    /**
     * Update [activePersona] for subsequent foreground calls. State-independent — callers may
     * switch persona between turns or while idle. Mid-call switches do not affect the in-flight
     * inference: the foreground call carries the persona the caller passed into it, and the
     * resulting [Turn] records that persona on the transcript regardless of [activePersona].
     */
    fun setPersona(persona: Persona) {
        activePersona = persona
    }

    private fun requireState(action: String, vararg allowed: State) {
        check(state in allowed) {
            "Illegal CaptureSession transition: $action requires state in ${allowed.toList()}, was $state"
        }
    }
}
