package dev.anchildress1.vestige.inference

import dev.anchildress1.vestige.model.Persona
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant

class TranscriptTest {

    @Test
    fun `fresh transcript is empty with size zero`() {
        val transcript = Transcript()
        assertTrue(transcript.isEmpty())
        assertEquals(0, transcript.size)
        assertEquals(emptyList<Turn>(), transcript.turns)
    }

    @Test
    fun `appended turns appear in insertion order`() {
        val transcript = Transcript()
        val first = Turn(Speaker.USER, "hello", Instant.parse("2026-05-09T00:00:01Z"))
        val second = Turn(Speaker.MODEL, "noted", Instant.parse("2026-05-09T00:00:02Z"), Persona.WITNESS)
        transcript.append(first)
        transcript.append(second)

        assertFalse(transcript.isEmpty())
        assertEquals(2, transcript.size)
        assertEquals(listOf(first, second), transcript.turns)
    }

    @Test
    fun `turns getter returns a defensive snapshot, not the live list`() {
        val transcript = Transcript()
        transcript.append(Turn(Speaker.USER, "u", Instant.EPOCH))

        val snapshotBefore = transcript.turns
        transcript.append(Turn(Speaker.MODEL, "m", Instant.EPOCH, Persona.EDITOR))
        val snapshotAfter = transcript.turns

        assertEquals(1, snapshotBefore.size)
        assertEquals(2, snapshotAfter.size)
        assertNotSame(snapshotBefore, snapshotAfter)
    }

    @Test
    fun `Turn equality uses speaker, text, and timestamp together`() {
        val ts = Instant.parse("2026-05-09T12:00:00Z")
        val a = Turn(Speaker.USER, "same", ts)
        val b = Turn(Speaker.USER, "same", ts)
        val c = Turn(Speaker.MODEL, "same", ts, Persona.WITNESS)
        val d = Turn(Speaker.USER, "different", ts)
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
        assertFalse(a == c)
        assertFalse(a == d)
    }

    @Test
    fun `Turn allows empty text — model may transcribe to empty without erroring the data type`() {
        val turn = Turn(Speaker.USER, "", Instant.EPOCH)
        assertEquals("", turn.text)
    }

    @Test
    fun `MODEL turns must carry a persona`() {
        assertThrows(IllegalArgumentException::class.java) {
            Turn(Speaker.MODEL, "reply", Instant.EPOCH)
        }
    }

    @Test
    fun `USER turns cannot carry a persona`() {
        assertThrows(IllegalArgumentException::class.java) {
            Turn(Speaker.USER, "reply", Instant.EPOCH, Persona.HARDASS)
        }
    }

    @Test
    fun `entryText joins only USER turns in order`() {
        val transcript = Transcript()
        transcript.append(Turn(Speaker.USER, "first user", Instant.EPOCH))
        transcript.append(Turn(Speaker.MODEL, "model reply", Instant.EPOCH, Persona.WITNESS))
        transcript.append(Turn(Speaker.USER, "second user", Instant.EPOCH))

        assertEquals("first user\n\nsecond user", transcript.entryText())
    }
}
