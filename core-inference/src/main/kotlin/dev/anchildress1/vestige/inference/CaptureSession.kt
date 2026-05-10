package dev.anchildress1.vestige.inference

import dev.anchildress1.vestige.model.Persona
import java.time.Clock

/**
 * Single-use turn-by-turn state for one capture per `phase-2-core-loop.md` §Story 2.1 (v1 single-
 * turn scope per the STT-B test boundary — see `adrs/ADR-002-multi-lens-extraction-pattern.md`
 * §"Multi-turn behavior"). One [CaptureSession] models one entry's worth of recording-and-response:
 * the user records, Gemma transcribes + answers, the entry saves, done. Subsequent recordings
 * construct a fresh [CaptureSession] — the v1 lifecycle never loops back for a second turn. The
 * STT-B prompt-stuffing pattern was tested and produced retention=0.0; the LiteRT-LM SDK's
 * stateful Conversation path was not measured. v1 ships single-use sessions for simplicity, not
 * because multi-turn was conclusively impossible.
 *
 * State machine:
 * ```
 *   IDLE ──startRecording──▶ RECORDING ──submitForInference──▶ INFERRING
 *                                                                  │
 *                                                                  ▼
 *                                          RESPONDED ◀──recordModelResponse── TRANSCRIBED
 *                                          ▲                                  ▲
 *                                          │                                  │
 *                                          │                       recordTranscription
 *                                          │
 *                              (terminal — no transition out)
 *
 *   any ──fail──▶ ERROR (terminal)
 * ```
 *
 * Illegal transitions throw `IllegalStateException`. Per `AGENTS.md` guardrail 11 the transcript
 * stores text only; no audio bytes flow through this class. Persona selection (Story 2.3) updates
 * [activePersona] for the foreground call; the recorded [Turn] permanently carries whichever
 * persona authored it.
 */
class CaptureSession(private val clock: Clock = Clock.systemUTC(), defaultPersona: Persona = Persona.WITNESS) {

    enum class State { IDLE, RECORDING, INFERRING, TRANSCRIBED, RESPONDED, ERROR }

    val transcript: Transcript = Transcript()

    var state: State = State.IDLE
        private set

    var lastError: Throwable? = null
        private set

    /**
     * Active persona for the upcoming foreground call (Story 2.3). Defaults to [Persona.WITNESS]
     * per `concept-locked.md` §Personas. Background extraction is persona-agnostic per
     * `AGENTS.md` guardrail 9, so this value never reaches the lens prompts.
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
     * already visible in the transcript. RESPONDED is terminal — the next recording requires a
     * fresh [CaptureSession] instance per the STT-B fallback's single-use lifecycle.
     */
    fun recordModelResponse(modelText: String, persona: Persona) {
        requireState("recordModelResponse", State.TRANSCRIBED)
        transcript.append(Turn(Speaker.MODEL, modelText, clock.instant(), persona))
        state = State.RESPONDED
    }

    /**
     * Any → ERROR. Stores [error] for the foreground UI to render. ERROR is terminal — the
     * caller constructs a fresh [CaptureSession] to retry; the prior session's transcript is
     * preserved on the failed instance for diagnostics until it goes out of scope.
     */
    fun fail(error: Throwable) {
        lastError = error
        state = State.ERROR
    }

    /**
     * Update [activePersona] for the upcoming foreground call. State-independent — callers may
     * switch persona while idle or during recording. The foreground call carries whichever
     * persona the caller passes into it; the resulting [Turn] records that persona regardless of
     * any later [setPersona] call on this session.
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
