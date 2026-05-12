package dev.anchildress1.vestige.ui.patterns

import android.app.Application

/**
 * Stub Application for Compose UI tests in this package. Avoids constructing the real
 * `VestigeApplication`, which would open a process-wide `BoxStore` via `AppContainer` and
 * corrupt the native-library state when individual tests open their own per-test ObjectBox.
 */
internal class PatternsTestApplication : Application()
