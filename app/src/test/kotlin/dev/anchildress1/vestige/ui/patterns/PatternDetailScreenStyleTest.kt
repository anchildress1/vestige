package dev.anchildress1.vestige.ui.patterns

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PatternDetailScreenStyleTest {

    @Test
    fun `patternIntensityStyleFor maps each lifecycle state to a visible accent`() {
        val active = patternIntensityStyleFor(dev.anchildress1.vestige.model.PatternState.ACTIVE)
        assertEquals(TraceBarDefaults.Accent, active.accent)
        assertTrue(active.peak)

        val snoozed = patternIntensityStyleFor(dev.anchildress1.vestige.model.PatternState.SNOOZED)
        assertEquals(dev.anchildress1.vestige.ui.theme.Ember, snoozed.accent)
        assertFalse(snoozed.peak)

        val resolved = patternIntensityStyleFor(dev.anchildress1.vestige.model.PatternState.RESOLVED)
        assertEquals(dev.anchildress1.vestige.ui.theme.Teal, resolved.accent)
        assertFalse(resolved.peak)

        val dismissed = patternIntensityStyleFor(dev.anchildress1.vestige.model.PatternState.DISMISSED)
        assertEquals(dev.anchildress1.vestige.ui.theme.Teal, dismissed.accent)
        assertFalse(dismissed.peak)

        val belowThreshold = patternIntensityStyleFor(dev.anchildress1.vestige.model.PatternState.BELOW_THRESHOLD)
        assertEquals(dev.anchildress1.vestige.ui.theme.TealDim, belowThreshold.accent)
        assertFalse(belowThreshold.peak)
    }

    @Test
    fun `patternCardTraceBarStyleFor keeps non-active sections readable`() {
        val active = patternCardTraceBarStyleFor(PatternSection.ACTIVE)
        assertEquals(TraceBarDefaults.Accent, active.accent)
        assertTrue(active.peak)

        val snoozed = patternCardTraceBarStyleFor(PatternSection.SNOOZED)
        assertEquals(dev.anchildress1.vestige.ui.theme.Ember, snoozed.accent)
        assertFalse(snoozed.peak)

        val resolved = patternCardTraceBarStyleFor(PatternSection.RESOLVED)
        assertEquals(dev.anchildress1.vestige.ui.theme.Teal, resolved.accent)
        assertFalse(resolved.peak)

        val dismissed = patternCardTraceBarStyleFor(PatternSection.DISMISSED)
        assertEquals(dev.anchildress1.vestige.ui.theme.Teal, dismissed.accent)
        assertFalse(dismissed.peak)
    }
}
