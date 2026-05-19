package dev.anchildress1.vestige.storage

internal object TemporalPatternRules {
    const val RELATION_WEEKDAY_TIME_BLOCK: String = "weekday_time_block"
    const val RELATION_MONTH_START: String = "month_start"
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
