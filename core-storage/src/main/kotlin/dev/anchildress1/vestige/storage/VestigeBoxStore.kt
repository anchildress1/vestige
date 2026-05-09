package dev.anchildress1.vestige.storage

import android.content.Context
import io.objectbox.BoxStore
import java.io.File

/**
 * Thin factory around the generated `MyObjectBox` builder. The Phase 2 `EntryStore` (per
 * architecture-brief.md §AppContainer) wraps this with write semantics and the markdown
 * source-of-truth handoff; Phase 1 only needs the BoxStore lifecycle so the schema is
 * exercise-able by the smoke test.
 */
object VestigeBoxStore {

    /**
     * Build a [BoxStore] pointing at the app's internal storage. Caller owns lifecycle —
     * close on process tear-down.
     */
    fun open(context: Context): BoxStore = MyObjectBox.builder()
        .androidContext(context.applicationContext)
        .build()

    /**
     * Build a [BoxStore] at a caller-supplied directory. Used by tests and tooling that
     * don't want to touch the canonical app database location.
     */
    fun openAt(directory: File): BoxStore {
        require(directory.isDirectory || directory.mkdirs()) {
            "Cannot create or open ObjectBox directory: $directory"
        }
        return MyObjectBox.builder().directory(directory).build()
    }
}
