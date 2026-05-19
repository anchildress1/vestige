package dev.anchildress1.vestige.ui.history

import dev.anchildress1.vestige.model.ConfidenceVerdict
import dev.anchildress1.vestige.model.EntryLensReceipt
import dev.anchildress1.vestige.model.Lens
import dev.anchildress1.vestige.storage.EntryLensReceiptJson
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, application = HistoryTestApplication::class)
class EntryDetailReceiptFormattingTest {

    private fun encode(vararg receipts: EntryLensReceipt): String = EntryLensReceiptJson.encode(receipts.toList())

    private fun readOf(json: String?, lens: Lens): LensRead = buildLensReads(json).first { it.label == lens.name }

    // --- buildLensReads: decode-state branches ---

    @Test
    fun `null receipts json renders every lens as not-run, not unreadable`() {
        val reads = buildLensReads(null)

        assertEquals(Lens.entries.map { it.name }, reads.map { it.label })
        assertTrue(reads.all { it.value == EntryDetailCopy.LENS_MISSING })
        assertTrue(reads.all { it.tone == LensTone.AMBIGUOUS })
    }

    @Test
    fun `empty array receipts json renders every lens as not-run`() {
        val reads = buildLensReads("[]")

        assertTrue(reads.all { it.value == EntryDetailCopy.LENS_MISSING && it.tone == LensTone.AMBIGUOUS })
    }

    @Test
    fun `corrupt receipts json renders every lens as unreadable with conflict tone`() {
        val reads = buildLensReads("{not valid json")

        assertEquals(Lens.entries.size, reads.size)
        assertTrue(reads.all { it.value == EntryDetailCopy.LENS_UNREADABLE })
        assertTrue(reads.all { it.tone == LensTone.CONFLICT })
    }

    @Test
    fun `lens without a receipt is not-run while a sibling lens resolves`() {
        val json = encode(EntryLensReceipt(Lens.LITERAL, extracted = true, fields = mapOf("tags" to "calm")))

        assertEquals("calm", readOf(json, Lens.LITERAL).value)
        assertEquals(EntryDetailCopy.LENS_MISSING, readOf(json, Lens.INFERENTIAL).value)
        assertEquals(LensTone.AMBIGUOUS, readOf(json, Lens.INFERENTIAL).tone)
    }

    // --- summaryText: extracted / not-extracted / field precedence ---

    @Test
    fun `unextracted receipt surfaces lastError when present`() {
        val json = encode(EntryLensReceipt(Lens.SKEPTICAL, extracted = false, lastError = "timeout"))

        assertEquals("timeout", readOf(json, Lens.SKEPTICAL).value)
        assertEquals(LensTone.AMBIGUOUS, readOf(json, Lens.SKEPTICAL).tone)
    }

    @Test
    fun `unextracted receipt with no error reads as no-opinion`() {
        val json = encode(EntryLensReceipt(Lens.SKEPTICAL, extracted = false, lastError = null))

        assertEquals(EntryDetailCopy.LENS_NO_OPINION, readOf(json, Lens.SKEPTICAL).value)
    }

    @Test
    fun `extracted receipt with no displayable fields reads as no-fields and ambiguous`() {
        val json = encode(EntryLensReceipt(Lens.LITERAL, extracted = true, fields = emptyMap()))

        assertEquals(EntryDetailCopy.LENS_NO_FIELDS, readOf(json, Lens.LITERAL).value)
        assertEquals(LensTone.AMBIGUOUS, readOf(json, Lens.LITERAL).tone)
    }

    @Test
    fun `summary key precedence prefers energy_descriptor over later keys`() {
        val json = encode(
            EntryLensReceipt(
                Lens.LITERAL,
                extracted = true,
                fields = mapOf("energy_descriptor" to "wired", "tags" to "ignored"),
            ),
        )

        assertEquals("wired", readOf(json, Lens.LITERAL).value)
    }

    // --- tone: flags-first invariant ---

    @Test
    fun `flags force conflict tone even when extracted with usable fields`() {
        val json = encode(
            EntryLensReceipt(
                Lens.LITERAL,
                extracted = true,
                fields = mapOf("tags" to "calm"),
                flags = listOf("contradiction"),
            ),
        )

        assertEquals(LensTone.CONFLICT, readOf(json, Lens.LITERAL).tone)
    }

    @Test
    fun `extracted receipt with a usable field is canonical`() {
        val json = encode(EntryLensReceipt(Lens.LITERAL, extracted = true, fields = mapOf("tags" to "calm")))

        assertEquals(LensTone.CANONICAL, readOf(json, Lens.LITERAL).tone)
    }

    // --- displayValue: every value-shape arm, exercised through summaryText ---

    private fun summaryFor(field: Map<String, Any?>): String =
        readOf(encode(EntryLensReceipt(Lens.LITERAL, extracted = true, fields = field)), Lens.LITERAL).value

    @Test
    fun `boolean true field renders as state shift, false falls through`() {
        assertEquals("state shift", summaryFor(mapOf("state_shift" to true)))
        // false -> null -> no other key -> no-fields
        assertEquals(EntryDetailCopy.LENS_NO_FIELDS, summaryFor(mapOf("state_shift" to false)))
    }

    @Test
    fun `list field joins at most two non-blank elements`() {
        assertEquals("a, b", summaryFor(mapOf("energy_descriptor" to listOf("a", "b", "c"))))
    }

    @Test
    fun `map field prefers a known text key over arbitrary values`() {
        assertEquals("hello", summaryFor(mapOf("energy_descriptor" to mapOf("text" to "hello", "z" to "zzz"))))
    }

    @Test
    fun `map field with no preferred key joins its values`() {
        assertEquals("a, b", summaryFor(mapOf("energy_descriptor" to linkedMapOf("x" to "a", "y" to "b"))))
    }

    @Test
    fun `blank string field is treated as no value`() {
        assertEquals(EntryDetailCopy.LENS_NO_FIELDS, summaryFor(mapOf("energy_descriptor" to "   ")))
    }

    // --- parseObservations ---

    @Test
    fun `blank or empty-array observations json yields no lines`() {
        assertTrue(parseObservations("").isEmpty())
        assertTrue(parseObservations("[]").isEmpty())
        assertTrue(parseObservations("   ").isEmpty())
    }

    @Test
    fun `observation parses text, evidence and non-blank fields`() {
        val line = parseObservations(
            """[{"text":"slept poorly","evidence":"energy","fields":["energy_descriptor","  "," tags "]}]""",
        ).single()

        assertEquals("slept poorly", line.text)
        assertEquals("energy", line.evidence)
        assertEquals(listOf("energy_descriptor", "tags"), line.fields)
    }

    @Test
    fun `observation without evidence or fields defaults to null and empty`() {
        val line = parseObservations("""[{"text":"just text"}]""").single()

        assertNull(line.evidence)
        assertTrue(line.fields.isEmpty())
    }

    @Test
    fun `observation with blank text is dropped`() {
        assertTrue(parseObservations("""[{"text":"   ","evidence":"x"}]""").isEmpty())
    }

    @Test
    fun `malformed observations json yields no lines instead of throwing`() {
        assertTrue(parseObservations("{not an array").isEmpty())
    }

    // --- lensStatus: verdict precedence ---

    private fun confidence(vararg verdicts: Pair<String, ConfidenceVerdict>): String =
        JSONObject().apply { verdicts.forEach { (k, v) -> put(k, v.name) } }.toString()

    @Test
    fun `conflict verdict outranks canonical in lens status`() {
        val json = confidence(
            "tags" to ConfidenceVerdict.CANONICAL,
            "energy_descriptor" to ConfidenceVerdict.CANONICAL_WITH_CONFLICT,
        )

        assertEquals(EntryDetailCopy.THREE_LENS_STATUS_CONFLICT, lensStatus(json))
    }

    @Test
    fun `canonical outranks candidate when no conflict present`() {
        val json = confidence(
            "tags" to ConfidenceVerdict.CANDIDATE,
            "energy_descriptor" to ConfidenceVerdict.CANONICAL,
        )

        assertEquals(EntryDetailCopy.THREE_LENS_STATUS_CANONICAL, lensStatus(json))
    }

    @Test
    fun `candidate-only confidence reads as candidate`() {
        assertEquals(
            EntryDetailCopy.THREE_LENS_STATUS_CANDIDATE,
            lensStatus(confidence("tags" to ConfidenceVerdict.CANDIDATE)),
        )
    }

    @Test
    fun `empty or malformed confidence reads as ambiguous`() {
        assertEquals(EntryDetailCopy.THREE_LENS_STATUS_AMBIGUOUS, lensStatus("{}"))
        assertEquals(EntryDetailCopy.THREE_LENS_STATUS_AMBIGUOUS, lensStatus("not json"))
    }
}
