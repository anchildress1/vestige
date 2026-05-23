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

    private fun readOf(json: String?, lens: Lens): LensRead = buildLensReads(json).first { it.label == lens.name }

    private fun rowsOf(
        receiptsJson: String?,
        entryText: String = "",
        confidenceJson: String = "{}",
        recurrenceLink: String? = null,
        statedCommitmentJson: String? = null,
        tags: List<String> = emptyList(),
    ): List<FieldRow> = buildFieldRows(
        EntryEntity(
            entryText = entryText,
            confidenceJson = confidenceJson,
            recurrenceLink = recurrenceLink,
            statedCommitmentJson = statedCommitmentJson,
            lensReceiptsJson = receiptsJson,
        ).also { entity ->
            tags.forEach { tag -> entity.tags.add(TagEntity(name = tag)) }
        },
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

    // --- tone: Skeptical reads red only when it diverges AND flags ---

    @Test
    fun `agreeing Skeptical reads canonical even with flags`() {
        val json = encode(
            EntryLensReceipt(Lens.LITERAL, extracted = true, fields = mapOf("tags" to "calm")),
            EntryLensReceipt(Lens.INFERENTIAL, extracted = true, fields = mapOf("tags" to "calm")),
            EntryLensReceipt(
                Lens.SKEPTICAL,
                extracted = true,
                fields = mapOf("tags" to "calm"),
                flags = listOf("contradiction"),
            ),
        )

        assertEquals(LensTone.CANONICAL, readOf(json, Lens.SKEPTICAL).tone)
    }

    @Test
    fun `divergent Skeptical with a flag reads conflict`() {
        val json = encode(
            EntryLensReceipt(Lens.LITERAL, extracted = true, fields = mapOf("tags" to "calm")),
            EntryLensReceipt(Lens.INFERENTIAL, extracted = true, fields = mapOf("tags" to "calm")),
            EntryLensReceipt(
                Lens.SKEPTICAL,
                extracted = true,
                fields = mapOf("tags" to "tense"),
                flags = listOf("contradiction"),
            ),
        )

        assertEquals(LensTone.CONFLICT, readOf(json, Lens.SKEPTICAL).tone)
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
                    fields = mapOf(
                        "stated_commitment" to "ignored commitment",
                        "recurrence_link" to VALID_PATTERN_ID,
                    ),
                ),
            ),
            confidenceJson = confidence(
                "stated_commitment" to ConfidenceVerdict.CANONICAL,
                "recurrence_link" to ConfidenceVerdict.CANDIDATE,
                "tags" to ConfidenceVerdict.CANONICAL,
            ),
            recurrenceLink = "existing-pattern",
            statedCommitmentJson = """{"text":"call Pat"}""",
            tags = listOf("zeta", "alpha", "beta"),
        )

        assertEquals("alpha, beta", fieldRow(rows, "BEHAVIOR").value)
        assertEquals(LensTone.CANONICAL, fieldRow(rows, "BEHAVIOR").tone)
        assertEquals("call Pat", fieldRow(rows, "PROMISES").value)
        assertEquals(LensTone.CANONICAL, fieldRow(rows, "PROMISES").tone)
        assertEquals("existing-pattern", fieldRow(rows, "REPEAT").value)
        assertEquals(LensTone.CANDIDATE, fieldRow(rows, "REPEAT").tone)
    }

    @Test
    fun `receipt fields produce support-count values and flag conflicts`() {
        val rows = rowsOf(
            receiptsJson = encode(
                EntryLensReceipt(
                    Lens.LITERAL,
                    extracted = true,
                    fields = mapOf(
                        "stated_commitment" to mapOf("topic_or_person" to "Riley"),
                        "recurrence_link" to VALID_PATTERN_ID,
                    ),
                    flags = listOf("state disagreement"),
                ),
                EntryLensReceipt(
                    Lens.INFERENTIAL,
                    extracted = true,
                    fields = mapOf(
                        "stated_commitment" to mapOf("note" to "call Riley"),
                        "recurrence_link" to VALID_PATTERN_ID,
                    ),
                ),
                EntryLensReceipt(
                    Lens.SKEPTICAL,
                    extracted = true,
                    fields = mapOf("recurrence_link" to "not-a-pattern-id"),
                ),
            ),
        )

        assertEquals("Riley", fieldRow(rows, "PROMISES").value)
        assertEquals(LensTone.CONFLICT, fieldRow(rows, "PROMISES").tone)
        assertEquals(VALID_PATTERN_ID, fieldRow(rows, "REPEAT").value)
        assertEquals(LensTone.CONFLICT, fieldRow(rows, "REPEAT").tone)
    }

    @Test
    fun `two receipt field supports read as canonical without flags`() {
        val rows = rowsOf(
            receiptsJson = encode(
                EntryLensReceipt(
                    Lens.LITERAL,
                    extracted = true,
                    fields = mapOf("stated_commitment" to "send note", "recurrence_link" to VALID_PATTERN_ID),
                ),
                EntryLensReceipt(
                    Lens.INFERENTIAL,
                    extracted = true,
                    fields = mapOf("stated_commitment" to "send note", "recurrence_link" to VALID_PATTERN_ID),
                ),
            ),
        )

        assertEquals("send note", fieldRow(rows, "PROMISES").value)
        assertEquals(LensTone.CANONICAL, fieldRow(rows, "PROMISES").tone)
        assertEquals(VALID_PATTERN_ID, fieldRow(rows, "REPEAT").value)
        assertEquals(LensTone.CANONICAL, fieldRow(rows, "REPEAT").tone)
    }

    @Test
    fun `single receipt support reads as candidate when top-level fields are absent`() {
        val rows = rowsOf(
            receiptsJson = encode(
                EntryLensReceipt(
                    Lens.LITERAL,
                    extracted = true,
                    fields = mapOf(
                        "stated_commitment" to "send the note",
                        "recurrence_link" to VALID_PATTERN_ID,
                    ),
                ),
            ),
        )

        assertEquals(LensTone.CANDIDATE, fieldRow(rows, "PROMISES").tone)
        assertEquals(LensTone.CANDIDATE, fieldRow(rows, "REPEAT").tone)
    }

    @Test
    fun `invalid receipt recurrence falls back to dash and ambiguous tone`() {
        val rows = rowsOf(
            receiptsJson = encode(
                EntryLensReceipt(Lens.LITERAL, extracted = true, fields = mapOf("recurrence_link" to "short")),
                EntryLensReceipt(Lens.INFERENTIAL, extracted = true, fields = mapOf("recurrence_link" to 123)),
                EntryLensReceipt(Lens.SKEPTICAL, extracted = true, fields = mapOf("recurrence_link" to " ")),
            ),
        )

        assertEquals("—", fieldRow(rows, "REPEAT").value)
        assertEquals(LensTone.AMBIGUOUS, fieldRow(rows, "REPEAT").tone)
    }

    @Test
    fun `receipt recurrence with flags reads as conflict`() {
        val rows = rowsOf(
            receiptsJson = encode(
                EntryLensReceipt(
                    Lens.LITERAL,
                    extracted = true,
                    fields = mapOf("recurrence_link" to VALID_PATTERN_ID),
                    flags = listOf("pattern conflict"),
                ),
            ),
        )

        assertEquals(VALID_PATTERN_ID, fieldRow(rows, "REPEAT").value)
        assertEquals(LensTone.CONFLICT, fieldRow(rows, "REPEAT").tone)
    }

    @Test
    fun `missing and unreadable receipts produce explicit field fallbacks`() {
        val missingRows = rowsOf(
            receiptsJson = "[]",
            confidenceJson = confidence(
                "tags" to ConfidenceVerdict.CANDIDATE,
                "stated_commitment" to ConfidenceVerdict.CANONICAL_WITH_CONFLICT,
            ),
        )

        assertEquals("—", fieldRow(missingRows, "BEHAVIOR").value)
        assertEquals(LensTone.CANDIDATE, fieldRow(missingRows, "BEHAVIOR").tone)
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
            confidenceJson = """{"tags":"CANONICAL","stated_commitment":"GARBAGE"}""",
        )

        assertEquals(LensTone.CANONICAL, fieldRow(rows, "BEHAVIOR").tone)
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
    fun `conflict verdict outranks canonical in lens status`() {
        val json = confidence(
            "tags" to ConfidenceVerdict.CANONICAL,
            "stated_commitment" to ConfidenceVerdict.CANONICAL_WITH_CONFLICT,
        )

        assertEquals(EntryDetailCopy.THREE_LENS_STATUS_CONFLICT, lensStatus(json))
    }

    @Test
    fun `canonical outranks candidate when no conflict present`() {
        val json = confidence(
            "tags" to ConfidenceVerdict.CANDIDATE,
            "stated_commitment" to ConfidenceVerdict.CANONICAL,
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

    private companion object {
        private val VALID_PATTERN_ID = "a".repeat(64)
    }
}
