package dev.anchildress1.vestige.storage

import android.content.Context
import io.objectbox.BoxStore
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
}
