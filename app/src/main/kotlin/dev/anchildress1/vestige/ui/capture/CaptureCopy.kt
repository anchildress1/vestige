package dev.anchildress1.vestige.ui.capture

/**
 * Capture-screen microcopy. Strings sourced from `docs/ux-copy.md` §"Capture Screen" — pull
 * here, never invent inline. Tests assert verbatim so doc drift is loud.
 */
object CaptureCopy {
    const val HERO_QUESTION: String = "WHAT HAPPENED?"

    // Only the trailing "?" is the coral accent — the rest of the hero is ink. Matches the
    // empty-state treatment (terminal punctuation accented, not the word).
    const val HERO_HIGHLIGHT_FROM_END: String = "?"

    const val OR_TYPE: String = "OR TYPE →"

    const val TYPE_FIELD_LABEL: String = "Typed entry text"
    const val TYPE_PLACEHOLDER: String = "What happened."
    const val TYPE_SUBMIT: String = "Save entry"

    const val REC_LABEL_IDLE: String = "Record"
    const val REC_LABEL_RECORDING: String = "Stop"

    const val LIVE_RECORDING_EYEBROW: String = "RECORDING"
    const val LIVE_LEVEL_EYEBROW: String = "● LEVEL · LIVE"
    const val LIVE_REMAIN_LABEL: String = "REMAIN"
    const val LIVE_SECONDS_LABEL: String = "SECONDS"

    const val LIVE_STOP_PRIMARY: String = "STOP · FILE IT"
    const val LIVE_DISCARD_SECONDARY: String = "DISCARD · DON'T SAVE"

    const val NO_ENTRIES_YET: String = "NO ENTRIES YET · FIRST ONE TAKES 30 SECONDS"
    const val PATTERNS_PEEK_EYEBROW_FMT: String = "● %d ACTIVE PATTERNS"
    const val PATTERNS_PEEK_SEPARATOR: String = "  ·  "

    // Shown under the model-not-ready spinner (REC button stand-in) — tells the user what
    // they're waiting on. Sourced from docs/ux-copy.md §"Capture — model not ready".
    const val MODEL_LOADING_LINE: String = "Loading the model. One moment."
    const val MODEL_PAUSED_LINE: String = "Wi-Fi dropped. Reconnect to finish the model download."
    const val MODEL_DOWNLOADING_LINE_FMT: String = "Downloading the model. %d%%."

    const val MIC_DENIED_LINE: String = "Mic permission required to record. Settings → Permissions."
    const val MIC_UNAVAILABLE_LINE: String = "Mic unavailable. Try typing."
    const val MIC_BLOCKED_LINE: String = "Mic blocked at the system level."
    const val MIC_BLOCKED_SETTINGS_LINE: String = "Settings → Apps → Vestige → Permissions → Microphone."
    const val USE_TYPED_INSTEAD: String = "Use typed entry instead"
    const val INFERENCE_PARSE_FAILED_LINE: String = "Model couldn't read that. Try again."
    const val INFERENCE_TIMED_OUT_LINE: String = "Model timed out. Try a shorter chunk."
    const val INFERENCE_ENGINE_FAILED_LINE: String = "Reading failed. Try again."

    const val BAND_LABEL_MIC: String = "MIC"
    const val BAND_LABEL_MODEL: String = "MODEL"

    const val PATTERNS_LINK: String = "PATTERNS →"

    const val SETTINGS_LINK: String = "Settings"
    const val HISTORY_FOOTER_SEPARATOR: String = " · "
    const val HISTORY_FOOTER_PREFIX: String = "Last entry"
}
