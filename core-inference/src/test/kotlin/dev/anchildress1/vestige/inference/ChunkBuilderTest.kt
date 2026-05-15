package dev.anchildress1.vestige.inference

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ChunkBuilderTest {

    @Test
    fun `samples below one chunk are returned only by drainFinal`() {
        val builder = ChunkBuilder(samplesPerChunk = 100)
        val produced = builder.append(FloatArray(40) { it.toFloat() }, count = 40)
        assertTrue(produced.isEmpty(), "no full chunk yet")

        val tail = builder.drainFinal()
        assertEquals(40, tail!!.size)
        assertEquals(0f, tail[0])
        assertEquals(39f, tail[39])
    }

    @Test
    fun `exactly one chunk worth produces one chunk and an empty drain`() {
        val builder = ChunkBuilder(samplesPerChunk = 4)
        val produced = builder.append(floatArrayOf(0.1f, 0.2f, 0.3f, 0.4f), count = 4)
        assertEquals(1, produced.size)
        assertArrayEquals(floatArrayOf(0.1f, 0.2f, 0.3f, 0.4f), produced[0])
        assertNull(builder.drainFinal(), "no trailing samples on an exact boundary")
    }

    @Test
    fun `more than one chunk emits sequentially with the leftover in drain`() {
        val builder = ChunkBuilder(samplesPerChunk = 3)
        val source = floatArrayOf(1f, 2f, 3f, 4f, 5f, 6f, 7f, 8f) // 2 chunks + 2 leftover
        val produced = builder.append(source, count = source.size)
        assertEquals(2, produced.size)
        assertArrayEquals(floatArrayOf(1f, 2f, 3f), produced[0])
        assertArrayEquals(floatArrayOf(4f, 5f, 6f), produced[1])
        assertArrayEquals(floatArrayOf(7f, 8f), builder.drainFinal())
    }

    @Test
    fun `successive appends accumulate across boundaries`() {
        val builder = ChunkBuilder(samplesPerChunk = 5)
        assertTrue(builder.append(floatArrayOf(1f, 2f, 3f), 3).isEmpty())
        val mid = builder.append(floatArrayOf(4f, 5f, 6f, 7f), 4)
        assertEquals(1, mid.size)
        assertArrayEquals(floatArrayOf(1f, 2f, 3f, 4f, 5f), mid[0])
        assertArrayEquals(floatArrayOf(6f, 7f), builder.drainFinal())
    }

    @Test
    fun `count smaller than source length only takes the requested prefix`() {
        val builder = ChunkBuilder(samplesPerChunk = 4)
        val source = floatArrayOf(9f, 8f, 7f, 6f, 5f, 4f)
        val produced = builder.append(source, count = 3) // only the first three samples
        assertTrue(produced.isEmpty())
        assertArrayEquals(floatArrayOf(9f, 8f, 7f), builder.drainFinal())
    }

    @Test
    fun `drainFinal twice returns null on the second call`() {
        val builder = ChunkBuilder(samplesPerChunk = 4)
        builder.append(floatArrayOf(1f, 2f), 2)
        assertArrayEquals(floatArrayOf(1f, 2f), builder.drainFinal())
        assertNull(builder.drainFinal())
    }

    @Test
    fun `append with count 0 is a no-op`() {
        val builder = ChunkBuilder(samplesPerChunk = 4)
        assertTrue(builder.append(FloatArray(10), 0).isEmpty())
        assertNull(builder.drainFinal())
    }

    @Test
    fun `negative count is rejected`() {
        val builder = ChunkBuilder(samplesPerChunk = 4)
        assertThrows(IllegalArgumentException::class.java) {
            builder.append(FloatArray(4), count = -1)
        }
    }

    @Test
    fun `count exceeding source size is rejected`() {
        val builder = ChunkBuilder(samplesPerChunk = 4)
        assertThrows(IllegalArgumentException::class.java) {
            builder.append(FloatArray(2), count = 10)
        }
    }

    @Test
    fun `non-positive samplesPerChunk is rejected at construction`() {
        assertThrows(IllegalArgumentException::class.java) { ChunkBuilder(samplesPerChunk = 0) }
        assertThrows(IllegalArgumentException::class.java) { ChunkBuilder(samplesPerChunk = -1) }
    }

    @Test
    fun `emitted chunks are independent of the internal buffer`() {
        val builder = ChunkBuilder(samplesPerChunk = 2)
        val produced = builder.append(floatArrayOf(1f, 2f), 2)
        assertEquals(1, produced.size)
        builder.append(floatArrayOf(99f, 99f), 2)
        assertArrayEquals(floatArrayOf(1f, 2f), produced[0])
    }

    // ─── clear (Q8 — synchronous buffer overwrite on discard) ────────────────

    @Test
    fun `clear drains accumulated samples — drainFinal returns null afterward (pos)`() {
        val builder = ChunkBuilder(samplesPerChunk = 100)
        builder.append(FloatArray(40) { it.toFloat() + 1f }, count = 40)
        builder.clear()
        assertNull(builder.drainFinal(), "clear must reset the write head")
    }

    @Test
    fun `clear is a no-op on an empty builder (edge)`() {
        val builder = ChunkBuilder(samplesPerChunk = 8)
        builder.clear()
        assertNull(builder.drainFinal())
        val produced = builder.append(floatArrayOf(1f, 2f), 2)
        assertTrue(produced.isEmpty())
    }

    @Test
    fun `clear resets so subsequent appends start from a fresh window (pos)`() {
        val builder = ChunkBuilder(samplesPerChunk = 4)
        builder.append(floatArrayOf(7f, 8f), 2)
        builder.clear()
        val produced = builder.append(floatArrayOf(1f, 2f, 3f, 4f), 4)
        assertEquals(1, produced.size)
        assertArrayEquals(floatArrayOf(1f, 2f, 3f, 4f), produced[0])
        assertFalse(produced[0].any { it == 7f || it == 8f }, "stale samples must not bleed through clear")
    }
}
