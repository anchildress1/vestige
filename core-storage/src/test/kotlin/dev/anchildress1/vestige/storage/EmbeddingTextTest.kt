package dev.anchildress1.vestige.storage

import dev.anchildress1.vestige.testing.newInMemoryObjectBoxDirectory
import dev.anchildress1.vestige.testing.openInMemoryBoxStore
import io.objectbox.BoxStore
import io.objectbox.kotlin.boxFor
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class EmbeddingTextTest {

    private lateinit var boxStore: BoxStore
    private lateinit var dataDir: File

    @Before
    fun setUp() {
        dataDir = newInMemoryObjectBoxDirectory("objectbox-embedtext-")
        boxStore = openInMemoryBoxStore(dataDir)
    }

    @After
    fun tearDown() {
        boxStore.close()
        BoxStore.deleteAllFiles(dataDir)
    }

    @Test
    fun `tags only joins names space-separated`() {
        val entry = persist(tagNames = listOf("tuesday-meeting", "standup", "flattened"))
        assertEquals("tuesday-meeting standup flattened", buildEmbeddingText(entry))
    }

    @Test
    fun `observations only joins texts with period-space`() {
        val entry = persist(observations = listOf("meeting ran long", "lead was absent"))
        assertEquals("meeting ran long. lead was absent", buildEmbeddingText(entry))
    }

    @Test
    fun `commitment only emits the topic_or_person value`() {
        val entry = persist(commitmentJson = commitment(topic = "quarterly-review"))
        assertEquals("quarterly-review", buildEmbeddingText(entry))
    }

    @Test
    fun `all three components join in tag-observation-commitment order`() {
        val entry = persist(
            tagNames = listOf("standup", "flattened"),
            observations = listOf("meeting ran long", "lead was absent"),
            commitmentJson = commitment(topic = "alice"),
        )
        assertEquals(
            "standup flattened. meeting ran long. lead was absent. alice",
            buildEmbeddingText(entry),
        )
    }

    @Test
    fun `nothing distillable yields an empty string`() {
        val entry = persist()
        assertEquals("", buildEmbeddingText(entry))
    }

    @Test
    fun `malformed observations json is omitted, not propagated`() {
        val entry = persist(tagNames = listOf("tag"), rawObservationsJson = "not-valid-json")
        assertEquals("tag", buildEmbeddingText(entry))
    }

    @Test
    fun `observation with blank text is dropped`() {
        val raw = JSONArray().apply {
            put(JSONObject().put("text", "kept").put("evidence", "theme-noticing").put("fields", JSONArray()))
            put(JSONObject().put("text", "   ").put("evidence", "theme-noticing").put("fields", JSONArray()))
        }.toString()
        val entry = persist(rawObservationsJson = raw)
        assertEquals("kept", buildEmbeddingText(entry))
    }

    @Test
    fun `observation text is trimmed`() {
        val raw = JSONArray().apply {
            put(JSONObject().put("text", "  spaced out  ").put("evidence", "theme-noticing").put("fields", JSONArray()))
        }.toString()
        val entry = persist(rawObservationsJson = raw)
        assertEquals("spaced out", buildEmbeddingText(entry))
    }

    @Test
    fun `blank and whitespace tag names are filtered`() {
        val entry = persist(tagNames = listOf("kept", "   ", ""))
        assertEquals("kept", buildEmbeddingText(entry))
    }

    @Test
    fun `malformed commitment json is omitted`() {
        val entry = persist(tagNames = listOf("tag"), commitmentJson = "{ not json")
        assertEquals("tag", buildEmbeddingText(entry))
    }

    @Test
    fun `commitment with json-null topic is omitted`() {
        val json = JSONObject().put("text", "c").put("topic_or_person", JSONObject.NULL).toString()
        val entry = persist(commitmentJson = json)
        assertEquals("", buildEmbeddingText(entry))
    }

    @Test
    fun `commitment with literal string null is omitted`() {
        val entry = persist(commitmentJson = commitment(topic = "null"))
        assertEquals("", buildEmbeddingText(entry))
    }

    @Test
    fun `commitment missing the topic key is omitted`() {
        val entry = persist(tagNames = listOf("tag"), commitmentJson = JSONObject().put("text", "c").toString())
        assertEquals("tag", buildEmbeddingText(entry))
    }

    @Test
    fun `commitment with whitespace-only topic is omitted`() {
        val entry = persist(commitmentJson = commitment(topic = "   "))
        assertEquals("", buildEmbeddingText(entry))
    }

    private fun commitment(topic: String): String =
        JSONObject().put("text", "committed").put("topic_or_person", topic).toString()

    private fun persist(
        tagNames: List<String> = emptyList(),
        observations: List<String> = emptyList(),
        rawObservationsJson: String? = null,
        commitmentJson: String? = null,
    ): EntryEntity {
        val entryBox = boxStore.boxFor<EntryEntity>()
        val tagBox = boxStore.boxFor<TagEntity>()
        val tagEntities = tagNames.map { name -> TagEntity(name = name, entryCount = 1).also { tagBox.put(it) } }
        val observationsJson = rawObservationsJson ?: JSONArray().apply {
            observations.forEach {
                put(JSONObject().put("text", it).put("evidence", "theme-noticing").put("fields", JSONArray()))
            }
        }.toString()
        val entry = EntryEntity(
            entryText = "raw verbatim body that must never be embedded",
            timestampEpochMs = System.currentTimeMillis(),
            markdownFilename = "test-${System.nanoTime()}.md",
            statedCommitmentJson = commitmentJson,
            entryObservationsJson = observationsJson,
        )
        val id = entryBox.put(entry)
        if (tagEntities.isNotEmpty()) {
            entry.tags.addAll(tagEntities)
            entryBox.put(entry)
        }
        return entryBox[id]
    }
}
