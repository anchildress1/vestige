package dev.anchildress1.vestige.patterns

import org.junit.Assert.assertEquals
import org.junit.Test

class PatternTitleTest {

    @Test
    fun `strips a trailing category noun`() {
        assertEquals("Tuesday Morning", PatternTitle.sanitize("Tuesday Morning Pattern"))
    }

    @Test
    fun `strips a category noun mid-title and collapses whitespace`() {
        assertEquals("Standup Crash", PatternTitle.sanitize("Standup Recurrence Crash"))
    }

    @Test
    fun `strips plurals and is case-insensitive`() {
        assertEquals("Late Night", PatternTitle.sanitize("Late Night PATTERNS"))
    }

    @Test
    fun `leaves a title with no category noun untouched`() {
        assertEquals("Couch Spiral", PatternTitle.sanitize("Couch Spiral"))
    }

    @Test
    fun `falls back to the raw title when stripping would empty it`() {
        assertEquals("Template Recurrence", PatternTitle.sanitize("Template Recurrence"))
    }
}
