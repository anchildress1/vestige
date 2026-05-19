package dev.anchildress1.vestige.ui.capture

/** Chrome data + handlers for the idle surface; grouped to keep signatures under the param cap. */
data class IdleChromeCallbacks(
    val onStatusTap: (() -> Unit)? = null,
    val onPatternsTap: (() -> Unit)? = null,
    val onHistoryTap: (() -> Unit)? = null,
    val onSettingsTap: (() -> Unit)? = null,
    val onOpenEntryDetail: ((Long) -> Unit)? = null,
    val lastEntryFooter: LastEntryFooter? = null,
    val patternsPeek: CapturePatternsPeek? = null,
)
