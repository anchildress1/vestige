package dev.anchildress1.vestige.ui.patterns

import dev.anchildress1.vestige.model.PatternState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PatternDetailScreenStyleTest {

    @Test
    fun `patternIntensityStyleFor only peaks active patterns`() {
        val active = patternIntensityStyleFor(PatternState.ACTIVE)
        assertEquals(TraceBarDefaults.Accent, active.accent)
        assertTrue(active.peak)

        PatternState.entries
            .filterNot { it == PatternState.ACTIVE }
            .forEach { state ->
                val muted = patternIntensityStyleFor(state)
                assertEquals(TraceBarDefaults.Rail, muted.accent)
                assertFalse("$state should not peak", muted.peak)
            }
    }
}
