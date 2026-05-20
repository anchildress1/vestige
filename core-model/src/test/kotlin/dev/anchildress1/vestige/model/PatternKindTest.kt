package dev.anchildress1.vestige.model

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class PatternKindTest {

    @Test
    fun `all serials are unique — copy-paste error guard`() {
        val serials = PatternKind.entries.map { it.serial }
        assertEquals(serials.distinct(), serials)
    }
}
