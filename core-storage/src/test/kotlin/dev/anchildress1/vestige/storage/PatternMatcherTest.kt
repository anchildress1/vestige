package dev.anchildress1.vestige.storage

import dev.anchildress1.vestige.model.PatternKind
import dev.anchildress1.vestige.model.TemplateLabel
import dev.anchildress1.vestige.testing.newInMemoryObjectBoxDirectory
import dev.anchildress1.vestige.testing.openInMemoryBoxStore
import io.objectbox.BoxStore
import io.objectbox.kotlin.boxFor
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.time.Instant
import java.time.ZoneOffset

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class PatternMatcherTest {

    private lateinit var boxStore: BoxStore
    private lateinit var dataDir: File

    @Before
    fun setUp() {
        dataDir = newInMemoryObjectBoxDirectory("objectbox-matcher-")
        boxStore = openInMemoryBoxStore(dataDir)
    }

    @After
    fun tearDown() {
        boxStore.closeAfterCleaningThreadResources()
        BoxStore.deleteAllFiles(dataDir)
    }

    @Suppress("LongParameterList") // Test seam mirrors the fields PatternMatcher reads.
    private fun putEntry(
        templateLabel: TemplateLabel? = null,
        tags: List<String> = emptyList(),
        text: String = "",
        commitmentTopic: String? = null,
        timestamp: Instant = Instant.parse("2026-05-11T12:00:00Z"),
    ): EntryEntity {
        val entry = EntryEntity(
            entryText = text,
            templateLabel = templateLabel,
            statedCommitmentJson = commitmentTopic?.let { """{"topic_or_person":"$it","text":"do it"}""" },
            timestampEpochMs = timestamp.toEpochMilli(),
        )
        boxStore.boxFor<EntryEntity>().put(entry)
        if (tags.isNotEmpty()) {
            val tagBox = boxStore.boxFor<TagEntity>()
            val resolved = tags.map { name ->
                tagBox.all.firstOrNull { it.name == name } ?: TagEntity(name = name).also { tagBox.put(it) }
            }
            entry.tags.addAll(resolved)
            boxStore.boxFor<EntryEntity>().put(entry)
        }
        return entry
    }

    private fun pattern(kind: PatternKind, signature: String): PatternEntity = PatternEntity(
        patternId = "x".repeat(64),
        kind = kind,
        signatureJson = signature,
        title = "t",
        firstSeenTimestamp = 1L,
        lastSeenTimestamp = 1L,
    )

    @Test
    fun `template_recurrence matches when entry label equals signature label`() {
        val entry = putEntry(templateLabel = TemplateLabel.AFTERMATH)
        val p = pattern(PatternKind.TEMPLATE_RECURRENCE, "{\"label\":\"aftermath\"}")
        assertTrue(PatternMatcher.matches(entry, p, ZoneOffset.UTC))
    }

    @Test
    fun `template_recurrence rejects label mismatch`() {
        val entry = putEntry(templateLabel = TemplateLabel.TUNNEL_EXIT)
        val p = pattern(PatternKind.TEMPLATE_RECURRENCE, "{\"label\":\"aftermath\"}")
        assertFalse(PatternMatcher.matches(entry, p, ZoneOffset.UTC))
    }

    @Test
    fun `tag_pair matches when entry has subset that contains the pair`() {
        val entry = putEntry(templateLabel = TemplateLabel.AFTERMATH, tags = listOf("standup", "crashed", "tuesday"))
        val p = pattern(
            PatternKind.TAG_PAIR_CO_OCCURRENCE,
            "{\"label\":\"aftermath\",\"tags\":[\"crashed\",\"standup\"]}",
        )
        assertTrue(PatternMatcher.matches(entry, p, ZoneOffset.UTC))
    }

    @Test
    fun `tag_pair rejects when one tag is missing`() {
        val entry = putEntry(templateLabel = TemplateLabel.AFTERMATH, tags = listOf("standup"))
        val p = pattern(
            PatternKind.TAG_PAIR_CO_OCCURRENCE,
            "{\"label\":\"aftermath\",\"tags\":[\"crashed\",\"standup\"]}",
        )
        assertFalse(PatternMatcher.matches(entry, p, ZoneOffset.UTC))
    }

    @Test
    fun `tag_pair rejects signatures with wrong number of tags`() {
        val entry = putEntry(templateLabel = TemplateLabel.AFTERMATH, tags = listOf("standup", "crashed", "tuesday"))
        val oneTag = pattern(
            PatternKind.TAG_PAIR_CO_OCCURRENCE,
            "{\"label\":\"aftermath\",\"tags\":[\"standup\"]}",
        )
        val threeTags = pattern(
            PatternKind.TAG_PAIR_CO_OCCURRENCE,
            "{\"label\":\"aftermath\",\"tags\":[\"standup\",\"crashed\",\"tuesday\"]}",
        )
        // A 1-tag signature would collapse to template_recurrence-ish behavior; a 3-tag
        // signature would over-constrain. Both indicate upstream corruption; matcher rejects.
        assertFalse(PatternMatcher.matches(entry, oneTag, ZoneOffset.UTC))
        assertFalse(PatternMatcher.matches(entry, threeTags, ZoneOffset.UTC))
    }

    @Test
    fun `tag_pair rejects malformed or incomplete signatures`() {
        val entry = putEntry(templateLabel = TemplateLabel.AFTERMATH, tags = listOf("standup", "crashed"))
        val malformed = pattern(PatternKind.TAG_PAIR_CO_OCCURRENCE, "{bad-json")
        val missingTags = pattern(PatternKind.TAG_PAIR_CO_OCCURRENCE, "{\"label\":\"aftermath\"}")

        assertFalse(PatternMatcher.matches(entry, malformed, ZoneOffset.UTC))
        assertFalse(PatternMatcher.matches(entry, missingTags, ZoneOffset.UTC))
    }

    @Test
    fun `goblin matches when entry timestamp falls in 0 to 5am local`() {
        val entry = putEntry(timestamp = Instant.parse("2026-05-11T02:00:00Z"))
        val p = pattern(PatternKind.TIME_OF_DAY_CLUSTER, "{\"bucket\":\"goblin\"}")
        assertTrue(PatternMatcher.matches(entry, p, ZoneOffset.UTC))
    }

    @Test
    fun `goblin rejects entries outside the band`() {
        val entry = putEntry(timestamp = Instant.parse("2026-05-11T12:00:00Z"))
        val p = pattern(PatternKind.TIME_OF_DAY_CLUSTER, "{\"bucket\":\"goblin\"}")
        assertFalse(PatternMatcher.matches(entry, p, ZoneOffset.UTC))
    }

    @Test
    fun `commitment matches when topic_or_person equals signature topic`() {
        val entry = putEntry(commitmentTopic = "Jamie")
        val p = pattern(PatternKind.COMMITMENT_RECURRENCE, "{\"topic_or_person\":\"jamie\"}")
        assertTrue(PatternMatcher.matches(entry, p, ZoneOffset.UTC))
    }

    @Test
    fun `commitment rejects when entry has no commitment field`() {
        val entry = putEntry()
        val p = pattern(PatternKind.COMMITMENT_RECURRENCE, "{\"topic_or_person\":\"jamie\"}")
        assertFalse(PatternMatcher.matches(entry, p, ZoneOffset.UTC))
    }

    @Test
    fun `commitment rejects malformed entry json and blank target topics`() {
        val malformedEntry = EntryEntity(
            entryText = "",
            statedCommitmentJson = "{bad-json",
            timestampEpochMs = Instant.parse("2026-05-11T12:00:00Z").toEpochMilli(),
        )
        boxStore.boxFor<EntryEntity>().put(malformedEntry)

        val blankTarget = pattern(PatternKind.COMMITMENT_RECURRENCE, "{}")
        val normalTarget = pattern(PatternKind.COMMITMENT_RECURRENCE, "{\"topic_or_person\":\"jamie\"}")

        assertFalse(PatternMatcher.matches(malformedEntry, normalTarget, ZoneOffset.UTC))
        assertFalse(PatternMatcher.matches(malformedEntry, blankTarget, ZoneOffset.UTC))
    }

    @Test
    fun `vocab matches when tag contains the token`() {
        val entry = putEntry(tags = listOf("tired"))
        val p = pattern(PatternKind.VOCAB_FREQUENCY, "{\"token\":\"tired\"}")
        assertTrue(PatternMatcher.matches(entry, p, ZoneOffset.UTC))
    }

    @Test
    fun `vocab matches when entry_text contains stemmed form`() {
        val entry = putEntry(text = "I am tireds again", tags = emptyList())
        val p = pattern(PatternKind.VOCAB_FREQUENCY, "{\"token\":\"tired\"}")
        assertTrue(PatternMatcher.matches(entry, p, ZoneOffset.UTC))
    }

    @Test
    fun `vocab rejects unrelated text`() {
        val entry = putEntry(text = "rested and great", tags = emptyList())
        val p = pattern(PatternKind.VOCAB_FREQUENCY, "{\"token\":\"tired\"}")
        assertFalse(PatternMatcher.matches(entry, p, ZoneOffset.UTC))
    }

    @Test
    fun `vocab rejects blank signature token`() {
        val entry = putEntry(text = "tired all day", tags = emptyList())
        val p = pattern(PatternKind.VOCAB_FREQUENCY, "{}")
        assertFalse(PatternMatcher.matches(entry, p, ZoneOffset.UTC))
    }

    @Test
    fun `vocab stems entry text via the shared TokenStemmer — no prefix-overreach`() {
        // `newscast` should NOT match the `news` signature — naive startsWith would let it through.
        val entry = putEntry(text = "I watched the newscast last night", tags = emptyList())
        val p = pattern(PatternKind.VOCAB_FREQUENCY, "{\"token\":\"news\"}")
        assertFalse(
            "preserved-surface tokens must not match longer words",
            PatternMatcher.matches(entry, p, ZoneOffset.UTC),
        )
    }

    @Test
    fun `vocab matches plural form when stemmer folds it`() {
        val entry = putEntry(text = "I had three meetings", tags = emptyList())
        val p = pattern(PatternKind.VOCAB_FREQUENCY, "{\"token\":\"meeting\"}")
        assertTrue(PatternMatcher.matches(entry, p, ZoneOffset.UTC))
    }

    @Test
    fun `vocab matches via alias fold so detection and matching stay aligned`() {
        // Detection alias-folds "drained" → "tired" when minting the pattern signature. The
        // matcher MUST do the same — otherwise the pattern that created itself from drained
        // entries can never match a subsequent drained entry, and callouts silently stop.
        val entry = putEntry(text = "drained again, every limb gave up at once", tags = emptyList())
        val p = pattern(PatternKind.VOCAB_FREQUENCY, "{\"token\":\"tired\"}")
        assertTrue(PatternMatcher.matches(entry, p, ZoneOffset.UTC))
    }

    @Test
    fun `vocab does not match below the MIN_VOCAB_LENGTH floor`() {
        // A 3-letter token must not satisfy a vocab signature even if it stems exactly.
        // Same floor as the text path — Codex P2 finding.
        val entry = putEntry(text = "low low low", tags = emptyList())
        val p = pattern(PatternKind.VOCAB_FREQUENCY, "{\"token\":\"low\"}")
        assertFalse(PatternMatcher.matches(entry, p, ZoneOffset.UTC))
    }

    @Test
    fun `temporal relative matches same weekday time block`() {
        val entry = putEntry(timestamp = Instant.parse("2026-05-19T14:00:00Z"))
        val p = pattern(
            PatternKind.TEMPORAL_RELATIVE,
            "{\"relation\":\"weekday_time_block\",\"day_of_week\":\"tuesday\",\"time_block\":\"afternoon\"}",
        )

        assertTrue(PatternMatcher.matches(entry, p, ZoneOffset.UTC))
    }

    @Test
    fun `temporal relative uses injected zone when matching weekday time block`() {
        val entry = putEntry(timestamp = Instant.parse("2026-05-19T21:00:00Z"))
        val p = pattern(
            PatternKind.TEMPORAL_RELATIVE,
            "{\"relation\":\"weekday_time_block\",\"day_of_week\":\"tuesday\",\"time_block\":\"afternoon\"}",
        )

        assertTrue(PatternMatcher.matches(entry, p, java.time.ZoneId.of("America/Los_Angeles")))
        assertFalse(PatternMatcher.matches(entry, p, ZoneOffset.UTC))
    }

    @Test
    fun `temporal relative rejects different weekday time block`() {
        val entry = putEntry(timestamp = Instant.parse("2026-05-19T09:00:00Z"))
        val p = pattern(
            PatternKind.TEMPORAL_RELATIVE,
            "{\"relation\":\"weekday_time_block\",\"day_of_week\":\"tuesday\",\"time_block\":\"afternoon\"}",
        )

        assertFalse(PatternMatcher.matches(entry, p, ZoneOffset.UTC))
    }

    @Test
    fun `temporal relative matches first of month`() {
        val entry = putEntry(timestamp = Instant.parse("2026-05-01T09:00:00Z"))
        val p = pattern(
            PatternKind.TEMPORAL_RELATIVE,
            "{\"relation\":\"month_start\",\"day_of_month\":1}",
        )

        assertTrue(PatternMatcher.matches(entry, p, ZoneOffset.UTC))
    }

    @Test
    fun `temporal relative uses injected zone when matching first of month`() {
        val entry = putEntry(timestamp = Instant.parse("2026-05-01T00:30:00Z"))
        val p = pattern(
            PatternKind.TEMPORAL_RELATIVE,
            "{\"relation\":\"month_start\",\"day_of_month\":1}",
        )

        assertTrue(PatternMatcher.matches(entry, p, ZoneOffset.UTC))
        assertFalse(PatternMatcher.matches(entry, p, java.time.ZoneId.of("America/Los_Angeles")))
    }

    @Test
    fun `temporal relative rejects malformed relation`() {
        val entry = putEntry(timestamp = Instant.parse("2026-05-01T09:00:00Z"))
        val p = pattern(PatternKind.TEMPORAL_RELATIVE, "{\"relation\":\"nonsense\"}")

        assertFalse(PatternMatcher.matches(entry, p, ZoneOffset.UTC))
    }
}
