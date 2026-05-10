package dev.anchildress1.vestige.lifecycle

/** Lifecycle states for the conditional foreground service per ADR-004. */
enum class BackgroundExtractionLifecycleState {
    NORMAL,
    PROMOTING,
    FOREGROUND,
    KEEP_ALIVE,
    DEMOTING,
}
