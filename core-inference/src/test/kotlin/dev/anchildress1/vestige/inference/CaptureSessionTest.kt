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
    fun `single-turn happy path walks IDLE to RESPONDED in chronological order`() {
        val transcriptionAt = Instant.parse("2026-05-09T12:00:00Z")
        val responseAt = Instant.parse("2026-05-09T12:00:05Z")
        val session = CaptureSession(clock = tickingClock(transcriptionAt, responseAt))

        session.startRecording()
        assertEquals(CaptureSession.State.RECORDING, session.state)
        session.submitForInference()
        assertEquals(CaptureSession.State.INFERRING, session.state)
        session.recordTranscription("user said this")
        assertEquals(CaptureSession.State.TRANSCRIBED, session.state)
        session.recordModelResponse("model replied this", Persona.WITNESS)
        assertEquals(CaptureSession.State.RESPONDED, session.state)

        assertTranscript(
            session = session,
            texts = listOf("user said this", "model replied this"),
            speakers = listOf(Speaker.USER, Speaker.MODEL),
            timestamps = listOf(transcriptionAt, responseAt),
            personas = listOf(null, Persona.WITNESS),
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
            timestamps = listOf(transcriptionAt, responseAt),
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
    fun `RESPONDED is terminal — startRecording is illegal after the model has replied`() {
        val session = CaptureSession(clock = fixedClock())
        session.startRecording()
        session.submitForInference()
        session.recordTranscription("u")
        session.recordModelResponse("m", Persona.WITNESS)
        assertEquals(CaptureSession.State.RESPONDED, session.state)

        val ex = assertThrows(IllegalStateException::class.java) { session.startRecording() }
        assertTrue(ex.message!!.contains("startRecording"))
        assertTrue(ex.message!!.contains("RESPONDED"))
    }

    @Test
    fun `RESPONDED is terminal — recordTranscription, recordModelResponse, and submitForInference all throw`() {
        val session = CaptureSession(clock = fixedClock())
        session.startRecording()
        session.submitForInference()
        session.recordTranscription("u")
        session.recordModelResponse("m", Persona.WITNESS)

        assertThrows(IllegalStateException::class.java) { session.submitForInference() }
        assertThrows(IllegalStateException::class.java) { session.recordTranscription("u2") }
        assertThrows(IllegalStateException::class.java) { session.recordModelResponse("m2", Persona.HARDASS) }
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
    fun `fail mid-recording transitions to ERROR and preserves the partial transcript for diagnostics`() {
        val session = CaptureSession(clock = fixedClock())
        session.startRecording()
        session.submitForInference()
        session.recordTranscription("got this far")
        session.fail(RuntimeException("audio buffer underrun"))

        assertEquals(CaptureSession.State.ERROR, session.state)
        assertEquals(1, session.transcript.size)
        assertEquals("got this far", session.transcript.turns[0].text)
    }

    @Test
    fun `ERROR is terminal — startRecording is illegal after fail`() {
        val session = CaptureSession()
        session.fail(RuntimeException("boom"))
        val ex = assertThrows(IllegalStateException::class.java) { session.startRecording() }
        assertTrue(ex.message!!.contains("ERROR"))
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

    @Test
    fun `default activePersona is WITNESS per concept-locked Personas`() {
        val session = CaptureSession()
        assertEquals(Persona.WITNESS, session.activePersona)
    }

    @Test
    fun `defaultPersona constructor argument seeds activePersona`() {
        val session = CaptureSession(defaultPersona = Persona.EDITOR)
        assertEquals(Persona.EDITOR, session.activePersona)
    }

    @Test
    fun `setPersona updates activePersona for the upcoming foreground call`() {
        val session = CaptureSession()
        assertEquals(Persona.WITNESS, session.activePersona)

        session.setPersona(Persona.HARDASS)
        assertEquals(Persona.HARDASS, session.activePersona)

        session.setPersona(Persona.EDITOR)
        assertEquals(Persona.EDITOR, session.activePersona)
    }

    @Test
    fun `transcript snapshot taken at RESPONDED is unchanged by subsequent illegal transitions`() {
        val transcriptionAt = Instant.parse("2026-05-09T08:30:00Z")
        val responseAt = Instant.parse("2026-05-09T08:30:04Z")
        val session = CaptureSession(clock = tickingClock(transcriptionAt, responseAt))
        session.startRecording()
        session.submitForInference()
        session.recordTranscription("user said this")
        session.recordModelResponse("model replied", Persona.WITNESS)

        val snapshot = session.transcript.turns

        assertThrows(IllegalStateException::class.java) { session.startRecording() }
        assertThrows(IllegalStateException::class.java) { session.submitForInference() }
        assertThrows(IllegalStateException::class.java) { session.recordTranscription("u2") }
        assertThrows(IllegalStateException::class.java) { session.recordModelResponse("m2", Persona.HARDASS) }

        assertEquals(2, snapshot.size)
        assertEquals(snapshot, session.transcript.turns)
        assertEquals("user said this", snapshot[0].text)
        assertEquals("model replied", snapshot[1].text)
    }

    @Test
    fun `setPersona is allowed in every state including the terminal RESPONDED and ERROR states`() {
        val transcriptionAt = Instant.parse("2026-05-09T08:30:00Z")
        val responseAt = Instant.parse("2026-05-09T08:30:04Z")
        val session = CaptureSession(clock = tickingClock(transcriptionAt, responseAt))

        session.setPersona(Persona.HARDASS)
        assertEquals(CaptureSession.State.IDLE, session.state)

        session.startRecording()
        session.setPersona(Persona.EDITOR)
        assertEquals(CaptureSession.State.RECORDING, session.state)
        assertEquals(Persona.EDITOR, session.activePersona)

        session.submitForInference()
        session.setPersona(Persona.WITNESS)
        assertEquals(CaptureSession.State.INFERRING, session.state)

        session.recordTranscription("u")
        session.setPersona(Persona.HARDASS)
        assertEquals(CaptureSession.State.TRANSCRIBED, session.state)

        session.recordModelResponse("m", Persona.WITNESS)
        session.setPersona(Persona.EDITOR)
        assertEquals(CaptureSession.State.RESPONDED, session.state)
        assertEquals(Persona.EDITOR, session.activePersona)

        session.fail(RuntimeException("boom"))
        session.setPersona(Persona.HARDASS)
        assertEquals(CaptureSession.State.ERROR, session.state)
        assertEquals(Persona.HARDASS, session.activePersona)
    }
}
