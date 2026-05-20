package dev.anchildress1.vestige.ui.history

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import dev.anchildress1.vestige.model.Persona
import dev.anchildress1.vestige.model.ResolvedExtraction
import dev.anchildress1.vestige.storage.EntryStore
import dev.anchildress1.vestige.storage.MarkdownEntryStore
import dev.anchildress1.vestige.storage.closeAfterCleaningThreadResources
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
        composeRule.activityRule.scenario.close()
        // Compose host tests still have ObjectBox readers owned by runner threads during @After.
        // Clean thread locals first, then close the in-memory registry so later JVM tests do not
        // inherit the stale store.
        boxStore.closeAfterCleaningThreadResources()
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

        // Identify the list by its row tag — robust to row-content formatting changes.
        composeRule.onNodeWithTag("history_row").assertIsDisplayed()

        composeRule.onNodeWithTag("history_row").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("entry_time").assertIsDisplayed()

        composeRule.activity.runOnUiThread {
            composeRule.activity.onBackPressedDispatcher.onBackPressed()
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("history_row").assertIsDisplayed()
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

    @Test
    fun `openRequest routes directly to entry detail and consumes the one-shot request`() {
        val entryId = seedCompleted("opened from notification", 1_000_000L)
        var consumed = false

        composeRule.activity.setContent {
            HistoryHost(
                entryStore = entryStore,
                persona = Persona.WITNESS,
                onExit = {},
                zoneId = ZoneOffset.UTC,
                dataRevision = MutableStateFlow(0L),
                openRequest = EntryDetailOpenRequest(entryId = entryId, token = 7L),
                onOpenRequestConsumed = { consumed = true },
            )
        }

        composeRule.waitForIdle()
        composeRule.onNodeWithTag("entry_time").assertIsDisplayed()
        assertTrue("openRequest should be consumed after routing", consumed)
    }

    @Test
    fun `tab navigation from detail clears stale detail before leaving history`() {
        seedCompleted("standup crashed me again", 1_000_000L)
        val showingHistory = mutableStateOf(true)

        composeRule.activity.setContent {
            if (showingHistory.value) {
                HistoryHost(
                    entryStore = entryStore,
                    persona = Persona.WITNESS,
                    onExit = {},
                    zoneId = ZoneOffset.UTC,
                    dataRevision = MutableStateFlow(0L),
                    onNavigateTab = { showingHistory.value = false },
                )
            } else {
                Box(modifier = Modifier.fillMaxSize().testTag("outside_history"))
            }
        }

        composeRule.onNodeWithTag("history_row").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("entry_time").assertIsDisplayed()

        composeRule.onNode(hasText("CAPTURE") and hasClickAction()).performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("outside_history").assertIsDisplayed()

        composeRule.runOnIdle { showingHistory.value = true }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("history_row").assertIsDisplayed()
    }

    private fun seedCompleted(text: String, timestampEpochMs: Long): Long {
        val id = entryStore.createPendingEntry(text, Instant.ofEpochMilli(timestampEpochMs))
        entryStore.completeEntry(id, ResolvedExtraction(emptyMap()), templateLabel = null)
        return id
    }
}
