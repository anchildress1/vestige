package dev.anchildress1.vestige.testing

import dev.anchildress1.vestige.storage.MyObjectBox
import io.objectbox.BoxStore
import java.io.File
import java.nio.file.Files

private val TEST_ROOT: File by lazy {
    val root = File(System.getProperty("java.io.tmpdir"), "vestige-test-objectbox")
    check(root.exists() || root.mkdirs()) { "Failed to create ObjectBox test root: $root" }
    root
}

/** Per-test temp root under the JVM tmpdir. Caller owns cleanup via [cleanupObjectBoxTempRoot]. */
fun newModuleTempRoot(prefix: String): File = Files.createTempDirectory(TEST_ROOT.toPath(), prefix).toFile()

/**
 * Returns a sentinel `File` whose path starts with `BoxStore.IN_MEMORY_PREFIX`. ObjectBox keys
 * stores by URI; any path beginning with `memory:` is routed to the in-process registry instead
 * of being mkdir'd on disk. The nanoTime suffix gives per-test isolation; passing the same
 * sentinel back to [openInMemoryBoxStore] reattaches to the same backing store.
 */
fun newInMemoryObjectBoxDirectory(prefix: String): File =
    File("${BoxStore.IN_MEMORY_PREFIX}$prefix${System.nanoTime()}")

fun openInMemoryBoxStore(directory: File): BoxStore = MyObjectBox.builder().directory(directory).build()

/**
 * Releases [dataDir] (no-op for in-memory paths — BoxStore handles the registry teardown via
 * `close()`) and recursively deletes [root]. Always invokes the recursive delete, even if the
 * BoxStore-side cleanup throws, so a partial failure can't leak the temp tree.
 */
fun cleanupObjectBoxTempRoot(root: File, dataDir: File) {
    try {
        if (dataDir.exists()) {
            BoxStore.deleteAllFiles(dataDir)
        }
    } finally {
        if (root.exists()) {
            check(root.deleteRecursively()) { "Failed to delete test temp root: $root" }
        }
    }
}
