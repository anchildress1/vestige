package dev.anchildress1.vestige.storage

import dev.anchildress1.vestige.model.ConfidenceVerdict
import dev.anchildress1.vestige.model.ExtractionStatus
import dev.anchildress1.vestige.model.TemplateLabel
import io.objectbox.converter.PropertyConverter

/**
 * ObjectBox stores enums as their string name. Three converters keep the on-disk format
 * resilient to enum reordering and to JVM-tier enum changes that would shift `ordinal`.
 */
internal class TemplateLabelConverter : PropertyConverter<TemplateLabel?, String?> {
    override fun convertToEntityProperty(databaseValue: String?): TemplateLabel? =
        databaseValue?.let { runCatching { TemplateLabel.valueOf(it) }.getOrNull() }

    override fun convertToDatabaseValue(entityProperty: TemplateLabel?): String? = entityProperty?.name
}

internal class ExtractionStatusConverter : PropertyConverter<ExtractionStatus, String> {
    override fun convertToEntityProperty(databaseValue: String?): ExtractionStatus =
        databaseValue?.let { runCatching { ExtractionStatus.valueOf(it) }.getOrNull() }
            ?: ExtractionStatus.PENDING

    override fun convertToDatabaseValue(entityProperty: ExtractionStatus): String = entityProperty.name
}

internal class ConfidenceVerdictConverter : PropertyConverter<ConfidenceVerdict?, String?> {
    override fun convertToEntityProperty(databaseValue: String?): ConfidenceVerdict? =
        databaseValue?.let { runCatching { ConfidenceVerdict.valueOf(it) }.getOrNull() }

    override fun convertToDatabaseValue(entityProperty: ConfidenceVerdict?): String? = entityProperty?.name
}
