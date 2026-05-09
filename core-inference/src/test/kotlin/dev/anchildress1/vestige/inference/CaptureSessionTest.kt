package dev.anchildress1.vestige.inference

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
        val secondTurnAt = Instant.parse("2026-05-09T12:00:30Z")
        val thirdTurnAt = Instant.parse("2026-05-09T12:01:00Z")

        val ticking = object : Clock() {
            private val pending = mutableListOf(firstTurnAt, secondTurnAt, thirdTurnAt)
            override fun getZone() = ZoneOffset.UTC
            override fun withZone(zone: java.time.ZoneId?) = this
            override fun instant(): Instant = pending.removeAt(0)
        }
        val session = CaptureSession(clock = ticking)

        repeat(3) { index ->
            session.startRecording()
            assertEquals(CaptureSession.State.RECORDING, session.state)
            session.submitForInference()
            assertEquals(CaptureSession.State.INFERRING, session.state)
            session.recordResponse(userText = "user-$index", modelText = "model-$index")
            assertEquals(CaptureSession.State.RESPONDED, session.state)
            session.acknowledgeResponse()
            assertEquals(CaptureSession.State.IDLE, session.state)
        }

        val turns = session.transcript.turns
        assertEquals(6, turns.size)
        assertEquals(
            listOf("user-0", "model-0", "user-1", "model-1", "user-2", "model-2"),
            turns.map { it.text },
        )
        assertEquals(
            listOf(
                Speaker.USER,
                Speaker.MODEL,
                Speaker.USER,
                Speaker.MODEL,
                Speaker.USER,
                Speaker.MODEL,
            ),
            turns.map { it.speaker },
        )
        assertEquals(
            listOf(firstTurnAt, firstTurnAt, secondTurnAt, secondTurnAt, thirdTurnAt, thirdTurnAt),
            turns.map { it.timestamp },
        )
    }

    @Test
    fun `recordResponse stamps both turns with the same clock instant`() {
        val at = Instant.parse("2026-05-09T08:30:00Z")
        val session = CaptureSession(clock = fixedClock(at))
        session.startRecording()
        session.submitForInference()
        session.recordResponse("u", "m")

        val turns = session.transcript.turns
        assertEquals(2, turns.size)
        assertEquals(at, turns[0].timestamp)
        assertEquals(at, turns[1].timestamp)
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
    fun `recordResponse from RECORDING throws`() {
        val session = CaptureSession()
        session.startRecording()
        assertThrows(IllegalStateException::class.java) {
            session.recordResponse("u", "m")
        }
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
        session.recordResponse("first user", "first model")
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
        session.recordResponse("kept", "still here")
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
        session.recordResponse("post-recovery", "ack")
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
    fun `recordResponse with empty user text still appends both turns and advances state`() {
        val session = CaptureSession(clock = fixedClock())
        session.startRecording()
        session.submitForInference()
        session.recordResponse(userText = "", modelText = "noted")

        assertEquals(CaptureSession.State.RESPONDED, session.state)
        assertEquals(2, session.transcript.size)
        assertEquals("", session.transcript.turns[0].text)
        assertEquals("noted", session.transcript.turns[1].text)
    }
}
