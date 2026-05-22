package dev.anchildress1.vestige.storage

import dev.anchildress1.vestige.model.ExtractionStatus
import dev.anchildress1.vestige.model.PatternKind
import dev.anchildress1.vestige.model.TemplateLabel
import dev.anchildress1.vestige.testing.newInMemoryObjectBoxDirectory
import dev.anchildress1.vestige.testing.openInMemoryBoxStore
import io.objectbox.BoxStore
import io.objectbox.kotlin.boxFor
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class PatternDetectorTest {

    private lateinit var boxStore: BoxStore
    private lateinit var dataDir: File
    private lateinit var detector: PatternDetector
    private val now: Instant = Instant.parse("2026-05-11T12:00:00Z")
    private val clock: Clock = Clock.fixed(now, ZoneOffset.UTC)

    @Before
    fun setUp() {
        dataDir = newInMemoryObjectBoxDirectory("objectbox-detector-")
        boxStore = openInMemoryBoxStore(dataDir)
        detector = PatternDetector(boxStore, clock, zoneId = ZoneOffset.UTC)
    }

    @After
    fun tearDown() {
        boxStore.closeAfterCleaningThreadResources()
        BoxStore.deleteAllFiles(dataDir)
    }

    @Suppress("LongParameterList")
    private fun putEntry(
        text: String = "",
        templateLabel: TemplateLabel? = null,
        timestamp: Instant = now,
        tagNames: List<String> = emptyList(),
        commitmentTopic: String? = null,
        extractionStatus: ExtractionStatus = ExtractionStatus.COMPLETED,
        vocabularyWord: String? = null,
        vector: FloatArray? = null,
    ): EntryEntity {
        val tagBox = boxStore.boxFor<TagEntity>()
        val entry = EntryEntity(
            entryText = text,
            timestampEpochMs = timestamp.toEpochMilli(),
            templateLabel = templateLabel,
            statedCommitmentJson = commitmentTopic?.let { """{"topic_or_person":"$it","text":"do it"}""" },
            extractionStatus = extractionStatus,
            vocabularyWord = vocabularyWord,
            vector = vector,
        )
        boxStore.boxFor<EntryEntity>().put(entry)
        if (tagNames.isNotEmpty()) {
            val tagEntities = tagNames.map { name ->
                tagBox.all.firstOrNull { it.name == name }
                    ?: TagEntity(name = name).also { tagBox.put(it) }
            }
            entry.tags.addAll(tagEntities)
            boxStore.boxFor<EntryEntity>().put(entry)
        }
        return entry
    }

    // A vector pointed (mostly) along one coordinate axis — same construction the clustering
    // tests use so members of one axis fall inside the cosine cut and split from other axes.
    private fun nearVector(axis: Int, jitter: Double): FloatArray {
        val v = FloatArray(VOCAB_EMBED_DIM)
        v[axis] = 1.0f
        for (i in v.indices) v[i] = v[i] + jitter.toFloat() * ((i + 1) % 5 - 2) * 0.01f
        return v
    }

    // Vocab-candidate seam: vectored entry on [axis] (jittered per [seed]) with a tone word.
    private fun putVocab(word: String?, axis: Int, seed: Int): EntryEntity =
        putEntry(vocabularyWord = word, vector = nearVector(axis, seed * 0.001))

    private fun putRawCommitmentEntry(rawCommitmentJson: String): EntryEntity = EntryEntity(
        entryText = "",
        timestampEpochMs = now.toEpochMilli(),
        statedCommitmentJson = rawCommitmentJson,
        extractionStatus = ExtractionStatus.COMPLETED,
    ).also { boxStore.boxFor<EntryEntity>().put(it) }

    @Test
    fun `empty database produces no patterns`() {
        assertTrue(detector.detect().isEmpty())
    }

    @Test
    fun `template recurrence requires three matching entries`() {
        repeat(2) { putEntry(templateLabel = TemplateLabel.AFTERMATH) }
        assertTrue(detector.detect().none { it.kind == PatternKind.TEMPLATE_RECURRENCE })

        putEntry(templateLabel = TemplateLabel.AFTERMATH)
        val patterns = detector.detect().filter { it.kind == PatternKind.TEMPLATE_RECURRENCE }
        assertEquals(1, patterns.size)
        assertEquals(3, patterns.first().supportingEntryCount)
        assertEquals("aftermath", patterns.first().templateLabel)
    }

    @Test
    fun `audit fallback labels do not surface as template recurrence patterns`() {
        repeat(4) { putEntry(templateLabel = TemplateLabel.AUDIT) }

        assertTrue(
            "audit is fallback metadata and should not become a user-visible pattern",
            detector.detect().none {
                it.kind == PatternKind.TEMPLATE_RECURRENCE && it.templateLabel == TemplateLabel.AUDIT.serial
            },
        )
    }

    @Test
    fun `tag-pair co-occurrence enumerates sorted pairs and requires three entries`() {
        repeat(3) { putEntry(templateLabel = TemplateLabel.AFTERMATH, tagNames = listOf("standup", "crashed")) }
        val pair = detector.detect().single { it.kind == PatternKind.TAG_PAIR_CO_OCCURRENCE }
        assertEquals(3, pair.supportingEntryCount)
        // Tags inside the signature are normalized + sorted before hashing.
        assertTrue(pair.signatureJson.contains("[\"crashed\",\"standup\"]"))
    }

    @Test
    fun `tag-pair below threshold does not pattern`() {
        repeat(2) { putEntry(templateLabel = TemplateLabel.AFTERMATH, tagNames = listOf("standup", "crashed")) }
        assertNull(detector.detect().firstOrNull { it.kind == PatternKind.TAG_PAIR_CO_OCCURRENCE })
    }

    @Test
    fun `tag-pair supports a subset — an extra tag does not break it`() {
        putEntry(templateLabel = TemplateLabel.AFTERMATH, tagNames = listOf("standup", "crashed"))
        putEntry(templateLabel = TemplateLabel.AFTERMATH, tagNames = listOf("standup", "crashed", "tuesday"))
        putEntry(templateLabel = TemplateLabel.AFTERMATH, tagNames = listOf("standup", "crashed"))
        val pair = detector.detect().single {
            it.kind == PatternKind.TAG_PAIR_CO_OCCURRENCE &&
                it.signatureJson.contains("[\"crashed\",\"standup\"]")
        }
        assertEquals(3, pair.supportingEntryCount)
    }

    @Test
    fun `tag-pair ignores single-tag entries while still counting valid pairs`() {
        putEntry(templateLabel = TemplateLabel.AFTERMATH, tagNames = listOf("standup"))
        repeat(3) { putEntry(templateLabel = TemplateLabel.AFTERMATH, tagNames = listOf("standup", "crashed")) }
        val pair = detector.detect().single { it.kind == PatternKind.TAG_PAIR_CO_OCCURRENCE }
        assertEquals(3, pair.supportingEntryCount)
    }

    @Test
    fun `goblin hours pattern uses the 30-day window`() {
        // 3 entries between 00:00 and 04:59 UTC inside 30 days.
        val midnight = Instant.parse("2026-05-10T02:00:00Z")
        repeat(3) { i -> putEntry(timestamp = midnight.plusSeconds((i * 3600L))) }
        val pattern = detector.detect().single { it.kind == PatternKind.TIME_OF_DAY_CLUSTER }
        assertEquals(3, pattern.supportingEntryCount)
        assertNull("goblin pattern signature has no template label", pattern.templateLabel)
    }

    @Test
    fun `goblin hours below threshold does not pattern`() {
        val midnight = Instant.parse("2026-05-10T02:00:00Z")
        repeat(2) { i -> putEntry(timestamp = midnight.plusSeconds((i * 3600L))) }
        assertNull(detector.detect().firstOrNull { it.kind == PatternKind.TIME_OF_DAY_CLUSTER })
    }

    @Test
    fun `goblin hours excludes entries outside 30-day window`() {
        val oldGoblin = Instant.parse("2026-03-01T02:00:00Z") // > 30 days back
        repeat(3) { putEntry(timestamp = oldGoblin) }
        assertNull(detector.detect().firstOrNull { it.kind == PatternKind.TIME_OF_DAY_CLUSTER })
    }

    @Test
    fun `goblin hours ignores entries inside 30 days when they are outside the local hour band`() {
        val midday = Instant.parse("2026-05-10T12:00:00Z")
        repeat(3) { putEntry(timestamp = midday) }
        assertNull(detector.detect().firstOrNull { it.kind == PatternKind.TIME_OF_DAY_CLUSTER })
    }

    @Test
    fun `goblin hours excludes 5am boundary entries`() {
        val fiveAm = Instant.parse("2026-05-10T05:00:00Z")
        repeat(3) { putEntry(timestamp = fiveAm) }
        assertNull(detector.detect().firstOrNull { it.kind == PatternKind.TIME_OF_DAY_CLUSTER })
    }

    @Test
    fun `commitment recurrence groups by topic_or_person`() {
        repeat(3) { putEntry(commitmentTopic = "Jamie") }
        val pattern = detector.detect().single { it.kind == PatternKind.COMMITMENT_RECURRENCE }
        assertEquals(3, pattern.supportingEntryCount)
        assertTrue(pattern.signatureJson.contains("\"topic_or_person\":\"jamie\""))
    }

    @Test
    fun `commitment below threshold does not pattern`() {
        repeat(2) { putEntry(commitmentTopic = "Jamie") }
        assertNull(detector.detect().firstOrNull { it.kind == PatternKind.COMMITMENT_RECURRENCE })
    }

    @Test
    fun `malformed commitment json is ignored`() {
        repeat(3) { putRawCommitmentEntry("{bad json") }
        assertNull(detector.detect().firstOrNull { it.kind == PatternKind.COMMITMENT_RECURRENCE })
    }

    @Test
    fun `blank commitment topic is ignored`() {
        repeat(3) { putRawCommitmentEntry("""{"topic_or_person":"   ","text":"do it"}""") }
        assertNull(detector.detect().firstOrNull { it.kind == PatternKind.COMMITMENT_RECURRENCE })
    }

    @Test
    fun `blank commitment payload is ignored`() {
        repeat(3) { putRawCommitmentEntry("   ") }
        assertNull(detector.detect().firstOrNull { it.kind == PatternKind.COMMITMENT_RECURRENCE })
    }

    @Test
    fun `commitment recurrence normalizes topic variants into one signature`() {
        putEntry(commitmentTopic = "Jamie")
        putEntry(commitmentTopic = " jamie ")
        putEntry(commitmentTopic = "JAMIE")
        val pattern = detector.detect().single { it.kind == PatternKind.COMMITMENT_RECURRENCE }
        assertTrue(pattern.signatureJson.contains("\"topic_or_person\":\"jamie\""))
        assertEquals(3, pattern.supportingEntryCount)
    }

    @Test
    fun `vocab pattern mints one cluster keyed on the dominant tone word`() {
        // Six vectored entries on one semantic axis, all carrying a "tired"-family tone word →
        // one embedding cluster ≥ VOCAB_THRESHOLD → one VOCAB_FREQUENCY keyed on the dominant
        // word. Tone word, not a token count, is the identity.
        repeat(6) { i -> putVocab(word = "tired", axis = 0, seed = i) }
        val patterns = detector.detect().filter { it.kind == PatternKind.VOCAB_FREQUENCY }
        assertEquals(1, patterns.size)
        assertTrue(patterns.single().signatureJson.contains("\"token\":\"tired\""))
        assertEquals(6, patterns.single().supportingEntryCount)
    }

    @Test
    fun `vocab dominant word is the most frequent tone word — ties broken alphabetically`() {
        // 4 "tired" + 2 "wired" in one cluster → dominant is "tired" (frequency wins).
        repeat(4) { i -> putVocab(word = "tired", axis = 0, seed = i) }
        repeat(2) { i -> putVocab(word = "wired", axis = 0, seed = i + 4) }
        val pattern = detector.detect().single { it.kind == PatternKind.VOCAB_FREQUENCY }
        assertTrue(pattern.signatureJson.contains("\"token\":\"tired\""))
        assertEquals(6, pattern.supportingEntryCount)
    }

    @Test
    fun `vocab below the cluster threshold mints nothing`() {
        // Two semantic axes, three entries each → two clusters of 3, both below VOCAB_THRESHOLD
        // (4). No pattern even though six candidates exist.
        repeat(3) { i -> putVocab(word = "tired", axis = 0, seed = i) }
        repeat(3) { i -> putVocab(word = "wired", axis = 1, seed = i) }
        assertNull(detector.detect().firstOrNull { it.kind == PatternKind.VOCAB_FREQUENCY })
    }

    @Test
    fun `vocab ignores entries without a tone word`() {
        // Vectored, well-clustered, but no model-emitted tone word → not a candidate.
        repeat(6) { i -> putVocab(word = null, axis = 0, seed = i) }
        assertNull(detector.detect().firstOrNull { it.kind == PatternKind.VOCAB_FREQUENCY })
    }

    @Test
    fun `vocab ignores entries without a usable vector`() {
        // Tone word present but no vector → clustering drops them, so no candidates survive.
        repeat(6) { putEntry(vocabularyWord = "tired", vector = null) }
        assertNull(detector.detect().firstOrNull { it.kind == PatternKind.VOCAB_FREQUENCY })
    }

    @Test
    fun `vocab tone-word variants collapse into one canonical token`() {
        // Casing + plural variants of the same tone word all canonicalize to "tired" — the
        // dominant-word grouping must count them as one, not three competing keys.
        repeat(2) { i -> putVocab(word = "Tired", axis = 0, seed = i) }
        repeat(2) { i -> putVocab(word = "tireds", axis = 0, seed = i + 2) }
        repeat(2) { i -> putVocab(word = "tired", axis = 0, seed = i + 4) }
        val pattern = detector.detect().single { it.kind == PatternKind.VOCAB_FREQUENCY }
        assertTrue(pattern.signatureJson.contains("\"token\":\"tired\""))
        assertEquals(6, pattern.supportingEntryCount)
    }

    @Test
    fun `future-dated entries are excluded from windows`() {
        // Clock-skewed or manually-edited future timestamps would otherwise satisfy
        // `nowMs - timestamp <= window` (negative delta) and count toward thresholds.
        val future = now.plusSeconds(60 * 60 * 24) // tomorrow
        repeat(5) { putEntry(templateLabel = TemplateLabel.AFTERMATH, timestamp = future) }
        assertTrue(detector.detect().none { it.kind == PatternKind.TEMPLATE_RECURRENCE })
    }

    @Test
    fun `entries outside 90-day window do not count`() {
        val ancient = Instant.parse("2025-01-01T12:00:00Z")
        repeat(5) { putEntry(templateLabel = TemplateLabel.AFTERMATH, timestamp = ancient) }
        assertTrue(detector.detect().isEmpty())
    }

    @Test
    fun `pattern_id is deterministic — same data, same IDs`() {
        repeat(3) { putEntry(templateLabel = TemplateLabel.AFTERMATH) }
        val first = detector.detect().map { it.patternId }.sorted()
        val second = detector.detect().map { it.patternId }.sorted()
        assertEquals(first, second)
    }

    @Test
    fun `pattern_id is content-addressable — different tag pairs produce different IDs`() {
        repeat(3) { putEntry(templateLabel = TemplateLabel.AFTERMATH, tagNames = listOf("standup", "crashed")) }
        repeat(3) { putEntry(templateLabel = TemplateLabel.AFTERMATH, tagNames = listOf("standup", "flattened")) }
        val pairIds = detector.detect()
            .filter { it.kind == PatternKind.TAG_PAIR_CO_OCCURRENCE }
            .map { it.patternId }
            .toSet()
        // Three pairs land: (crashed, standup), (flattened, standup), and either of those that
        // co-occur is irrelevant — we just confirm each distinct signature produces a distinct id.
        assertTrue("Each distinct signature produces a distinct id (got ${pairIds.size})", pairIds.size >= 2)
    }

    @Test
    fun `tag-pair detection respects template label boundary`() {
        // Same tags but different template labels — pairs are scoped to the label.
        repeat(3) { putEntry(templateLabel = TemplateLabel.AFTERMATH, tagNames = listOf("standup", "crashed")) }
        putEntry(templateLabel = TemplateLabel.TUNNEL_EXIT, tagNames = listOf("standup", "crashed"))
        val pairs = detector.detect().filter { it.kind == PatternKind.TAG_PAIR_CO_OCCURRENCE }
        // Only the AFTERMATH bucket has ≥3 — the TUNNEL_EXIT entry sits below threshold.
        assertEquals(1, pairs.size)
        assertEquals("aftermath", pairs.first().templateLabel)
    }

    @Test
    fun `supportingEntryIds are sorted ascending and reflect the box ids`() {
        val a = putEntry(templateLabel = TemplateLabel.AFTERMATH)
        val b = putEntry(templateLabel = TemplateLabel.AFTERMATH)
        val c = putEntry(templateLabel = TemplateLabel.AFTERMATH)
        val pattern = detector.detect().single { it.kind == PatternKind.TEMPLATE_RECURRENCE }
        assertEquals(listOf(a.id, b.id, c.id).sorted(), pattern.supportingEntryIds)
    }

    @Test
    fun `firstSeen and lastSeen come from supporting timestamps`() {
        val oldest = now.minusSeconds(60 * 60 * 24 * 5)
        val newest = now.minusSeconds(60 * 60 * 24)
        putEntry(templateLabel = TemplateLabel.AFTERMATH, timestamp = oldest)
        putEntry(templateLabel = TemplateLabel.AFTERMATH, timestamp = newest)
        putEntry(templateLabel = TemplateLabel.AFTERMATH, timestamp = newest)
        val pattern = detector.detect().single { it.kind == PatternKind.TEMPLATE_RECURRENCE }
        assertEquals(oldest.toEpochMilli(), pattern.firstSeenTimestamp)
        assertEquals(newest.toEpochMilli(), pattern.lastSeenTimestamp)
    }

    @Test
    fun `signature json includes the kind discriminator`() {
        repeat(3) { putEntry(templateLabel = TemplateLabel.AFTERMATH) }
        val pattern = detector.detect().single { it.kind == PatternKind.TEMPLATE_RECURRENCE }
        assertNotNull(pattern.signatureJson)
        assertTrue(pattern.signatureJson.contains("\"kind\":\"template_recurrence\""))
    }

    @Test
    fun `failed and timed-out entries are excluded from supporting sets`() {
        repeat(2) { putEntry(templateLabel = TemplateLabel.AFTERMATH) }
        putEntry(
            templateLabel = TemplateLabel.AFTERMATH,
            extractionStatus = ExtractionStatus.FAILED,
        )
        putEntry(
            templateLabel = TemplateLabel.AFTERMATH,
            extractionStatus = ExtractionStatus.TIMED_OUT,
        )

        assertTrue(
            "only completed entries should count toward template recurrence",
            detector.detect().none { it.kind == PatternKind.TEMPLATE_RECURRENCE },
        )
    }

    @Test
    fun `detector uses injected zone for goblin window — non-UTC zone shifts the band`() {
        val pacific = ZoneId.of("America/Los_Angeles")
        val detectorPst = PatternDetector(boxStore, clock, zoneId = pacific)
        // May → PDT (UTC-7). 09:00 UTC = 02:00 PDT → in goblin window under PDT, not under UTC.
        val utcMorning = Instant.parse("2026-05-10T09:00:00Z")
        repeat(3) { putEntry(timestamp = utcMorning) }
        val patterns = detectorPst.detect().filter { it.kind == PatternKind.TIME_OF_DAY_CLUSTER }
        assertEquals(1, patterns.size)
        // UTC detector treats 09:00 UTC as out-of-window — same data, no pattern.
        assertTrue(detector.detect().none { it.kind == PatternKind.TIME_OF_DAY_CLUSTER })
    }

    @Test
    fun `temporal relative detects same weekday afternoon across distinct weeks`() {
        val laterClock = Clock.fixed(Instant.parse("2026-05-20T12:00:00Z"), ZoneOffset.UTC)
        val laterDetector = PatternDetector(boxStore, laterClock, zoneId = ZoneOffset.UTC)
        putEntry(timestamp = Instant.parse("2026-05-05T14:00:00Z"))
        putEntry(timestamp = Instant.parse("2026-05-12T15:30:00Z"))
        putEntry(timestamp = Instant.parse("2026-05-19T12:15:00Z"))

        val pattern = laterDetector.detect().single {
            it.kind == PatternKind.TEMPORAL_RELATIVE &&
                it.signatureJson.contains("\"relation\":\"weekday_time_block\"")
        }

        assertEquals(3, pattern.supportingEntryCount)
        assertTrue(pattern.signatureJson.contains("\"day_of_week\":\"tuesday\""))
        assertTrue(pattern.signatureJson.contains("\"time_block\":\"afternoon\""))
    }

    @Test
    fun `temporal relative weekday block uses injected zone when local hour differs from UTC`() {
        val pacific = ZoneId.of("America/Los_Angeles")
        val laterClock = Clock.fixed(Instant.parse("2026-05-20T12:00:00Z"), ZoneOffset.UTC)
        val pacificDetector = PatternDetector(boxStore, laterClock, zoneId = pacific)
        val utcDetector = PatternDetector(boxStore, laterClock, zoneId = ZoneOffset.UTC)
        putEntry(timestamp = Instant.parse("2026-05-05T21:00:00Z"))
        putEntry(timestamp = Instant.parse("2026-05-12T21:30:00Z"))
        putEntry(timestamp = Instant.parse("2026-05-19T22:00:00Z"))

        val pacificPattern = pacificDetector.detect().single {
            it.kind == PatternKind.TEMPORAL_RELATIVE &&
                it.signatureJson.contains("\"relation\":\"weekday_time_block\"")
        }

        assertTrue(pacificPattern.signatureJson.contains("\"day_of_week\":\"tuesday\""))
        assertTrue(pacificPattern.signatureJson.contains("\"time_block\":\"afternoon\""))
        assertNull(
            utcDetector.detect().firstOrNull {
                it.kind == PatternKind.TEMPORAL_RELATIVE &&
                    it.signatureJson.contains("\"time_block\":\"afternoon\"")
            },
        )
    }

    @Test
    fun `temporal relative requires distinct dates for weekday time blocks`() {
        val laterClock = Clock.fixed(Instant.parse("2026-05-20T12:00:00Z"), ZoneOffset.UTC)
        val laterDetector = PatternDetector(boxStore, laterClock, zoneId = ZoneOffset.UTC)
        repeat(3) { putEntry(timestamp = Instant.parse("2026-05-19T14:00:00Z")) }

        assertNull(
            laterDetector.detect().firstOrNull {
                it.kind == PatternKind.TEMPORAL_RELATIVE &&
                    it.signatureJson.contains("\"relation\":\"weekday_time_block\"")
            },
        )
    }

    @Test
    fun `temporal relative keeps weekday time blocks separate`() {
        val laterClock = Clock.fixed(Instant.parse("2026-05-20T12:00:00Z"), ZoneOffset.UTC)
        val laterDetector = PatternDetector(boxStore, laterClock, zoneId = ZoneOffset.UTC)
        putEntry(timestamp = Instant.parse("2026-05-05T14:00:00Z"))
        putEntry(timestamp = Instant.parse("2026-05-12T15:30:00Z"))
        putEntry(timestamp = Instant.parse("2026-05-19T09:15:00Z"))

        assertNull(
            laterDetector.detect().firstOrNull {
                it.kind == PatternKind.TEMPORAL_RELATIVE &&
                    it.signatureJson.contains("\"relation\":\"weekday_time_block\"")
            },
        )
    }

    @Test
    fun `temporal relative detects first-of-month across distinct months`() {
        val laterClock = Clock.fixed(Instant.parse("2026-05-20T12:00:00Z"), ZoneOffset.UTC)
        val laterDetector = PatternDetector(boxStore, laterClock, zoneId = ZoneOffset.UTC)
        putEntry(timestamp = Instant.parse("2026-03-01T09:00:00Z"))
        putEntry(timestamp = Instant.parse("2026-04-01T11:00:00Z"))
        putEntry(timestamp = Instant.parse("2026-05-01T16:00:00Z"))

        val pattern = laterDetector.detect().single {
            it.kind == PatternKind.TEMPORAL_RELATIVE &&
                it.signatureJson.contains("\"relation\":\"month_start\"")
        }

        assertEquals(3, pattern.supportingEntryCount)
        assertTrue(pattern.signatureJson.contains("\"day_of_month\":1"))
    }

    @Test
    fun `temporal relative month-start uses injected zone when local date differs from UTC`() {
        val pacific = ZoneId.of("America/Los_Angeles")
        val laterClock = Clock.fixed(Instant.parse("2026-05-20T12:00:00Z"), ZoneOffset.UTC)
        val pacificDetector = PatternDetector(boxStore, laterClock, zoneId = pacific)
        val utcDetector = PatternDetector(boxStore, laterClock, zoneId = ZoneOffset.UTC)
        putEntry(timestamp = Instant.parse("2026-03-01T00:30:00Z"))
        putEntry(timestamp = Instant.parse("2026-04-01T00:30:00Z"))
        putEntry(timestamp = Instant.parse("2026-05-01T00:30:00Z"))

        assertNotNull(
            utcDetector.detect().firstOrNull {
                it.kind == PatternKind.TEMPORAL_RELATIVE &&
                    it.signatureJson.contains("\"relation\":\"month_start\"")
            },
        )
        assertNull(
            pacificDetector.detect().firstOrNull {
                it.kind == PatternKind.TEMPORAL_RELATIVE &&
                    it.signatureJson.contains("\"relation\":\"month_start\"")
            },
        )
    }

    @Test
    fun `temporal relative requires distinct months for first-of-month`() {
        val laterClock = Clock.fixed(Instant.parse("2026-05-20T12:00:00Z"), ZoneOffset.UTC)
        val laterDetector = PatternDetector(boxStore, laterClock, zoneId = ZoneOffset.UTC)
        repeat(3) { putEntry(timestamp = Instant.parse("2026-05-01T09:00:00Z")) }

        assertNull(
            laterDetector.detect().firstOrNull {
                it.kind == PatternKind.TEMPORAL_RELATIVE &&
                    it.signatureJson.contains("\"relation\":\"month_start\"")
            },
        )
    }

    private companion object {
        // Small enough for fast tests, large enough that cosine separation is meaningful.
        const val VOCAB_EMBED_DIM: Int = 32
    }
}
