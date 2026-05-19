package dev.anchildress1.vestige.model

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class EntryLensReceiptTest {

    @Test
    fun `rejects negative attemptCount`() {
        assertThrows(IllegalArgumentException::class.java) {
            EntryLensReceipt(lens = Lens.LITERAL, extracted = false, attemptCount = -1)
        }
    }

    @Test
    fun `rejects negative elapsedMs`() {
        assertThrows(IllegalArgumentException::class.java) {
            EntryLensReceipt(lens = Lens.LITERAL, extracted = false, elapsedMs = -1L)
        }
    }

    @Test
    fun `accepts zero counters`() {
        val receipt = EntryLensReceipt(lens = Lens.SKEPTICAL, extracted = true)
        assertEquals(0, receipt.attemptCount)
        assertEquals(0L, receipt.elapsedMs)
    }
}
