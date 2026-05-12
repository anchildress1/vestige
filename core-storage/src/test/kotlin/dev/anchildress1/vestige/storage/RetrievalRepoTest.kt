package dev.anchildress1.vestige.storage

import androidx.test.core.app.ApplicationProvider
import io.objectbox.BoxStore
import io.objectbox.kotlin.boxFor
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class RetrievalRepoTest {

    private lateinit var boxStore: BoxStore
    private lateinit var dataDir: File
    private lateinit var repo: RetrievalRepo
    private val now: Instant = Instant.parse("2026-05-11T12:00:00Z")
    private val clock: Clock = Clock.fixed(now, ZoneOffset.UTC)

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        dataDir = File(context.filesDir, "objectbox-retrieval-${System.nanoTime()}")
        boxStore = VestigeBoxStore.openAt(dataDir)
        repo = RetrievalRepo(boxStore, clock)
    }

    @After
    fun tearDown() {
        boxStore.close()
        BoxStore.deleteAllFiles(dataDir)
    }

    @Test
    fun `empty database returns empty list`() {
        assertTrue(repo.query("anything").isEmpty())
    }

    @Test
    fun `single keyword match surfaces only that entry`() {
        insertEntry("standup crashed me again", daysAgo = 1)
        insertEntry("groceries and laundry", daysAgo = 1)

        val results = repo.query("crashed standup")

        assertEquals(1, results.size)
        assertEquals("standup crashed me again", results.single().entryText)
    }

    @Test
    fun `blank query returns empty list`() {
        insertEntry("standup crashed me again", daysAgo = 1)

        assertTrue(repo.query("").isEmpty())
        assertTrue(repo.query("   ").isEmpty())
    }

    @Test
    fun `no matches returns empty list not nulls`() {
        insertEntry("groceries", daysAgo = 1)
        insertEntry("laundry", daysAgo = 2)

        val results = repo.query("racquetball")

        assertEquals(0, results.size)
    }

    @Test
    fun `ranking prefers higher keyword overlap`() {
        val better = insertEntry("standup crashed me", daysAgo = 5)
        val worse = insertEntry("crashed in traffic", daysAgo = 5)

        val results = repo.query("standup crashed")

        assertEquals(listOf(better, worse), results.map { it.id })
    }

    @Test
    fun `tag overlap surfaces an entry even with zero keyword overlap`() {
        val tagged = insertEntry("unrelated body text", daysAgo = 10, tagNames = listOf("standup"))
        insertEntry("unrelated body text", daysAgo = 10)

        val results = repo.query("standup")

        assertEquals(listOf(tagged), results.map { it.id })
    }

    @Test
    fun `keyword and tag signals stack — both surface, combined score wins`() {
        // Same query token "standup" — tagged entry pays Jaccard, body entry pays keyword.
        val tagOnly = insertEntry("nothing matches here", daysAgo = 5, tagNames = listOf("standup"))
        val bodyOnly = insertEntry("standup wrecked me", daysAgo = 5)

        val results = repo.query("standup")

        assertEquals(2, results.size)
        // tag-only: keyword=0, tagJaccard=1.0 → score 1.0 + recency
        // body-only: keyword=1.0 (1/1), tagJaccard=0 (queryTags={standup}, entryTags={}) → score 1.0 + recency
        // Identical scores → id-asc breaks to tagOnly (inserted first).
        assertEquals(listOf(tagOnly, bodyOnly), results.map { it.id })
    }

    @Test
    fun `space-separated query matches kebab-case stored tags`() {
        val tagged = insertEntry("unrelated body text", daysAgo = 5, tagNames = listOf("tuesday-meeting"))
        insertEntry("unrelated body text", daysAgo = 5, tagNames = listOf("laundry"))

        val results = repo.query("tuesday meeting")

        assertEquals(listOf(tagged), results.map { it.id })
    }

    @Test
    fun `singular query matches plural stored tag surface`() {
        val tagged = insertEntry("unrelated body text", daysAgo = 5, tagNames = listOf("meetings"))
        insertEntry("unrelated body text", daysAgo = 5, tagNames = listOf("laundry"))

        val results = repo.query("meeting")

        assertEquals(listOf(tagged), results.map { it.id })
    }

    @Test
    fun `recency breaks ties when keyword and tag signals match`() {
        val recent = insertEntry("crashed after standup", daysAgo = 1)
        val older = insertEntry("crashed after standup", daysAgo = 60)

        val results = repo.query("standup")

        assertEquals(listOf(recent, older), results.map { it.id })
    }

    @Test
    fun `recency alone never surfaces an unmatched entry`() {
        insertEntry("brand new entry text", daysAgo = 0)

        assertTrue(repo.query("totally-unrelated-token").isEmpty())
    }

    @Test
    fun `topN caps the result size`() {
        repeat(5) { i -> insertEntry("standup crashed ${'a' + i}", daysAgo = i) }

        val results = repo.query("standup", topN = 2)

        assertEquals(2, results.size)
    }

    @Test
    fun `ties on score break by entry id ascending`() {
        // Identical text, identical recency, no tags → identical score; lower id wins.
        val first = insertEntry("standup again", daysAgo = 5)
        val second = insertEntry("standup again", daysAgo = 5)

        val results = repo.query("standup")

        assertEquals(listOf(first, second), results.map { it.id })
    }

    @Test
    fun `recencyWeight flips ordering on the same fixture`() {
        val older = insertEntry("standup crashed", daysAgo = 60)
        val recent = insertEntry("standup crashed", daysAgo = 1)

        // With recency disabled, scores tie → id-asc → older first (inserted first).
        val noRecency = repo.query("standup", recencyWeight = 0f)
        assertEquals(listOf(older, recent), noRecency.map { it.id })

        // With the default 0.3 weight, recent's recency boost flips ordering.
        val withRecency = repo.query("standup")
        assertEquals(listOf(recent, older), withRecency.map { it.id })
    }

    @Test
    fun `query is deterministic for repeated invocations`() {
        insertEntry("standup crashed", daysAgo = 2)
        insertEntry("standup wired", daysAgo = 3)
        insertEntry("crashed traffic", daysAgo = 1)

        val first = repo.query("standup crashed")
        val second = repo.query("standup crashed")

        assertEquals(first.map { it.id }, second.map { it.id })
    }

    @Test
    fun `query rejects non-positive topN`() {
        assertThrows(IllegalArgumentException::class.java) { repo.query("x", topN = 0) }
        assertThrows(IllegalArgumentException::class.java) { repo.query("x", topN = -1) }
    }

    @Test
    fun `query rejects out-of-range recencyWeight`() {
        assertThrows(IllegalArgumentException::class.java) { repo.query("x", recencyWeight = -0.1f) }
        assertThrows(IllegalArgumentException::class.java) { repo.query("x", recencyWeight = 1.1f) }
    }

    @Test
    fun `ss-guarded tags stay unstemmed — query 'kis' does not bridge to 'kiss'`() {
        val kiss = insertEntry("unrelated body text", daysAgo = 5, tagNames = listOf("kiss"))
        insertEntry("unrelated body text", daysAgo = 5, tagNames = listOf("laundry"))

        // 'kiss' (len > MIN_STEM_LENGTH, ends with 'ss') exits the stemmer unchanged.
        assertEquals(listOf(kiss), repo.query("kiss").map { it.id })
        assertTrue(repo.query("kis").isEmpty())
    }

    @Test
    fun `is-guarded tags stay unstemmed — query 'thi' does not bridge to 'this'`() {
        val thisTag = insertEntry("unrelated body text", daysAgo = 5, tagNames = listOf("this"))
        insertEntry("unrelated body text", daysAgo = 5, tagNames = listOf("laundry"))

        // 'this' (len > MIN_STEM_LENGTH, ends with 'is') exits the stemmer unchanged.
        assertEquals(listOf(thisTag), repo.query("this").map { it.id })
        assertTrue(repo.query("thi").isEmpty())
    }

    @Test
    fun `us-guarded tags stay unstemmed — query 'bu' does not bridge to 'bus'`() {
        // 'bus' is length 3 — guarded by MIN_STEM_LENGTH alone. 'cactus' would exercise the
        // 'us'-suffix guard, but the demo vocabulary has no Latin plurals worth testing here.
        val bus = insertEntry("unrelated body text", daysAgo = 5, tagNames = listOf("bus"))
        insertEntry("unrelated body text", daysAgo = 5, tagNames = listOf("laundry"))

        assertEquals(listOf(bus), repo.query("bus").map { it.id })
        assertTrue(repo.query("bu").isEmpty())
    }

    @Test
    fun `ADR-002 preserved surfaces — query 'new' must not bridge to stored 'news'`() {
        insertEntry("unrelated body text", daysAgo = 5, tagNames = listOf("news"))
        insertEntry("unrelated body text", daysAgo = 5, tagNames = listOf("laundry"))

        // ADR-002 §"Plural folding addendum" names 'news' as a corruption case (naive stem → 'new').
        // The PRESERVED_SURFACES exception keeps the surface form intact on both sides of the compare.
        assertTrue(repo.query("new").isEmpty())
    }

    @Test
    fun `ADR-002 preserved surfaces — query 'sery' must not bridge to stored 'series'`() {
        insertEntry("unrelated body text", daysAgo = 5, tagNames = listOf("series"))
        insertEntry("unrelated body text", daysAgo = 5, tagNames = listOf("laundry"))

        // 'series' would corrupt to 'sery' under the ies→y rule; the PRESERVED_SURFACES exception
        // exits the stemmer before any rules apply.
        assertTrue(repo.query("sery").isEmpty())
    }

    @Test
    fun `ie-singular and ies-plural fold via trailing-s drop — movies bridges to movie`() {
        // Codex review P1: the previous ies→y rule corrupted 'movies' to 'movy'. Trailing-s drop
        // matches 'movie' to 'movies' on both directions without that corruption.
        val moviesTag = insertEntry("unrelated body text", daysAgo = 5, tagNames = listOf("movies"))
        val movieTag = insertEntry("unrelated body text", daysAgo = 5, tagNames = listOf("movie"))

        // Query in either direction surfaces both entries (same stem 'movie').
        val plural = repo.query("movies").map { it.id }
        val singular = repo.query("movie").map { it.id }
        assertTrue("expected $movieTag in $plural", movieTag in plural)
        assertTrue("expected $moviesTag in $plural", moviesTag in plural)
        assertTrue("expected $movieTag in $singular", movieTag in singular)
        assertTrue("expected $moviesTag in $singular", moviesTag in singular)
    }

    @Test
    fun `lowercasing uses Locale_ROOT and survives Turkish-style I quirks`() {
        // Codex review P2: default locale on Turkish locales folds 'I' to 'ı' (dotless i), breaking
        // round-trips. Locale.ROOT keeps tokenization device-independent.
        val tagged = insertEntry("Important Notes", daysAgo = 5, tagNames = listOf("notes"))
        insertEntry("plain body", daysAgo = 5)

        // Query with uppercase 'I' must match the lowercased body token 'important'.
        val results = repo.query("IMPORTANT")
        assertEquals(listOf(tagged), results.map { it.id })
    }

    @Test
    fun `plural-singular tag pair scores 1_0 on comparison keys`() {
        // Both 'meeting' and 'meetings' coexist as separate TagEntity rows — Copilot review #2.
        // tagScore must be computed on stems, so the entry tagged only 'meeting' still gets a
        // perfect overlap against the query 'meetings'.
        val meeting = insertEntry("unrelated body text", daysAgo = 5, tagNames = listOf("meeting"))
        // Seed the second stored tag on an unrelated entry so it lives in tagBox.all.
        insertEntry("crash today", daysAgo = 5, tagNames = listOf("meetings"))

        // Query "meeting" with stored vocab {meeting, meetings} — entry tagged only 'meeting'
        // must still surface; under surface-form Jaccard it would have scored ~0.5.
        val results = repo.query("meeting")
        assertTrue("expected $meeting in results, got ${results.map { it.id }}", meeting in results.map { it.id })
    }

    @Test
    fun `stored tag surface forms are never mutated by query stemming`() {
        // ADR-002 §"Plural folding addendum" requires comparison-only normalization — the stored
        // tag entity must keep its original surface form even when surfaced via a stemmed query.
        val newsId = insertEntry("unrelated body text", daysAgo = 5, tagNames = listOf("news"))
        val seriesId = insertEntry("unrelated body text", daysAgo = 5, tagNames = listOf("series"))

        repo.query("news") // stems internally; must not write back
        repo.query("series")

        val storedTags = boxStore.boxFor<TagEntity>().all.map { it.name }.toSet()
        assertTrue("'news' must survive stemming intact", "news" in storedTags)
        assertTrue("'series' must survive stemming intact", "series" in storedTags)
        // Verify the entities themselves still carry the original names through the relation.
        val newsEntry = boxStore.boxFor<EntryEntity>().get(newsId)
        val seriesEntry = boxStore.boxFor<EntryEntity>().get(seriesId)
        assertEquals(listOf("news"), newsEntry.tags.map { it.name })
        assertEquals(listOf("series"), seriesEntry.tags.map { it.name })
    }

    @Test
    fun `queryHybrid surfaces vocabulary-drift match the keyword-only path misses`() = runBlocking {
        // The aftermath entry shares only stop-words with the query but its vector is far closer
        // than the literal-keyword distractor. queryHybrid should rank it first.
        val aftermath = insertEntry("battery got yanked after the sync", daysAgo = 3)
        val distractor = insertEntry("battery died on my keyboard", daysAgo = 1)

        val embedder = fixedEmbedder(
            "post-meeting crash" to floatArrayOf(1f, 0f, 0f),
            "battery got yanked after the sync" to floatArrayOf(0.95f, 0.1f, 0f),
            "battery died on my keyboard" to floatArrayOf(0.1f, 0.99f, 0f),
        )

        val baseline = repo.query("post-meeting crash")
        assertTrue("tag-only baseline misses vocabulary-drift entries", baseline.isEmpty())

        val hybrid = repo.queryHybrid("post-meeting crash", embedder, topN = 5)

        assertEquals(listOf(aftermath, distractor), hybrid.map { it.id })
    }

    @Test
    fun `queryHybrid blank text returns empty without invoking the embedder`() = runBlocking {
        insertEntry("standup crashed", daysAgo = 1)
        var calls = 0
        val embedder: suspend (String) -> FloatArray = { calls++; floatArrayOf(1f) }

        assertTrue(repo.queryHybrid("   ", embedder).isEmpty())
        assertEquals(0, calls)
    }

    @Test
    fun `queryHybrid rejects negative embeddingWeight`() {
        val embedder: suspend (String) -> FloatArray = { floatArrayOf(1f) }
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { repo.queryHybrid("x", embedder, embeddingWeight = -0.1f) }
        }
    }

    @Test
    fun `queryHybrid zero-weight collapses to keyword+tag+recency`() = runBlocking {
        // With embeddingWeight=0 the cosine term contributes nothing — ordering must match query().
        insertEntry("standup crashed me", daysAgo = 2)
        insertEntry("standup wired me", daysAgo = 1)
        val embedder = fixedEmbedder(
            "standup" to floatArrayOf(1f, 0f),
            "standup crashed me" to floatArrayOf(0f, 1f), // far from query
            "standup wired me" to floatArrayOf(1f, 0f),   // identical to query — would dominate
        )

        val classic = repo.query("standup").map { it.id }
        val hybrid = repo.queryHybrid("standup", embedder, embeddingWeight = 0f).map { it.id }

        assertEquals(classic, hybrid)
    }

    private fun fixedEmbedder(vararg pairs: Pair<String, FloatArray>): suspend (String) -> FloatArray {
        val table = pairs.toMap()
        return { text ->
            table[text]
                ?: error("test fixture missing embedding for \"$text\" (have ${table.keys})")
        }
    }

    @Test
    fun `queryHybrid rejects vectors with mismatched dimensions`() {
        insertEntry("standup crashed", daysAgo = 1)
        val embedder: suspend (String) -> FloatArray = { text ->
            if (text == "standup") floatArrayOf(1f, 0f) else floatArrayOf(1f, 0f, 0f)
        }
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { repo.queryHybrid("standup", embedder) }
        }
    }

    private fun insertEntry(text: String, daysAgo: Int, tagNames: List<String> = emptyList()): Long {
        val ts = now.minusSeconds(daysAgo * SECONDS_PER_DAY).toEpochMilli()
        val entryBox = boxStore.boxFor<EntryEntity>()
        val tagBox = boxStore.boxFor<TagEntity>()
        val tagEntities = tagNames.map { name ->
            TagEntity(name = name, entryCount = 1).also { tagBox.put(it) }
        }
        val entry = EntryEntity(
            entryText = text,
            timestampEpochMs = ts,
            markdownFilename = "test-${System.nanoTime()}.md",
        )
        val id = entryBox.put(entry)
        if (tagEntities.isNotEmpty()) {
            entry.tags.addAll(tagEntities)
            entryBox.put(entry)
        }
        return id
    }

    private companion object {
        const val SECONDS_PER_DAY = 86_400L
    }
}
