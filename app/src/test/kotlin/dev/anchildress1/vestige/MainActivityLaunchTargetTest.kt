package dev.anchildress1.vestige

import android.content.Intent
import dev.anchildress1.vestige.storage.EntryStore
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.Assert.assertEquals
import org.junit.Test

class MainActivityLaunchTargetTest {

    @Test
    fun `resolvePostOnboardingLaunchTarget ignores unrelated launches`() {
        val entryStore = mockk<EntryStore>()

        val target = resolvePostOnboardingLaunchTarget(intent = null, entryStore = entryStore, token = 7L)

        assertEquals(PostOnboardingLaunchTarget.None, target)
    }

    @Test
    fun `resolvePostOnboardingLaunchTarget opens entry detail for most recent in-flight entry`() {
        val entryStore = mockk<EntryStore> {
            every { mostRecentNonTerminalEntryId() } returns 42L
        }
        val intent = mockk<Intent> {
            every { getBooleanExtra(EXTRA_OPEN_LATEST_IN_FLIGHT_ENTRY, false) } returns true
        }

        val target = resolvePostOnboardingLaunchTarget(intent = intent, entryStore = entryStore, token = 9L)

        assertEquals(PostOnboardingLaunchTarget.HistoryDetail(entryId = 42L, token = 9L), target)
    }

    @Test
    fun `resolvePostOnboardingLaunchTarget falls back to History when no in-flight entry exists`() {
        val entryStore = mockk<EntryStore> {
            every { mostRecentNonTerminalEntryId() } returns null
        }
        val intent = mockk<Intent> {
            every { getBooleanExtra(EXTRA_OPEN_LATEST_IN_FLIGHT_ENTRY, false) } returns true
        }

        val target = resolvePostOnboardingLaunchTarget(intent = intent, entryStore = entryStore, token = 11L)

        assertEquals(PostOnboardingLaunchTarget.History(token = 11L), target)
    }

    @Test
    fun `consumePostOnboardingLaunchTarget clears the one-shot extra after use`() {
        val entryStore = mockk<EntryStore> {
            every { mostRecentNonTerminalEntryId() } returns 42L
        }
        val intent = mockk<Intent>(relaxed = true)
        every { intent.getBooleanExtra(EXTRA_OPEN_LATEST_IN_FLIGHT_ENTRY, false) } returns true

        val target = consumePostOnboardingLaunchTarget(intent = intent, entryStore = entryStore, token = 13L)

        assertEquals(PostOnboardingLaunchTarget.HistoryDetail(entryId = 42L, token = 13L), target)
        verify(exactly = 1) { intent.removeExtra(EXTRA_OPEN_LATEST_IN_FLIGHT_ENTRY) }
    }
}
