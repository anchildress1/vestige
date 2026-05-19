package dev.anchildress1.vestige.storage

import dev.anchildress1.vestige.model.EntryLensReceipt
import dev.anchildress1.vestige.model.Lens
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EntryLensReceiptJsonTest {

    @Test
    fun `encode and decode preserve parsed fields flags and failure metadata`() {
        val receipts = listOf(
            EntryLensReceipt(
                lens = Lens.LITERAL,
                extracted = true,
                fields = mapOf(
                    "tags" to listOf("standup", "crashed"),
                    "stated_commitment" to mapOf("text" to "review doc", "entry_id" to null),
                ),
                flags = emptyList(),
                attemptCount = 1,
                elapsedMs = 1_200L,
            ),
            EntryLensReceipt(
                lens = Lens.SKEPTICAL,
                extracted = false,
                attemptCount = 2,
                elapsedMs = 5_000L,
                lastError = "parse-fail",
            ),
        )

        val decoded = EntryLensReceiptJson.decode(EntryLensReceiptJson.encode(receipts))

        assertEquals(receipts[0].lens, decoded[0].lens)
        assertEquals(true, decoded[0].extracted)
        assertEquals(listOf("standup", "crashed"), decoded[0].fields["tags"])
        assertEquals(mapOf("text" to "review doc", "entry_id" to null), decoded[0].fields["stated_commitment"])
        assertEquals("parse-fail", decoded[1].lastError)
    }

    @Test
    fun `decode returns empty list for malformed json`() {
        assertTrue(EntryLensReceiptJson.decode("{not-json").isEmpty())
    }
}
