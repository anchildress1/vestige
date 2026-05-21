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
        energyDescriptor: String? = null,
        recurrenceLink: String? = null,
        statedCommitmentJson: String? = null,
        tags: List<String> = emptyList(),
    ): List<FieldRow> = buildFieldRows(
        EntryEntity(
            entryText = entryText,
            confidenceJson = confidenceJson,
            energyDescriptor = energyDescriptor,
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
    fun `map field falls back when preferred keys are blank`() {
        assertEquals(
            "fallback",
            summaryFor(mapOf("energy_descriptor" to linkedMapOf("text" to " ", "z" to "fallback"))),
        )
    }

    @Test
    fun `map field with only blank values is treated as no value`() {
        assertEquals(
            EntryDetailCopy.LENS_NO_FIELDS,
            summaryFor(mapOf("energy_descriptor" to linkedMapOf("text" to " ", "z" to ""))),
        )
    }

    @Test
    fun `blank list field is treated as no value`() {
        assertEquals(EntryDetailCopy.LENS_NO_FIELDS, summaryFor(mapOf("energy_descriptor" to listOf(" ", ""))))
    }

    @Test
    fun `blank string field is treated as no value`() {
        assertEquals(EntryDetailCopy.LENS_NO_FIELDS, summaryFor(mapOf("energy_descriptor" to "   ")))
    }

    @Test
    fun `numeric field falls back to string rendering`() {
        assertEquals("42", summaryFor(mapOf("energy_descriptor" to 42)))
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
                        "energy_descriptor" to "ignored",
                        "stated_commitment" to "ignored commitment",
                        "recurrence_link" to VALID_PATTERN_ID,
                    ),
                ),
            ),
            confidenceJson = confidence(
                "energy_descriptor" to ConfidenceVerdict.CANONICAL_WITH_CONFLICT,
                "stated_commitment" to ConfidenceVerdict.CANONICAL,
                "recurrence_link" to ConfidenceVerdict.CANDIDATE,
                "tags" to ConfidenceVerdict.CANONICAL,
            ),
            energyDescriptor = "wired",
            recurrenceLink = "existing-pattern",
            statedCommitmentJson = """{"text":"call Pat"}""",
            tags = listOf("zeta", "alpha", "beta"),
        )

        assertEquals("alpha, beta", fieldRow(rows, "BEHAVIOR").value)
        assertEquals(LensTone.CANONICAL, fieldRow(rows, "BEHAVIOR").tone)
        assertEquals("wired", fieldRow(rows, "STATE").value)
        assertEquals(LensTone.CONFLICT, fieldRow(rows, "STATE").tone)
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
                        "energy_descriptor" to "wired",
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

        assertEquals("wired", fieldRow(rows, "STATE").value)
        assertEquals(LensTone.CONFLICT, fieldRow(rows, "STATE").tone)
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
                        "energy_descriptor" to "flat",
                        "stated_commitment" to "send the note",
                        "recurrence_link" to VALID_PATTERN_ID,
                    ),
                ),
            ),
        )

        assertEquals(LensTone.CANDIDATE, fieldRow(rows, "STATE").tone)
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
    fun `vocab receipt field outranks lexical fallback`() {
        val rows = rowsOf(
            receiptsJson = encode(
                EntryLensReceipt(
                    Lens.LITERAL,
                    extracted = true,
                    fields = mapOf("vocabulary_contradictions" to listOf("wired", "tired", "ignored")),
                ),
                EntryLensReceipt(
                    Lens.INFERENTIAL,
                    extracted = true,
                    fields = mapOf("vocabulary_contradictions" to listOf("wired")),
                ),
            ),
            entryText = "meeting meeting ticket ticket",
        )

        assertEquals("wired, tired", fieldRow(rows, "VOCAB").value)
        assertEquals(LensTone.CANONICAL, fieldRow(rows, "VOCAB").tone)
    }

    @Test
    fun `vocab lexical fallback uses repeated entry terms supported by receipt tags`() {
        val rows = rowsOf(
            receiptsJson = encode(
                EntryLensReceipt(Lens.LITERAL, extracted = true, fields = mapOf("tags" to listOf("meeting-retro"))),
                EntryLensReceipt(Lens.INFERENTIAL, extracted = true, fields = mapOf("tags" to listOf("meeting"))),
                EntryLensReceipt(Lens.SKEPTICAL, extracted = true, fields = mapOf("tags" to listOf("ticket"))),
            ),
            entryText = "meeting meeting ticket ticket the the",
        )

        assertEquals("meeting", fieldRow(rows, "VOCAB").value)
        assertEquals(LensTone.CANONICAL, fieldRow(rows, "VOCAB").tone)
    }

    @Test
    fun `vocab lexical fallback requires two supporting receipts`() {
        val rows = rowsOf(
            receiptsJson = encode(
                EntryLensReceipt(Lens.LITERAL, extracted = true, fields = mapOf("tags" to listOf("ticket"))),
                EntryLensReceipt(Lens.INFERENTIAL, extracted = true, fields = mapOf("tags" to listOf("noise"))),
                EntryLensReceipt(Lens.SKEPTICAL, extracted = true, fields = mapOf("tags" to listOf(123, "the"))),
            ),
            entryText = "ticket ticket",
        )

        assertEquals("—", fieldRow(rows, "VOCAB").value)
        assertEquals(LensTone.AMBIGUOUS, fieldRow(rows, "VOCAB").tone)
    }

    @Test
    fun `missing and unreadable receipts produce explicit field fallbacks`() {
        val missingRows = rowsOf(
            receiptsJson = "[]",
            confidenceJson = confidence(
                "energy_descriptor" to ConfidenceVerdict.CANDIDATE,
                "stated_commitment" to ConfidenceVerdict.CANONICAL_WITH_CONFLICT,
            ),
        )
        val unreadableRows = rowsOf(receiptsJson = "{nope")

        assertEquals("—", fieldRow(missingRows, "STATE").value)
        assertEquals(LensTone.CANDIDATE, fieldRow(missingRows, "STATE").tone)
        assertEquals("—", fieldRow(missingRows, "VOCAB").value)
        assertEquals(LensTone.AMBIGUOUS, fieldRow(missingRows, "VOCAB").tone)
        assertEquals(LensTone.CONFLICT, fieldRow(missingRows, "PROMISES").tone)
        assertEquals(EntryDetailCopy.LENS_UNREADABLE, fieldRow(unreadableRows, "VOCAB").value)
        assertEquals(LensTone.CONFLICT, fieldRow(unreadableRows, "VOCAB").tone)
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
            confidenceJson = """{"energy_descriptor":"CANONICAL","stated_commitment":"GARBAGE"}""",
        )

        assertEquals(LensTone.CANONICAL, fieldRow(rows, "STATE").tone)
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
            """[{"text":"slept poorly","evidence":"energy","fields":["energy_descriptor","  ","tags"]}]""",
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

    private companion object {
        private val VALID_PATTERN_ID = "a".repeat(64)
    }
}
