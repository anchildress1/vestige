package dev.anchildress1.vestige.storage

import dev.anchildress1.vestige.model.ConfidenceVerdict
import dev.anchildress1.vestige.model.ExtractionStatus
import dev.anchildress1.vestige.model.TemplateLabel
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class ConvertersTest {

    @Test
    fun `TemplateLabel converter round-trips every entry`() {
        val converter = TemplateLabelConverter()
        TemplateLabel.entries.forEach { label ->
            val db = converter.convertToDatabaseValue(label)
            assertEquals(label, converter.convertToEntityProperty(db))
        }
    }

    @Test
    fun `TemplateLabel converter maps null both directions`() {
        val converter = TemplateLabelConverter()
        assertNull(converter.convertToDatabaseValue(null))
        assertNull(converter.convertToEntityProperty(null))
    }

    @Test
    fun `TemplateLabel converter returns null on unknown name`() {
        val converter = TemplateLabelConverter()
        assertNull(converter.convertToEntityProperty("RECOVERY"))
        assertNull(converter.convertToEntityProperty(""))
    }

    @Test
    fun `ExtractionStatus converter round-trips every entry`() {
        val converter = ExtractionStatusConverter()
        ExtractionStatus.entries.forEach { status ->
            val db = converter.convertToDatabaseValue(status)
            assertEquals(status, converter.convertToEntityProperty(db))
        }
    }

    @Test
    fun `ExtractionStatus converter defaults to PENDING on null or unknown`() {
        val converter = ExtractionStatusConverter()
        // PENDING is the safe default — an entry that lost its status row needs to be re-extracted.
        assertEquals(ExtractionStatus.PENDING, converter.convertToEntityProperty(null))
        assertEquals(ExtractionStatus.PENDING, converter.convertToEntityProperty("DEPRECATED"))
    }

    @Test
    fun `ConfidenceVerdict converter round-trips every entry`() {
        val converter = ConfidenceVerdictConverter()
        ConfidenceVerdict.entries.forEach { verdict ->
            val db = converter.convertToDatabaseValue(verdict)
            assertEquals(verdict, converter.convertToEntityProperty(db))
        }
    }

    @Test
    fun `ConfidenceVerdict converter maps null both directions`() {
        val converter = ConfidenceVerdictConverter()
        assertNull(converter.convertToDatabaseValue(null))
        assertNull(converter.convertToEntityProperty(null))
        assertNull(converter.convertToEntityProperty("UNKNOWN"))
    }
}
