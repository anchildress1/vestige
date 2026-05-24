package dev.anchildress1.vestige.storage

import dev.anchildress1.vestige.model.ObservationEvidence
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
        boxStore.closeAfterCleaningThreadResources()
        BoxStore.deleteAllFiles(dataDir)
    }

    @Test
    fun `tone word is the embedding target`() {
        assertEquals("weary", buildEmbeddingText(persist(vocabularyWord = "weary")))
    }

    @Test
    fun `tone word is trimmed and lowercased`() {
        assertEquals("restless", buildEmbeddingText(persist(vocabularyWord = "  Restless ")))
    }

    @Test
    fun `null tone word yields empty string, never the literal null`() {
        assertEquals("", buildEmbeddingText(persist(vocabularyWord = null)))
    }

    @Test
    fun `blank tone word yields empty string`() {
        assertEquals("", buildEmbeddingText(persist(vocabularyWord = "   ")))
    }

    @Test
    fun `content fields never leak into the embedding target`() {
        val entry = persist(
            vocabularyWord = null,
            tagNames = listOf("standup", "flattened"),
            observations = listOf("meeting ran long"),
            commitmentJson = commitment(topic = "alice"),
        )
        assertEquals("", buildEmbeddingText(entry))
    }

    @Test
    fun `tone word wins when content fields are also present`() {
        val entry = persist(
            vocabularyWord = "drained",
            tagNames = listOf("standup"),
            observations = listOf("meeting ran long"),
            commitmentJson = commitment(topic = "alice"),
        )
        assertEquals("drained", buildEmbeddingText(entry))
    }

    private fun commitment(topic: String): String =
        JSONObject().put("text", "committed").put("topic_or_person", topic).toString()

    private fun persist(
        vocabularyWord: String? = null,
        tagNames: List<String> = emptyList(),
        observations: List<String> = emptyList(),
        commitmentJson: String? = null,
    ): EntryEntity {
        val entryBox = boxStore.boxFor<EntryEntity>()
        val tagBox = boxStore.boxFor<TagEntity>()
        val tagEntities = tagNames.map { name -> TagEntity(name = name, entryCount = 1).also { tagBox.put(it) } }
        val observationsJson = JSONArray().apply {
            observations.forEach {
                put(
                    JSONObject()
                        .put("text", it)
                        .put("evidence", ObservationEvidence.THEME_NOTICING.serial)
                        .put("fields", JSONArray()),
                )
            }
        }.toString()
        val entry = EntryEntity(
            entryText = "raw verbatim body that must never be embedded",
            timestampEpochMs = System.currentTimeMillis(),
            markdownFilename = "test-${System.nanoTime()}.md",
            vocabularyWord = vocabularyWord,
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
