package dev.anchildress1.vestige.ui.capture

/** Chrome handlers grouped together so the IdleLayout signature stays under the param cap. */
data class IdleChromeCallbacks(
    val onPersonaTap: (() -> Unit)? = null,
    val onStatusTap: (() -> Unit)? = null,
    val onPatternsTap: (() -> Unit)? = null,
)
