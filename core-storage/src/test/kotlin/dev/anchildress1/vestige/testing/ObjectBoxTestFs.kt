package dev.anchildress1.vestige.testing

import dev.anchildress1.vestige.storage.MyObjectBox
import io.objectbox.BoxStore
import java.io.File
import java.nio.file.Files

private val TEST_ROOT: File = repoRoot().resolve("core-storage/build/test-objectbox").apply { mkdirs() }

fun newModuleTempRoot(prefix: String): File = Files.createTempDirectory(TEST_ROOT.toPath(), prefix).toFile()

fun newInMemoryObjectBoxDirectory(prefix: String): File =
    File("${BoxStore.IN_MEMORY_PREFIX}$prefix${System.nanoTime()}")

fun openInMemoryBoxStore(directory: File): BoxStore = MyObjectBox.builder().directory(directory).build()

fun cleanupObjectBoxTempRoot(root: File, dataDir: File) {
    if (dataDir.exists()) {
        BoxStore.deleteAllFiles(dataDir)
    }
    check(!root.exists() || root.deleteRecursively()) {
        "Failed to delete test temp root: $root"
    }
}

private fun repoRoot(): File {
    val userDir = requireNotNull(System.getProperty("user.dir")) { "user.dir is not set" }
    var current = File(userDir).absoluteFile
    while (!File(current, "settings.gradle.kts").exists()) {
        current = current.parentFile ?: error("Could not locate repo root from $userDir")
    }
    return current
}
