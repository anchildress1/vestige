package dev.anchildress1.vestige.storage

import androidx.test.core.app.ApplicationProvider
import dev.anchildress1.vestige.model.PatternKind
import dev.anchildress1.vestige.model.TemplateLabel
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
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        dataDir = File(context.filesDir, "objectbox-detector-${System.nanoTime()}")
        boxStore = VestigeBoxStore.openAt(dataDir)
        detector = PatternDetector(boxStore, clock, zoneId = ZoneOffset.UTC)
    }

    @After
    fun tearDown() {
        boxStore.close()
        BoxStore.deleteAllFiles(dataDir)
    }

    private fun putEntry(
        text: String = "",
        templateLabel: TemplateLabel? = null,
        timestamp: Instant = now,
        tagNames: List<String> = emptyList(),
        commitmentTopic: String? = null,
    ): EntryEntity {
        val tagBox = boxStore.boxFor<TagEntity>()
        val entry = EntryEntity(
            entryText = text,
            timestampEpochMs = timestamp.toEpochMilli(),
            templateLabel = templateLabel,
            statedCommitmentJson = commitmentTopic?.let { """{"topic_or_person":"$it","text":"do it"}""" },
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
    fun `tag-pair co-occurrence enumerates sorted pairs and requires three entries`() {
        repeat(3) { putEntry(templateLabel = TemplateLabel.AFTERMATH, tagNames = listOf("standup", "crashed")) }
        val pair = detector.detect().single { it.kind == PatternKind.TAG_PAIR_CO_OCCURRENCE }
        assertEquals(3, pair.supportingEntryCount)
        // Tags inside the signature are normalized + sorted before hashing.
        assertTrue(pair.signatureJson.contains("[\"crashed\",\"standup\"]"))
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
    fun `goblin hours pattern uses the 30-day window`() {
        // 3 entries between 00:00 and 04:59 UTC inside 30 days.
        val midnight = Instant.parse("2026-05-10T02:00:00Z")
        repeat(3) { i -> putEntry(timestamp = midnight.plusSeconds((i * 3600L))) }
        val pattern = detector.detect().single { it.kind == PatternKind.TIME_OF_DAY_CLUSTER }
        assertEquals(3, pattern.supportingEntryCount)
        assertNull("goblin pattern signature has no template label", pattern.templateLabel)
    }

    @Test
    fun `goblin hours excludes entries outside 30-day window`() {
        val oldGoblin = Instant.parse("2026-03-01T02:00:00Z") // > 30 days back
        repeat(3) { putEntry(timestamp = oldGoblin) }
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
    fun `vocab pattern requires four entries and two distinct template contexts`() {
        // 4 entries containing "tired" but all under one template label → does not pattern.
        repeat(4) { putEntry(text = "I am tired again", templateLabel = TemplateLabel.AFTERMATH) }
        assertNull(detector.detect().firstOrNull { it.kind == PatternKind.VOCAB_FREQUENCY })

        // Mix in a second context (different template label) → now ≥2 distinct contexts → fires.
        putEntry(text = "tired all morning", templateLabel = TemplateLabel.TUNNEL_EXIT)
        val patterns = detector.detect().filter { it.kind == PatternKind.VOCAB_FREQUENCY }
        assertTrue(patterns.any { it.signatureJson.contains("\"token\":\"tired\"") })
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
}
