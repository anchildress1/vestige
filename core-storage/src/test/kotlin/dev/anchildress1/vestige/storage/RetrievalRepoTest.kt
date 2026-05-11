package dev.anchildress1.vestige.storage

import androidx.test.core.app.ApplicationProvider
import io.objectbox.BoxStore
import io.objectbox.kotlin.boxFor
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
