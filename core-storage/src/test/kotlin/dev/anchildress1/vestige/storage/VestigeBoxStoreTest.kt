package dev.anchildress1.vestige.storage

import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class VestigeBoxStoreTest {

    @Test
    fun `openAt throws when the path cannot become a directory`(@TempDir tempDir: File) {
        // Place a regular file where a directory is expected; mkdirs() on a child of a file fails.
        val blocker = File(tempDir, "blocker.bin").apply { writeText("not a dir") }
        val impossible = File(blocker, "child")
        val ex = assertThrows(IllegalArgumentException::class.java) {
            VestigeBoxStore.openAt(impossible)
        }
        assertTrue(ex.message!!.contains("Cannot create or open ObjectBox directory")) {
            "Unexpected error message: ${ex.message}"
        }
    }
}
