package dev.anchildress1.vestige.ui.patterns

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import dev.anchildress1.vestige.model.ExtractionStatus
import dev.anchildress1.vestige.model.PatternKind
import dev.anchildress1.vestige.model.PatternState
import dev.anchildress1.vestige.storage.EntryEntity
import dev.anchildress1.vestige.storage.EntryStore
import dev.anchildress1.vestige.storage.MarkdownEntryStore
import dev.anchildress1.vestige.storage.PatternEntity
import dev.anchildress1.vestige.storage.PatternStore
import dev.anchildress1.vestige.storage.VocabCluster
import dev.anchildress1.vestige.storage.VocabClustersCodec
import dev.anchildress1.vestige.testing.cleanupObjectBoxTempRoot
import dev.anchildress1.vestige.testing.newInMemoryObjectBoxDirectory
import dev.anchildress1.vestige.testing.newModuleTempRoot
import dev.anchildress1.vestige.testing.openInMemoryBoxStore
import io.objectbox.BoxStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE, application = PatternsTestApplication::class)
class VocabDriftScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    private lateinit var tempRoot: File
    private lateinit var dataDir: File
    private lateinit var markdownDir: File
    private lateinit var boxStore: BoxStore
    private lateinit var entryStore: EntryStore
    private lateinit var patternStore: PatternStore
    private val testDispatcher = UnconfinedTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        tempRoot = newModuleTempRoot("vestige-vocab-drift-screen-")
        dataDir = newInMemoryObjectBoxDirectory("ob-vocab-drift-")
        markdownDir = File(tempRoot, "md-${System.nanoTime()}").apply { mkdirs() }
        boxStore = openInMemoryBoxStore(dataDir)
        entryStore = EntryStore(boxStore, MarkdownEntryStore(markdownDir))
        patternStore = PatternStore(boxStore)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        boxStore.close()
        cleanupObjectBoxTempRoot(tempRoot, dataDir)
    }

    @Test
    fun `loaded screen renders root token total entries and every cluster label`() {
        val supporting = seedSupportingEntries(8, "exhausted") +
            seedSupportingEntries(7, "foggy") +
            seedSupportingEntries(6, "wired-tired")
        seedPattern(
            supporting,
            clusters = listOf(
                fakeCluster("exhausted, drained", 1, 8),
                fakeCluster("sluggish, foggy", 9, 15),
                fakeCluster("wired-tired", 16, 21),
            ),
        )

        composeRule.setContent {
            VocabDriftScreen(
                viewModel = newViewModel(),
                onBack = {},
            )
        }

        composeRule.onNodeWithText("\"tired\" × 21").assertIsDisplayed()
        composeRule.onNodeWithText("3 distinct framings of the same underlying state.").assertIsDisplayed()
        // Use assertExists for cluster labels: the third card may render below the viewport
        // fold under the default Robolectric screen size, but it's in the semantics tree.
        composeRule.onNodeWithText("exhausted, drained").assertExists()
        composeRule.onNodeWithText("sluggish, foggy").assertExists()
        composeRule.onNodeWithText("wired-tired").assertExists()
    }

    @Test
    fun `distribution bar announces vocabulary proportion split for screen readers`() {
        val supporting = seedSupportingEntries(8, "exhausted") +
            seedSupportingEntries(7, "foggy") +
            seedSupportingEntries(5, "wired")
        seedPattern(
            supporting,
            clusters = listOf(
                fakeCluster("exhausted", 1, 8),
                fakeCluster("foggy", 9, 15),
                fakeCluster("wired", 16, 20),
            ),
        )

        composeRule.setContent {
            VocabDriftScreen(
                viewModel = newViewModel(),
                onBack = {},
            )
        }

        composeRule
            .onNodeWithContentDescription("Vocabulary distribution: exhausted: 40%, foggy: 35%, wired: 25%.")
            .assertIsDisplayed()
    }

    @Test
    fun `not found state is announced politely with no click action`() {
        // No pattern row at all — VM resolves to NotFound.
        composeRule.setContent {
            VocabDriftScreen(viewModel = newViewModel(), onBack = {})
        }

        val band = composeRule.onNodeWithTag(VocabDriftTestTags.NOT_FOUND_BAND)
        band.assertIsDisplayed()
        band.assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.LiveRegion))
    }

    @Test
    fun `pattern with blank clusters json renders NotYetClustered band`() {
        // Right pattern, right kind — orchestrator hasn't stamped clusters yet.
        seedPattern(supporting = emptyList(), clusters = emptyList())

        composeRule.setContent {
            VocabDriftScreen(viewModel = newViewModel(), onBack = {})
        }

        val band = composeRule.onNodeWithTag(VocabDriftTestTags.NOT_YET_CLUSTERED_BAND)
        band.assertIsDisplayed()
        band.assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.LiveRegion))
    }

    @Test
    fun `non-vocab-frequency pattern renders NotFound`() {
        // Deep-link to a TEMPORAL_RELATIVE pattern by id — VM must guard the kind.
        val pattern = PatternEntity(
            patternId = PATTERN_ID,
            kind = PatternKind.TEMPORAL_RELATIVE,
            signatureJson = "{}",
            title = "Whatever",
            templateLabel = null,
            firstSeenTimestamp = 1L,
            lastSeenTimestamp = 1L,
            state = PatternState.ACTIVE,
        )
        patternStore.put(pattern)

        composeRule.setContent {
            VocabDriftScreen(viewModel = newViewModel(), onBack = {})
        }

        composeRule.onNodeWithTag(VocabDriftTestTags.NOT_FOUND_BAND).assertIsDisplayed()
    }

    @Test
    fun `example snippet collapses whitespace and ellipsizes past 140 chars`() {
        val box = boxStore.boxFor(EntryEntity::class.java)
        val longText = "tabs\tand\n\n   spaces   " + "x".repeat(200)
        val example = EntryEntity(
            markdownFilename = "vocab-long.md",
            entryText = longText,
            timestampEpochMs = 1L,
            durationMs = 5_000L,
            extractionStatus = ExtractionStatus.COMPLETED,
        ).also { box.put(it) }
        seedPattern(
            supporting = listOf(example),
            clusters = listOf(
                VocabCluster.of(
                    members = listOf(example.id),
                    label = "long",
                    description = "1 entry · framings: long",
                    exampleEntryId = example.id,
                ),
            ),
        )

        composeRule.setContent {
            VocabDriftScreen(viewModel = newViewModel(), onBack = {})
        }

        // Collapsed whitespace = single spaces. 140-char cap → trailing ellipsis.
        composeRule.onAllNodesWithText("\"tabs and spaces ", substring = true)[0].assertExists()
        composeRule.onAllNodesWithText("…\"", substring = true)[0].assertExists()
    }

    private fun fakeCluster(label: String, idStart: Long, idEnd: Long): VocabCluster = VocabCluster.of(
        members = (idStart..idEnd).toList(),
        label = label,
        description = "${idEnd - idStart + 1} entries · framings: $label",
        exampleEntryId = idStart,
    )

    private fun seedSupportingEntries(count: Int, marker: String): List<EntryEntity> {
        val box = boxStore.boxFor(EntryEntity::class.java)
        return (1..count).map { i ->
            EntryEntity(
                markdownFilename = "vocab-$marker-$i.md",
                entryText = "$marker entry $i",
                timestampEpochMs = i * 1000L,
                durationMs = 5_000L,
                extractionStatus = ExtractionStatus.COMPLETED,
            ).also { box.put(it) }
        }
    }

    private fun seedPattern(supporting: List<EntryEntity>, clusters: List<VocabCluster>) {
        val pattern = PatternEntity(
            patternId = PATTERN_ID,
            kind = PatternKind.VOCAB_FREQUENCY,
            signatureJson = """{"kind":"vocab_frequency","token":"tired"}""",
            title = "Tired",
            templateLabel = null,
            firstSeenTimestamp = 1L,
            lastSeenTimestamp = 100L,
            state = PatternState.ACTIVE,
            latestCalloutText = "tired across multiple framings",
            vocabClustersJson = VocabClustersCodec.encode(clusters, evidenceHash = "test-hash"),
        )
        patternStore.put(pattern)
        val stored = patternStore.findByPatternId(PATTERN_ID)!!
        stored.supportingEntries.addAll(supporting)
        patternStore.put(stored)
    }

    private fun newViewModel(): VocabDriftViewModel = VocabDriftViewModel(
        patternId = PATTERN_ID,
        patternStore = patternStore,
        entryStore = entryStore,
        ioDispatcher = testDispatcher,
    )

    private companion object {
        const val PATTERN_ID: String = "vvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvv"
    }
}
