package dev.anchildress1.vestige.inference

import dev.anchildress1.vestige.model.Persona
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

class CaptureSessionTest {

    private fun fixedClock(at: Instant = Instant.parse("2026-05-09T12:00:00Z")): Clock = Clock.fixed(at, ZoneOffset.UTC)

    private fun tickingClock(vararg instants: Instant): Clock = object : Clock() {
        private val pending = instants.toMutableList()
        override fun getZone() = ZoneOffset.UTC
        override fun withZone(zone: java.time.ZoneId?) = this
        override fun instant(): Instant = pending.removeAt(0)
    }

    private fun runSuccessfulTurn(session: CaptureSession, userText: String, modelText: String, persona: Persona) {
        session.startRecording()
        assertEquals(CaptureSession.State.RECORDING, session.state)
        session.submitForInference()
        assertEquals(CaptureSession.State.INFERRING, session.state)
        session.recordTranscription(userText = userText)
        assertEquals(CaptureSession.State.TRANSCRIBED, session.state)
        session.recordModelResponse(modelText = modelText, persona = persona)
        assertEquals(CaptureSession.State.RESPONDED, session.state)
        session.acknowledgeResponse()
        assertEquals(CaptureSession.State.IDLE, session.state)
    }

    private fun assertTranscript(
        session: CaptureSession,
        texts: List<String>,
        speakers: List<Speaker>,
        timestamps: List<Instant>,
        personas: List<Persona?>,
    ) {
        val turns = session.transcript.turns
        assertEquals(texts, turns.map { it.text })
        assertEquals(speakers, turns.map { it.speaker })
        assertEquals(timestamps, turns.map { it.timestamp })
        assertEquals(personas, turns.map { it.persona })
    }

    @Test
    fun `fresh session starts IDLE with empty transcript and no error`() {
        val session = CaptureSession()
        assertEquals(CaptureSession.State.IDLE, session.state)
        assertTrue(session.transcript.isEmpty())
        assertNull(session.lastError)
    }

    @Test
    fun `full multi-turn session walks the happy path and preserves chronological order`() {
        val firstTurnAt = Instant.parse("2026-05-09T12:00:00Z")
        val firstResponseAt = Instant.parse("2026-05-09T12:00:05Z")
        val secondTurnAt = Instant.parse("2026-05-09T12:00:30Z")
        val secondResponseAt = Instant.parse("2026-05-09T12:00:35Z")
        val thirdTurnAt = Instant.parse("2026-05-09T12:01:00Z")
        val thirdResponseAt = Instant.parse("2026-05-09T12:01:05Z")
        val session = CaptureSession(
            clock = tickingClock(
                firstTurnAt,
                firstResponseAt,
                secondTurnAt,
                secondResponseAt,
                thirdTurnAt,
                thirdResponseAt,
            ),
        )
        val personas = listOf(Persona.WITNESS, Persona.HARDASS, Persona.EDITOR)

        repeat(3) { index ->
            runSuccessfulTurn(session, "user-$index", "model-$index", personas[index])
        }

        assertEquals(6, session.transcript.size)
        assertTranscript(
            session = session,
            texts = listOf("user-0", "model-0", "user-1", "model-1", "user-2", "model-2"),
            speakers =
            listOf(
                Speaker.USER,
                Speaker.MODEL,
                Speaker.USER,
                Speaker.MODEL,
                Speaker.USER,
                Speaker.MODEL,
            ),
            timestamps = listOf(
                firstTurnAt,
                firstResponseAt,
                secondTurnAt,
                secondResponseAt,
                thirdTurnAt,
                thirdResponseAt,
            ),
            personas = listOf(null, Persona.WITNESS, null, Persona.HARDASS, null, Persona.EDITOR),
        )
    }

    @Test
    fun `recordTranscription precedes the model response in both state and timestamps`() {
        val transcriptionAt = Instant.parse("2026-05-09T08:30:00Z")
        val responseAt = Instant.parse("2026-05-09T08:30:04Z")
        val session = CaptureSession(clock = tickingClock(transcriptionAt, responseAt))
        session.startRecording()
        session.submitForInference()
        session.recordTranscription("u")
        assertEquals(CaptureSession.State.TRANSCRIBED, session.state)
        session.recordModelResponse("m", Persona.WITNESS)

        assertTranscript(
            session = session,
            texts = listOf("u", "m"),
            speakers = listOf(Speaker.USER, Speaker.MODEL),
            timestamps = listOf(
                transcriptionAt,
                responseAt,
            ),
            personas = listOf(null, Persona.WITNESS),
        )
    }

    @Test
    fun `startRecording from RECORDING throws — already recording is illegal`() {
        val session = CaptureSession()
        session.startRecording()
        val ex = assertThrows(IllegalStateException::class.java) { session.startRecording() }
        assertTrue(ex.message!!.contains("startRecording"))
        assertTrue(ex.message!!.contains("RECORDING"))
    }

    @Test
    fun `submitForInference from IDLE throws`() {
        val session = CaptureSession()
        assertThrows(IllegalStateException::class.java) { session.submitForInference() }
    }

    @Test
    fun `recordTranscription from RECORDING throws`() {
        val session = CaptureSession()
        session.startRecording()
        assertThrows(IllegalStateException::class.java) {
            session.recordTranscription("u")
        }
    }

    @Test
    fun `recordModelResponse from INFERRING throws until transcription lands`() {
        val session = CaptureSession()
        session.startRecording()
        session.submitForInference()
        assertThrows(IllegalStateException::class.java) {
            session.recordModelResponse("m", Persona.WITNESS)
        }
    }

    @Test
    fun `startRecording from TRANSCRIBED throws — model reply is still pending`() {
        val session = CaptureSession()
        session.startRecording()
        session.submitForInference()
        session.recordTranscription("u")
        assertThrows(IllegalStateException::class.java) { session.startRecording() }
    }

    @Test
    fun `acknowledgeResponse from TRANSCRIBED throws — model reply hasn't landed yet`() {
        val session = CaptureSession()
        session.startRecording()
        session.submitForInference()
        session.recordTranscription("u")
        assertThrows(IllegalStateException::class.java) { session.acknowledgeResponse() }
    }

    @Test
    fun `acknowledgeResponse from IDLE throws`() {
        val session = CaptureSession()
        assertThrows(IllegalStateException::class.java) { session.acknowledgeResponse() }
    }

    @Test
    fun `clearError from non-ERROR state throws`() {
        val session = CaptureSession()
        assertThrows(IllegalStateException::class.java) { session.clearError() }
    }

    @Test
    fun `fail from any state moves to ERROR and stores the cause`() {
        val session = CaptureSession()
        val cause = IllegalArgumentException("model exploded")
        session.fail(cause)
        assertEquals(CaptureSession.State.ERROR, session.state)
        assertSame(cause, session.lastError)
    }

    @Test
    fun `fail mid-recording transitions to ERROR without losing transcript history`() {
        val session = CaptureSession(clock = fixedClock())
        session.startRecording()
        session.submitForInference()
        session.recordTranscription("first user")
        session.recordModelResponse("first model", Persona.WITNESS)
        session.acknowledgeResponse()

        session.startRecording()
        session.fail(RuntimeException("audio buffer underrun"))

        assertEquals(CaptureSession.State.ERROR, session.state)
        assertEquals(2, session.transcript.size)
        assertEquals("first user", session.transcript.turns[0].text)
        assertEquals("first model", session.transcript.turns[1].text)
    }

    @Test
    fun `clearError returns to IDLE, clears lastError, and preserves transcript`() {
        val session = CaptureSession(clock = fixedClock())
        session.startRecording()
        session.submitForInference()
        session.recordTranscription("kept")
        session.recordModelResponse("still here", Persona.WITNESS)
        session.acknowledgeResponse()
        session.fail(RuntimeException("boom"))

        session.clearError()

        assertEquals(CaptureSession.State.IDLE, session.state)
        assertNull(session.lastError)
        assertEquals(2, session.transcript.size)
    }

    @Test
    fun `recovered session can keep recording new turns after clearError`() {
        val session = CaptureSession(clock = fixedClock())
        session.startRecording()
        session.fail(RuntimeException("boom"))
        session.clearError()

        session.startRecording()
        session.submitForInference()
        session.recordTranscription("post-recovery")
        session.recordModelResponse("ack", Persona.EDITOR)
        session.acknowledgeResponse()

        assertEquals(CaptureSession.State.IDLE, session.state)
        assertEquals(listOf("post-recovery", "ack"), session.transcript.turns.map { it.text })
    }

    @Test
    fun `repeated fail calls overwrite lastError with the latest cause`() {
        val session = CaptureSession()
        val first = RuntimeException("first")
        val second = RuntimeException("second")
        session.fail(first)
        session.fail(second)
        assertEquals(CaptureSession.State.ERROR, session.state)
        assertSame(second, session.lastError)
    }

    @Test
    fun `recordTranscription with empty user text still appends the user turn`() {
        val session = CaptureSession(clock = fixedClock())
        session.startRecording()
        session.submitForInference()
        session.recordTranscription(userText = "")

        assertEquals(CaptureSession.State.TRANSCRIBED, session.state)
        assertEquals(1, session.transcript.size)
        assertEquals("", session.transcript.turns[0].text)
    }

    @Test
    fun `recordModelResponse stores the persona that authored the reply`() {
        val session = CaptureSession(clock = fixedClock())
        session.startRecording()
        session.submitForInference()
        session.recordTranscription("user text")
        session.recordModelResponse(modelText = "sharp reply", persona = Persona.HARDASS)

        val modelTurn = session.transcript.turns.single { it.speaker == Speaker.MODEL }
        assertEquals(Persona.HARDASS, modelTurn.persona)
    }
}
