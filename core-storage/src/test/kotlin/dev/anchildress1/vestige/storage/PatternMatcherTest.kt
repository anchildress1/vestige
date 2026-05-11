package dev.anchildress1.vestige.storage

import androidx.test.core.app.ApplicationProvider
import dev.anchildress1.vestige.model.PatternKind
import dev.anchildress1.vestige.model.TemplateLabel
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
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        dataDir = File(context.filesDir, "objectbox-matcher-${System.nanoTime()}")
        boxStore = VestigeBoxStore.openAt(dataDir)
    }

    @After
    fun tearDown() {
        boxStore.close()
        BoxStore.deleteAllFiles(dataDir)
    }

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
}
