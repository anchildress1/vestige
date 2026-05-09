package dev.anchildress1.vestige.model

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class TemplateLabelTest {

    @Test
    fun `serial values match concept-locked kebab-case spec`() {
        assertEquals("aftermath", TemplateLabel.AFTERMATH.serial)
        assertEquals("tunnel-exit", TemplateLabel.TUNNEL_EXIT.serial)
        assertEquals("concrete-shoes", TemplateLabel.CONCRETE_SHOES.serial)
        assertEquals("decision-spiral", TemplateLabel.DECISION_SPIRAL.serial)
        assertEquals("goblin-hours", TemplateLabel.GOBLIN_HOURS.serial)
        assertEquals("audit", TemplateLabel.AUDIT.serial)
    }

    @Test
    fun `every enum entry has a unique serial`() {
        val serials = TemplateLabel.entries.map { it.serial }
        assertEquals(serials.size, serials.toSet().size)
    }

    @Test
    fun `fromSerial round-trips every entry`() {
        TemplateLabel.entries.forEach { label ->
            assertEquals(label, TemplateLabel.fromSerial(label.serial))
        }
    }

    @Test
    fun `fromSerial returns null on unknown input`() {
        assertNull(TemplateLabel.fromSerial("recovery"))
        assertNull(TemplateLabel.fromSerial(""))
        assertNull(TemplateLabel.fromSerial("AFTERMATH")) // case-sensitive
    }
}
