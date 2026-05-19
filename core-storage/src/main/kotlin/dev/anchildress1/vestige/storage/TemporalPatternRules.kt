package dev.anchildress1.vestige.storage

/** Temporal relation kinds. `serial` is the stable on-wire value persisted in signature JSON. */
internal enum class TemporalRelation(val serial: String) {
    WEEKDAY_TIME_BLOCK("weekday_time_block"),
    MONTH_START("month_start"),
    ;

    companion object {
        fun fromSerial(serial: String?): TemporalRelation? = entries.firstOrNull { it.serial == serial }
    }
}

internal object TemporalPatternRules {
    const val MONTH_START_DAY: Int = 1
    private const val MORNING_START_HOUR: Int = 5
    private const val MORNING_END_HOUR: Int = 11
    private const val AFTERNOON_START_HOUR: Int = 12
    private const val AFTERNOON_END_HOUR: Int = 16
    private const val EVENING_START_HOUR: Int = 17
    private const val EVENING_END_HOUR: Int = 21

    fun timeBlockForHour(hour: Int): String = when (hour) {
        in MORNING_START_HOUR..MORNING_END_HOUR -> "morning"
        in AFTERNOON_START_HOUR..AFTERNOON_END_HOUR -> "afternoon"
        in EVENING_START_HOUR..EVENING_END_HOUR -> "evening"
        else -> "night"
    }
}
