package dev.anchildress1.vestige.storage

import android.content.Context
import dev.anchildress1.vestige.model.ExtractionStatus
import io.objectbox.BoxStore
import io.objectbox.kotlin.boxFor
import java.io.File

/** Thin factory around the generated `MyObjectBox` builder. Caller owns BoxStore lifecycle. */
object VestigeBoxStore {

    fun open(context: Context): BoxStore = MyObjectBox.builder()
        .androidContext(context.applicationContext)
        .build()

    /** Test/tooling override — points at a custom directory. */
    fun openAt(directory: File): BoxStore {
        require(directory.isDirectory || directory.mkdirs()) {
            "Cannot create or open ObjectBox directory: $directory"
        }
        return MyObjectBox.builder().directory(directory).build()
    }

    /**
     * Cold-start recovery query: returns IDs of entries whose extraction did not finish. The
     * caller owns the [BoxStore] — this avoids the duplicate-open crash once `EntryStore`
     * (architecture-brief §"AppContainer Ownership") lands and becomes the sole BoxStore owner.
     */
    fun findNonTerminalEntryIds(boxStore: BoxStore): List<Long> = boxStore.boxFor<EntryEntity>()
        .all
        .asSequence()
        .filter { entry -> !entry.extractionStatus.isTerminal() }
        .map(EntryEntity::id)
        .toList()

    private fun ExtractionStatus.isTerminal(): Boolean = when (this) {
        ExtractionStatus.COMPLETED, ExtractionStatus.TIMED_OUT, ExtractionStatus.FAILED -> true
        ExtractionStatus.PENDING, ExtractionStatus.RUNNING -> false
    }
}
