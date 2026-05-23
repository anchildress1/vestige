package dev.anchildress1.vestige.ui.history

import dev.anchildress1.vestige.model.ConfidenceVerdict
import dev.anchildress1.vestige.model.EntryLensReceipt
import dev.anchildress1.vestige.model.Lens
import dev.anchildress1.vestige.storage.EntryEntity
import dev.anchildress1.vestige.storage.EntryLensReceiptJson
import dev.anchildress1.vestige.storage.TagEntity
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

    private fun readOf(json: String?, lens: Lens, hasConflict: Boolean = false): LensRead =
        buildLensReads(json, hasConflict).first { it.label == lens.name }

    private fun rowsOf(
        receiptsJson: String?,
        entryText: String = "",
        confidenceJson: String = "{}",
        statedCommitmentJson: String? = null,
        tags: List<String> = emptyList(),
        repeatTitle: String? = null,
    ): List<FieldRow> = buildFieldRows(
        EntryEntity(
            entryText = entryText,
            confidenceJson = confidenceJson,
            statedCommitmentJson = statedCommitmentJson,
            lensReceiptsJson = receiptsJson,
        ).also { entity ->
            tags.forEach { tag -> entity.tags.add(TagEntity(name = tag)) }
        },
        repeatTitle = repeatTitle,
    )

    private fun fieldRow(rows: List<FieldRow>, label: String): FieldRow = rows.first { it.label == label }

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
    fun `summary key precedence prefers tags over later keys`() {
        val json = encode(
            EntryLensReceipt(
                Lens.LITERAL,
                extracted = true,
                fields = mapOf("tags" to "wired", "recurrence_link" to "ignored"),
            ),
        )

        assertEquals("wired", readOf(json, Lens.LITERAL).value)
    }

    // --- tone: Skeptical reads red only on a real (CONSENSUS_WITH_CONFLICT) conflict ---

    @Test
    fun `Skeptical with a flag reads consensus when convergence found no conflict`() {
        val json = encode(
            EntryLensReceipt(
                Lens.SKEPTICAL,
                extracted = true,
                fields = mapOf("tags" to "calm"),
                flags = listOf("contradiction"),
            ),
        )

        assertEquals(LensTone.CONSENSUS, readOf(json, Lens.SKEPTICAL, hasConflict = false).tone)
    }

    @Test
    fun `Skeptical with a flag reads conflict when convergence flagged a conflict`() {
        val json = encode(
            EntryLensReceipt(
                Lens.SKEPTICAL,
                extracted = true,
                fields = mapOf("tags" to "calm"),
                flags = listOf("contradiction"),
            ),
        )

        assertEquals(LensTone.CONFLICT, readOf(json, Lens.SKEPTICAL, hasConflict = true).tone)
    }

    @Test
    fun `extracted receipt with a usable field is consensus`() {
        val json = encode(EntryLensReceipt(Lens.LITERAL, extracted = true, fields = mapOf("tags" to "calm")))

        assertEquals(LensTone.CONSENSUS, readOf(json, Lens.LITERAL).tone)
    }

    // --- displayValue: every value-shape arm, exercised through summaryText ---

    private fun summaryFor(field: Map<String, Any?>): String =
        readOf(encode(EntryLensReceipt(Lens.LITERAL, extracted = true, fields = field)), Lens.LITERAL).value

    @Test
    fun `list field joins at most two non-blank elements`() {
        assertEquals("a, b", summaryFor(mapOf("tags" to listOf("a", "b", "c"))))
    }

    @Test
    fun `map field prefers a known text key over arbitrary values`() {
        assertEquals("hello", summaryFor(mapOf("stated_commitment" to mapOf("text" to "hello", "z" to "zzz"))))
    }

    @Test
    fun `map field with no preferred key joins its values`() {
        assertEquals("a, b", summaryFor(mapOf("stated_commitment" to linkedMapOf("x" to "a", "y" to "b"))))
    }

    @Test
    fun `map field falls back when preferred keys are blank`() {
        assertEquals(
            "fallback",
            summaryFor(mapOf("stated_commitment" to linkedMapOf("text" to " ", "z" to "fallback"))),
        )
    }

    @Test
    fun `map field with only blank values is treated as no value`() {
        assertEquals(
            EntryDetailCopy.LENS_NO_FIELDS,
            summaryFor(mapOf("stated_commitment" to linkedMapOf("text" to " ", "z" to ""))),
        )
    }

    @Test
    fun `blank list field is treated as no value`() {
        assertEquals(EntryDetailCopy.LENS_NO_FIELDS, summaryFor(mapOf("tags" to listOf(" ", ""))))
    }

    @Test
    fun `blank string field is treated as no value`() {
        assertEquals(EntryDetailCopy.LENS_NO_FIELDS, summaryFor(mapOf("tags" to "   ")))
    }

    @Test
    fun `numeric field falls back to string rendering`() {
        assertEquals("42", summaryFor(mapOf("tags" to 42)))
    }

    // --- buildFieldRows ---

    @Test
    fun `top-level fields override receipt fields and keep confidence tones`() {
        val rows = rowsOf(
            receiptsJson = encode(
                EntryLensReceipt(
                    Lens.LITERAL,
                    extracted = true,
                    fields = mapOf("stated_commitment" to "ignored commitment"),
                ),
            ),
            confidenceJson = confidence(
                "stated_commitment" to ConfidenceVerdict.CONSENSUS,
                "tags" to ConfidenceVerdict.CONSENSUS,
            ),
            statedCommitmentJson = """{"text":"call Pat"}""",
            tags = listOf("zeta", "alpha", "beta"),
        )

        assertEquals("alpha, beta", fieldRow(rows, "BEHAVIOR").value)
        assertEquals(LensTone.CONSENSUS, fieldRow(rows, "BEHAVIOR").tone)
        assertEquals("call Pat", fieldRow(rows, "PROMISES").value)
        assertEquals(LensTone.CONSENSUS, fieldRow(rows, "PROMISES").tone)
    }

    @Test
    fun `receipt fields produce support-count values and flag conflicts`() {
        val rows = rowsOf(
            receiptsJson = encode(
                EntryLensReceipt(
                    Lens.LITERAL,
                    extracted = true,
                    fields = mapOf("stated_commitment" to mapOf("topic_or_person" to "Riley")),
                    flags = listOf("state disagreement"),
                ),
                EntryLensReceipt(
                    Lens.INFERENTIAL,
                    extracted = true,
                    fields = mapOf("stated_commitment" to mapOf("note" to "call Riley")),
                ),
            ),
        )

        assertEquals("Riley", fieldRow(rows, "PROMISES").value)
        assertEquals(LensTone.CONFLICT, fieldRow(rows, "PROMISES").tone)
    }

    @Test
    fun `two receipt field supports read as consensus without flags`() {
        val rows = rowsOf(
            receiptsJson = encode(
                EntryLensReceipt(Lens.LITERAL, extracted = true, fields = mapOf("stated_commitment" to "send note")),
                EntryLensReceipt(
                    Lens.INFERENTIAL,
                    extracted = true,
                    fields = mapOf("stated_commitment" to "send note"),
                ),
            ),
        )

        assertEquals("send note", fieldRow(rows, "PROMISES").value)
        assertEquals(LensTone.CONSENSUS, fieldRow(rows, "PROMISES").tone)
    }

    @Test
    fun `single receipt support reads as candidate when top-level fields are absent`() {
        val rows = rowsOf(
            receiptsJson = encode(
                EntryLensReceipt(
                    Lens.LITERAL,
                    extracted = true,
                    fields = mapOf("stated_commitment" to "send the note"),
                ),
            ),
        )

        assertEquals(LensTone.CANDIDATE, fieldRow(rows, "PROMISES").tone)
    }

    @Test
    fun `REPEAT shows the validated pattern title with the recurrence verdict tone`() {
        val consensusRows = rowsOf(
            receiptsJson = "[]",
            confidenceJson = confidence("recurrence_link" to ConfidenceVerdict.CONSENSUS),
            repeatTitle = "Tuesday Crash",
        )
        assertEquals("Tuesday Crash", fieldRow(consensusRows, "REPEAT").value)
        assertEquals(LensTone.CONSENSUS, fieldRow(consensusRows, "REPEAT").tone)

        // A single-lens CANDIDATE link must not render as fully-corroborated CONSENSUS.
        val candidateRows = rowsOf(
            receiptsJson = "[]",
            confidenceJson = confidence("recurrence_link" to ConfidenceVerdict.CANDIDATE),
            repeatTitle = "Tuesday Crash",
        )
        assertEquals(LensTone.CANDIDATE, fieldRow(candidateRows, "REPEAT").tone)
    }

    @Test
    fun `REPEAT falls back to dash and ambiguous when the model confirmed no recurrence`() {
        val none = rowsOf(receiptsJson = "[]", repeatTitle = null)
        val blank = rowsOf(receiptsJson = "[]", repeatTitle = "   ")

        assertEquals("—", fieldRow(none, "REPEAT").value)
        assertEquals(LensTone.AMBIGUOUS, fieldRow(none, "REPEAT").tone)
        assertEquals("—", fieldRow(blank, "REPEAT").value)
    }

    @Test
    fun `missing and unreadable receipts produce explicit field fallbacks`() {
        val missingRows = rowsOf(
            receiptsJson = "[]",
            confidenceJson = confidence(
                "tags" to ConfidenceVerdict.CANDIDATE,
                "stated_commitment" to ConfidenceVerdict.CONSENSUS_WITH_CONFLICT,
            ),
        )

        // No promoted tags and no receipt fallback → empty value, so the tone is AMBIGUOUS, not the
        // bare confidence verdict. A CANDIDATE tone with no value shown is the bug this guards against.
        assertEquals("—", fieldRow(missingRows, "BEHAVIOR").value)
        assertEquals(LensTone.AMBIGUOUS, fieldRow(missingRows, "BEHAVIOR").tone)
        assertEquals(LensTone.CONFLICT, fieldRow(missingRows, "PROMISES").tone)
    }

    @Test
    fun `commitment topic fallback is used when text is absent`() {
        val rows = rowsOf(
            receiptsJson = "[]",
            statedCommitmentJson = """{"topic_or_person":"Morgan"}""",
        )

        assertEquals("Morgan", fieldRow(rows, "PROMISES").value)
    }

    @Test
    fun `blank and malformed commitment json fall back to receipt values`() {
        val blankRows = rowsOf(
            receiptsJson = encode(
                EntryLensReceipt(Lens.LITERAL, extracted = true, fields = mapOf("stated_commitment" to "receipt")),
            ),
            statedCommitmentJson = " ",
        )
        val malformedRows = rowsOf(
            receiptsJson = encode(
                EntryLensReceipt(Lens.LITERAL, extracted = true, fields = mapOf("stated_commitment" to "receipt")),
            ),
            statedCommitmentJson = "{nope",
        )

        assertEquals("receipt", fieldRow(blankRows, "PROMISES").value)
        assertEquals("receipt", fieldRow(malformedRows, "PROMISES").value)
    }

    @Test
    fun `commitment json with no displayable fields falls back to dash`() {
        val rows = rowsOf(
            receiptsJson = "[]",
            statedCommitmentJson = """{"text":" ","topic_or_person":""}""",
        )

        assertEquals("—", fieldRow(rows, "PROMISES").value)
    }

    @Test
    fun `unknown confidence verdicts are ignored field by field`() {
        val rows = rowsOf(
            receiptsJson = "[]",
            confidenceJson = """{"tags":"CONSENSUS","stated_commitment":"GARBAGE"}""",
            tags = listOf("calm"),
        )

        assertEquals(LensTone.CONSENSUS, fieldRow(rows, "BEHAVIOR").tone)
        assertEquals(LensTone.AMBIGUOUS, fieldRow(rows, "PROMISES").tone)
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
            """[{"text":"slept poorly","evidence":"theme-noticing","fields":["recurrence_link","  ","tags"]}]""",
        ).single()

        assertEquals("slept poorly", line.text)
        assertEquals("theme-noticing", line.evidence)
        assertEquals(listOf("recurrence_link", "tags"), line.fields)
    }

    @Test
    fun `observation without evidence or fields defaults to null and empty`() {
        val line = parseObservations("""[{"text":"just text"}]""").single()

        assertNull(line.evidence)
        assertTrue(line.fields.isEmpty())
    }

    @Test
    fun `observation with non-array fields defaults to empty fields`() {
        val line = parseObservations("""[{"text":"just text","fields":"not-array"}]""").single()

        assertTrue(line.fields.isEmpty())
    }

    @Test
    fun `non-object observation items are dropped`() {
        assertTrue(parseObservations("""["not-object",{"text":" "}]""").isEmpty())
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
    fun `conflict verdict outranks consensus in lens status`() {
        val json = confidence(
            "tags" to ConfidenceVerdict.CONSENSUS,
            "stated_commitment" to ConfidenceVerdict.CONSENSUS_WITH_CONFLICT,
        )

        assertEquals(EntryDetailCopy.THREE_LENS_STATUS_CONFLICT, lensStatus(json))
    }

    @Test
    fun `consensus outranks candidate when no conflict present`() {
        val json = confidence(
            "tags" to ConfidenceVerdict.CANDIDATE,
            "stated_commitment" to ConfidenceVerdict.CONSENSUS,
        )

        assertEquals(EntryDetailCopy.THREE_LENS_STATUS_CONSENSUS, lensStatus(json))
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
