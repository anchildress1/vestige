package dev.anchildress1.vestige.ui.history

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import dev.anchildress1.vestige.model.Persona
import dev.anchildress1.vestige.model.ResolvedExtraction
import dev.anchildress1.vestige.storage.EntryStore
import dev.anchildress1.vestige.storage.MarkdownEntryStore
import dev.anchildress1.vestige.testing.cleanupObjectBoxTempRoot
import dev.anchildress1.vestige.testing.newInMemoryObjectBoxDirectory
import dev.anchildress1.vestige.testing.newModuleTempRoot
import dev.anchildress1.vestige.testing.openInMemoryBoxStore
import io.objectbox.BoxStore
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.time.Instant
import java.time.ZoneOffset

/**
 * Exercises the in-process nav graph in HistoryHost. Uses createAndroidComposeRule so the
 * BackHandler wiring (which needs an OnBackPressedDispatcher) has a real Activity host.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE, application = HistoryTestApplication::class)
class HistoryHostTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private lateinit var tempRoot: File
    private lateinit var dataDir: File
    private lateinit var boxStore: BoxStore
    private lateinit var entryStore: EntryStore

    @Before
    fun setUp() {
        tempRoot = newModuleTempRoot("vestige-history-host-")
        dataDir = newInMemoryObjectBoxDirectory("ob-history-host-")
        boxStore = openInMemoryBoxStore(dataDir)
        entryStore = EntryStore(
            boxStore,
            MarkdownEntryStore(File(tempRoot, "md-${System.nanoTime()}").apply { mkdirs() }),
        )
    }

    @After
    fun tearDown() {
        boxStore.close()
        cleanupObjectBoxTempRoot(tempRoot, dataDir)
    }

    @Test
    fun `routes to entry detail on row tap and back returns to the list`() {
        seedCompleted("standup crashed me again", 1_000_000L)

        composeRule.activity.setContent {
            HistoryHost(
                entryStore = entryStore,
                persona = Persona.WITNESS,
                onExit = {},
                zoneId = ZoneOffset.UTC,
                dataRevision = MutableStateFlow(0L),
            )
        }

        composeRule.onNodeWithText("standup crashed me again", substring = true).assertIsDisplayed()

        composeRule.onNodeWithTag("history_row").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("● NEW ENTRY").assertIsDisplayed()

        composeRule.activity.runOnUiThread {
            composeRule.activity.onBackPressedDispatcher.onBackPressed()
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("standup crashed me again", substring = true).assertIsDisplayed()
    }

    @Test
    fun `onExit fires when back is invoked from the list level`() {
        var exited = false
        composeRule.activity.setContent {
            HistoryHost(
                entryStore = entryStore,
                persona = Persona.WITNESS,
                onExit = { exited = true },
                zoneId = ZoneOffset.UTC,
                dataRevision = MutableStateFlow(0L),
            )
        }

        composeRule.activity.runOnUiThread {
            composeRule.activity.onBackPressedDispatcher.onBackPressed()
        }
        composeRule.waitForIdle()
        assertTrue("onExit should fire when back is pressed at list level", exited)
    }

    private fun seedCompleted(text: String, timestampEpochMs: Long) {
        val id = entryStore.createPendingEntry(text, Instant.ofEpochMilli(timestampEpochMs))
        entryStore.completeEntry(id, ResolvedExtraction(emptyMap()), templateLabel = null)
    }
}
