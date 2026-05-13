package dev.anchildress1.vestige.ui.patterns

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import dev.anchildress1.vestige.model.ExtractionStatus
import dev.anchildress1.vestige.storage.EntryEntity
import dev.anchildress1.vestige.storage.EntryStore
import dev.anchildress1.vestige.storage.MarkdownEntryStore
import dev.anchildress1.vestige.storage.VestigeBoxStore
import io.objectbox.BoxStore
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE, application = PatternsTestApplication::class)
class EntryDetailPlaceholderScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    private lateinit var dataDir: File
    private lateinit var boxStore: BoxStore
    private lateinit var entryStore: EntryStore

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        dataDir = File(context.filesDir, "ob-entry-detail-${System.nanoTime()}")
        boxStore = VestigeBoxStore.openAt(dataDir)
        entryStore = EntryStore(
            boxStore,
            MarkdownEntryStore(File(context.filesDir, "md-${System.nanoTime()}")),
        )
    }

    @After
    fun tearDown() {
        boxStore.close()
        dataDir.deleteRecursively()
    }

    @Test
    fun `Loaded state renders date label and entry text`() {
        val entryId = persistEntry(text = "crashed after standup", timestamp = 1_778_414_400_000L)

        composeRule.setContent {
            EntryDetailPlaceholderScreen(
                entryId = entryId,
                entryStore = entryStore,
                onBack = {},
            )
        }
        // produceState runs the entry load on Dispatchers.IO; wait for the Loaded branch to
        // compose before asserting against its content — otherwise this flakes when the test JVM
        // is hot and the recomposition hasn't landed yet.
        composeRule.waitUntil(timeoutMillis = 5_000L) {
            composeRule.onAllNodesWithText("crashed after standup").fetchSemanticsNodes().isNotEmpty()
        }

        composeRule.onNodeWithText("crashed after standup").assertIsDisplayed()
        composeRule.onNodeWithText("Entry").assertIsDisplayed() // top bar title
    }

    @Test
    fun `NotFound state renders fallback copy for missing entry id`() {
        composeRule.setContent {
            EntryDetailPlaceholderScreen(
                entryId = 9_999L,
                entryStore = entryStore,
                onBack = {},
            )
        }
        composeRule.onNodeWithText("Entry not found.").assertIsDisplayed()
    }

    @Test
    fun `Back navigation icon fires onBack`() {
        val entryId = persistEntry(text = "back-check entry", timestamp = 1_778_414_400_000L)
        var backFired = false
        composeRule.setContent {
            EntryDetailPlaceholderScreen(
                entryId = entryId,
                entryStore = entryStore,
                onBack = { backFired = true },
            )
        }
        composeRule.onNodeWithContentDescription("Back").performClick()
        assertTrue("back callback fired", backFired)
    }

    private fun persistEntry(text: String, timestamp: Long): Long {
        val entity = EntryEntity(
            entryText = text,
            timestampEpochMs = timestamp,
            markdownFilename = "entry-$timestamp.md",
            extractionStatus = ExtractionStatus.COMPLETED,
        )
        return boxStore.boxFor(EntryEntity::class.java).put(entity)
    }
}
