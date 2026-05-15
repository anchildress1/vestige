package dev.anchildress1.vestige.ui.capture

/**
 * Capture-screen microcopy. Strings sourced from `docs/ux-copy.md` §"Capture Screen" — pull
 * here, never invent inline. Tests assert verbatim so doc drift is loud.
 */
object CaptureCopy {
    const val HERO_QUESTION: String = "WHAT JUST HAPPENED?"
    const val HERO_HIGHLIGHT_FROM_END: String = "HAPPENED?"

    const val OR_TYPE: String = "OR TYPE →"

    const val TYPE_FIELD_LABEL: String = "Typed entry text"
    const val TYPE_PLACEHOLDER: String = "What just happened."
    const val TYPE_SUBMIT: String = "Log entry"

    const val REC_LABEL_IDLE: String = "Record"
    const val REC_LABEL_RECORDING: String = "Stop"

    const val LIVE_RECORDING_EYEBROW: String = "● RECORDING · CHUNK 1/1"
    const val LIVE_LEVEL_EYEBROW: String = "● LEVEL · LIVE"
    const val LIVE_REMAIN_LABEL: String = "REMAIN"
    const val LIVE_SECONDS_LABEL: String = "SECONDS"
    const val LIVE_WORD_COUNT_LABEL: String = "WORD COUNT · EST"

    const val LIVE_STOP_PRIMARY: String = "STOP · FILE IT"
    const val LIVE_DISCARD_SECONDARY: String = "DISCARD · NO SAVE"

    const val STAT_KEPT: String = "KEPT"
    const val STAT_ACTIVE: String = "ACTIVE"
    const val STAT_HITS_MONTH: String = "HITS/MO"
    const val STAT_CLOUD: String = "CLOUD"

    const val STREAK_LABEL: String = "STREAK"
    const val DAY_PREFIX: String = "DAY"

    const val MODEL_LOADING_LINE: String = "Model loading. Typed entries work now."
    const val MODEL_PAUSED_LINE: String = "Reconnect to Wi-Fi to resume. Typed entries work now."
    const val MODEL_DOWNLOADING_LINE_FMT: String = "Downloading model · %d%%"
    const val MIC_DENIED_LINE: String = "Mic permission required to record. Settings → Permissions."
    const val MIC_UNAVAILABLE_LINE: String = "Mic unavailable. Try typing."
    const val INFERENCE_PARSE_FAILED_LINE: String = "Model couldn't read that. Try again."
    const val INFERENCE_TIMED_OUT_LINE: String = "Model timed out. Try a shorter chunk."
    const val INFERENCE_ENGINE_FAILED_LINE: String = "Reading failed. Try again."

    const val BAND_LABEL_MIC: String = "MIC"
    const val BAND_LABEL_MODEL: String = "MODEL"
    const val BAND_LABEL_MODEL_LOADING: String = "MODEL · WARMING"
    const val BAND_LABEL_MODEL_PAUSED: String = "MODEL · PAUSED"
    const val BAND_LABEL_MODEL_DOWNLOADING_FMT: String = "MODEL · %d%%"

    const val READING_PLACEHOLDER: String = "Reading the entry."

    const val YOU_LABEL: String = "YOU"

    const val PATTERNS_LINK: String = "PATTERNS →"

    const val HISTORY_LINK: String = "History"
    const val HISTORY_LINK_A11Y: String = "Open history"
    const val HISTORY_FOOTER_SEPARATOR: String = " · "
    const val HISTORY_FOOTER_PREFIX: String = "Last entry"
}

/** Demo / on-device summary stats consumed by the date strip + StatRibbon. */
data class CaptureStats(val kept: Int, val active: Int, val hitsThisMonth: Int, val cloud: Int)

/** Date-strip metadata. `dayNumber` is the install-day counter; `streakDays` is the streak length. */
data class CaptureMeta(
    val weekdayLabel: String,
    val monthDayLabel: String,
    val timeLabel: String,
    val dayNumber: Int,
    val streakDays: Int,
)
