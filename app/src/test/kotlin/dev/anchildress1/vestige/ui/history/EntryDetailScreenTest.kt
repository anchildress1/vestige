package dev.anchildress1.vestige.ui.history

import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import dev.anchildress1.vestige.model.EntryObservation
import dev.anchildress1.vestige.model.ObservationEvidence
import dev.anchildress1.vestige.model.ResolvedExtraction
import dev.anchildress1.vestige.model.TemplateLabel
import dev.anchildress1.vestige.storage.EntryEntity
import dev.anchildress1.vestige.storage.EntryStore
import dev.anchildress1.vestige.storage.MarkdownEntryStore
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

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE, application = HistoryTestApplication::class, qualifiers = "w360dp-h800dp")
class EntryDetailScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    private lateinit var tempRoot: File
    private lateinit var dataDir: File
    private lateinit var boxStore: BoxStore
    private lateinit var entryStore: EntryStore
    private val dispatcher = UnconfinedTestDispatcher()
    private val zone = ZoneOffset.UTC

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        tempRoot = newModuleTempRoot("vestige-entry-detail-screen-")
        dataDir = newInMemoryObjectBoxDirectory("ob-entry-detail-screen-")
        boxStore = openInMemoryBoxStore(dataDir)
        entryStore = EntryStore(
            boxStore,
            MarkdownEntryStore(File(tempRoot, "md-${System.nanoTime()}").apply { mkdirs() }),
        )
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        boxStore.close()
        cleanupObjectBoxTempRoot(tempRoot, dataDir)
    }

    // --- content display ---

    @Test
    fun `entry number heading is displayed`() {
        val id = createCompleted("crash and burn")
        setDetail(id)

        composeRule.onNodeWithTag("entry_number").assertIsDisplayed()
    }

    @Test
    fun `transcription text is displayed`() {
        val id = createCompleted("standup was brutal today")
        setDetail(id)

        composeRule.onNodeWithTag("entry_transcription").assertIsDisplayed()
        composeRule.onNodeWithText("standup was brutal today").assertIsDisplayed()
    }

    @Test
    fun `blank transcription shows dash placeholder`() {
        val id = createCompleted("initial text")
        val box = boxStore.boxFor(EntryEntity::class.java)
        val entity = box.get(id)
        entity.entryText = ""
        box.put(entity)
        setDetail(id)

        composeRule.onNodeWithTag("entry_transcription").assertIsDisplayed()
        // Blank body maps to "—" in display; content description captures the raw body.
        composeRule.onNodeWithContentDescription(
            "${EntryDetailCopy.YOU_LABEL}:",
            substring = true,
        ).assertIsDisplayed()
    }

    @Test
    fun `energy descriptor is displayed in reading card`() {
        val id = createCompleted("cruisy and crashed")
        completeWithEnergy(id, "cruisy in, crashed out")
        setDetail(id)

        composeRule.onNodeWithTag("entry_reading_card").assertIsDisplayed()
        // descriptor is uppercased in the composable
        composeRule.onNodeWithText("CRUISY IN, CRASHED OUT").assertIsDisplayed()
    }

    @Test
    fun `template label pill shown when label present`() {
        val id = createCompleted("aftermath entry")
        entryStore.completeEntry(id, ResolvedExtraction(emptyMap()), TemplateLabel.AFTERMATH)
        setDetail(id)

        composeRule.onNodeWithTag("entry_template_label").assertIsDisplayed()
    }

    @Test
    fun `tags are displayed as pills`() {
        val id = createCompleted("got tags")
        entryStore.completeEntry(
            id,
            resolved("tired", "monday"),
            null,
        )
        setDetail(id)

        composeRule.onNodeWithTag("entry_tags").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("tag: monday").assertIsDisplayed()
    }

    @Test
    fun `observations are shown inside reading card`() {
        val id = createCompleted("observation entry")
        val obs = listOf(
            EntryObservation("You said fine twice.", ObservationEvidence.VOCABULARY_CONTRADICTION, emptyList()),
        )
        entryStore.completeEntry(id, ResolvedExtraction(emptyMap()), null, obs)
        setDetail(id)

        composeRule.onNodeWithText("You said fine twice.").assertIsDisplayed()
    }

    // --- not found ---

    @Test
    fun `not-found copy shown for unknown entry id`() {
        composeRule.setContent {
            dev.anchildress1.vestige.ui.theme.VestigeTheme {
                EntryDetailScreen(
                    viewModel = buildVm(99_999L),
                    onBack = {},
                    onNewEntry = {},
                )
            }
        }
        composeRule.onNodeWithText(EntryDetailCopy.NOT_FOUND).assertIsDisplayed()
    }

    // --- bottom bar a11y ---

    @Test
    fun `back button has click action`() {
        val id = createCompleted("back test")
        setDetail(id)

        composeRule.onNodeWithContentDescription(EntryDetailCopy.BACK_CD).assertHasClickAction()
    }

    @Test
    fun `new entry button has click action`() {
        val id = createCompleted("new entry test")
        setDetail(id)

        composeRule.onNodeWithContentDescription(EntryDetailCopy.NEW_ENTRY_CD).assertHasClickAction()
    }

    @Test
    fun `back callback fires on back tap`() {
        val id = createCompleted("back fires")
        var backFired = false
        composeRule.setContent {
            dev.anchildress1.vestige.ui.theme.VestigeTheme {
                EntryDetailScreen(
                    viewModel = buildVm(id),
                    onBack = { backFired = true },
                    onNewEntry = {},
                )
            }
        }
        composeRule.onNodeWithContentDescription(EntryDetailCopy.BACK_CD).performClick()
        assertTrue(backFired)
    }

    @Test
    fun `new entry callback fires on new entry tap`() {
        val id = createCompleted("new entry fires")
        var newEntryFired = false
        composeRule.setContent {
            dev.anchildress1.vestige.ui.theme.VestigeTheme {
                EntryDetailScreen(
                    viewModel = buildVm(id),
                    onBack = {},
                    onNewEntry = { newEntryFired = true },
                )
            }
        }
        composeRule.onNodeWithContentDescription(EntryDetailCopy.NEW_ENTRY_CD).performClick()
        assertTrue(newEntryFired)
    }

    // --- a11y: stat ribbon ---

    @Test
    fun `stat ribbon has merged content description`() {
        val id = createCompleted("four words here test")
        setDetail(id)
        // The StatRibbon merges descendants; its a11y content covers audio + words
        composeRule.onNodeWithContentDescription(
            "— audio, 4 words",
            substring = true,
        ).assertIsDisplayed()
    }

    // --- helpers ---

    private fun setDetail(id: Long) {
        composeRule.setContent {
            dev.anchildress1.vestige.ui.theme.VestigeTheme {
                EntryDetailScreen(
                    viewModel = buildVm(id),
                    onBack = {},
                    onNewEntry = {},
                )
            }
        }
    }

    private fun buildVm(id: Long) = EntryDetailViewModel(
        entryId = id,
        entryStore = entryStore,
        personaName = "WITNESS",
        zoneId = zone,
        ioDispatcher = dispatcher,
    )

    private fun createCompleted(text: String): Long {
        val id = entryStore.createPendingEntry(text, FIXTURE_INSTANT)
        entryStore.completeEntry(id, ResolvedExtraction(emptyMap()), null)
        return id
    }

    private fun completeWithEnergy(id: Long, energy: String) {
        val resolved = ResolvedExtraction(
            mapOf(
                "energy_descriptor" to dev.anchildress1.vestige.model.ResolvedField(
                    energy,
                    dev.anchildress1.vestige.model.ConfidenceVerdict.CANONICAL,
                ),
            ),
        )
        entryStore.completeEntry(id, resolved, null)
    }

    private fun resolved(vararg tags: String): ResolvedExtraction = ResolvedExtraction(
        mapOf(
            "tags" to dev.anchildress1.vestige.model.ResolvedField(
                tags.toList(),
                dev.anchildress1.vestige.model.ConfidenceVerdict.CANONICAL,
            ),
        ),
    )

    companion object {
        private val FIXTURE_INSTANT: Instant = Instant.ofEpochSecond(1_778_829_684L)
    }
}
