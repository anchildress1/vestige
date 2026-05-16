package dev.anchildress1.vestige.ui.history

import app.cash.turbine.test
import dev.anchildress1.vestige.model.EntryObservation
import dev.anchildress1.vestige.model.ObservationEvidence
import dev.anchildress1.vestige.model.Persona
import dev.anchildress1.vestige.model.ResolvedExtraction
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
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.time.Instant
import java.time.ZoneOffset

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, application = HistoryTestApplication::class)
class EntryDetailViewModelTest {

    private lateinit var tempRoot: File
    private lateinit var dataDir: File
    private lateinit var boxStore: BoxStore
    private lateinit var entryStore: EntryStore
    private val dispatcher = UnconfinedTestDispatcher()
    private val zone = ZoneOffset.UTC

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        tempRoot = newModuleTempRoot("vestige-entry-detail-vm-")
        dataDir = newInMemoryObjectBoxDirectory("ob-entry-detail-vm-")
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

    // --- loading / not-found ---

    @Test
    fun `state is Loaded with correct transcription after creating and completing an entry`() = runTest {
        val id = createCompleted("standup was brutal today")
        val vm = buildVm(id)

        vm.state.test {
            val loaded = awaitItem() as EntryDetailUiState.Loaded
            assertEquals("standup was brutal today", loaded.model.transcription)
        }
    }

    @Test
    fun `state is Loaded with the persisted follow-up and recorded persona`() = runTest {
        val id = createCompleted(
            text = "standup was brutal today",
            followUpText = "What happened after you opened the doc?",
            persona = Persona.EDITOR,
        )
        val vm = buildVm(id)

        vm.state.test {
            val loaded = awaitItem() as EntryDetailUiState.Loaded
            assertEquals("What happened after you opened the doc?", loaded.model.followUp)
            assertEquals("EDITOR", loaded.model.personaName)
        }
    }

    @Test
    fun `state is NotFound for an unknown entry id`() = runTest {
        val vm = buildVm(entryId = 99_999L)
        vm.state.test {
            assertEquals(EntryDetailUiState.NotFound, awaitItem())
        }
    }

    @Test
    fun `state is NotFound when readEntry throws`() = runTest {
        val id = createCompleted("store closes before read")
        boxStore.close()

        val vm = buildVm(id)
        vm.state.test {
            assertEquals(EntryDetailUiState.NotFound, awaitItem())
        }
    }

    // --- word count ---

    @Test
    fun `wordCount counts space-separated tokens in transcription`() = runTest {
        val id = createCompleted("one two three four")
        val vm = buildVm(id)

        vm.state.test {
            val loaded = awaitItem() as EntryDetailUiState.Loaded
            assertEquals(4, loaded.model.wordCount)
        }
    }

    // --- template label ---

    @Test
    fun `templateLabel is uppercased serial when present`() = runTest {
        val id = entryStore.createPendingEntry("aftermath entry", FIXTURE_INSTANT)
        entryStore.completeEntry(
            id,
            ResolvedExtraction(emptyMap()),
            dev.anchildress1.vestige.model.TemplateLabel.TUNNEL_EXIT,
        )
        val vm = buildVm(id)

        vm.state.test {
            val loaded = awaitItem() as EntryDetailUiState.Loaded
            assertEquals("TUNNEL-EXIT", loaded.model.templateLabel)
        }
    }

    @Test
    fun `templateLabel is null when none extracted`() = runTest {
        val id = createCompleted("no template here")
        val vm = buildVm(id)

        vm.state.test {
            val loaded = awaitItem() as EntryDetailUiState.Loaded
            assertNull(loaded.model.templateLabel)
        }
    }

    // --- observations ---

    @Test
    fun `observations list contains text from entryObservationsJson`() = runTest {
        val id = entryStore.createPendingEntry("said fine twice", FIXTURE_INSTANT)
        val obs = listOf(
            EntryObservation("You used \"fine\" twice.", ObservationEvidence.VOCABULARY_CONTRADICTION, emptyList()),
            EntryObservation("You committed to review by Friday.", ObservationEvidence.COMMITMENT_FLAG, emptyList()),
        )
        entryStore.completeEntry(id, ResolvedExtraction(emptyMap()), null, obs)
        val vm = buildVm(id)

        vm.state.test {
            val loaded = awaitItem() as EntryDetailUiState.Loaded
            assertEquals(2, loaded.model.observations.size)
            assertEquals("You used \"fine\" twice.", loaded.model.observations[0].text)
        }
    }

    @Test
    fun `observations is empty when json is empty array`() = runTest {
        val id = createCompleted("no observations")
        val vm = buildVm(id)

        vm.state.test {
            val loaded = awaitItem() as EntryDetailUiState.Loaded
            assertTrue(loaded.model.observations.isEmpty())
        }
    }

    @Test
    fun `observations is empty when entryObservationsJson is malformed`() = runTest {
        val id = createCompleted("malformed observations")
        val box = boxStore.boxFor(EntryEntity::class.java)
        box.get(id).also { it.entryObservationsJson = "{not valid json" }.let(box::put)
        val vm = buildVm(id)

        vm.state.test {
            val loaded = awaitItem() as EntryDetailUiState.Loaded
            assertTrue(loaded.model.observations.isEmpty())
        }
    }

    // --- helper ---

    private fun buildVm(entryId: Long): EntryDetailViewModel = EntryDetailViewModel(
        entryId = entryId,
        entryStore = entryStore,
        zoneId = zone,
        ioDispatcher = dispatcher,
    )

    private fun createCompleted(text: String, followUpText: String? = null, persona: Persona = Persona.WITNESS): Long {
        val id = entryStore.createPendingEntry(
            entryText = text,
            timestamp = FIXTURE_INSTANT,
            followUpText = followUpText,
            persona = persona,
        )
        entryStore.completeEntry(id, ResolvedExtraction(emptyMap()), null)
        return id
    }

    companion object {
        private val FIXTURE_INSTANT: Instant = Instant.ofEpochSecond(1_778_829_684L)
    }
}
