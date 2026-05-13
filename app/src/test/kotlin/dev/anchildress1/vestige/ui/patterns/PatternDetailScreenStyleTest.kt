package dev.anchildress1.vestige.ui.patterns

import dev.anchildress1.vestige.model.PatternState
import org.junit.Assert.assertEquals
import org.junit.Test

class PatternDetailScreenStyleTest {

    @Test
    fun `intensityToneFor maps each lifecycle state to a visible tone`() {
        assertEquals(IntensityTone.ACTIVE_PEAK, intensityToneFor(PatternState.ACTIVE))
        assertEquals(IntensityTone.SNOOZED, intensityToneFor(PatternState.SNOOZED))
        assertEquals(IntensityTone.SETTLED, intensityToneFor(PatternState.RESOLVED))
        assertEquals(IntensityTone.SETTLED, intensityToneFor(PatternState.DISMISSED))
        assertEquals(IntensityTone.FROZEN, intensityToneFor(PatternState.BELOW_THRESHOLD))
    }

    @Test
    fun `cardSectionToneFor keeps non-active sections readable`() {
        assertEquals(IntensityTone.ACTIVE_PEAK, cardSectionToneFor(PatternSection.ACTIVE))
        assertEquals(IntensityTone.SNOOZED, cardSectionToneFor(PatternSection.SNOOZED))
        assertEquals(IntensityTone.SETTLED, cardSectionToneFor(PatternSection.RESOLVED))
        assertEquals(IntensityTone.SETTLED, cardSectionToneFor(PatternSection.DISMISSED))
    }

    @Test
    fun `peak flag only lights for active patterns`() {
        // Only ACTIVE_PEAK rises to peak; settled / snoozed / frozen lay flat.
        assertEquals(true, IntensityTone.ACTIVE_PEAK.peak)
        assertEquals(false, IntensityTone.SNOOZED.peak)
        assertEquals(false, IntensityTone.SETTLED.peak)
        assertEquals(false, IntensityTone.FROZEN.peak)
    }
}
