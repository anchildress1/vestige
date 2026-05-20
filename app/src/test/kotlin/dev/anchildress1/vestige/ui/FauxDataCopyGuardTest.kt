package dev.anchildress1.vestige.ui

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Release guard: demo/fixture phrases may exist in debug seed data, but must never ship in
 * production runtime sources under `app/src/main`.
 */
class FauxDataCopyGuardTest {

    @Test
    fun `main source tree contains no known fixture literals`() {
        val violations = mutableListOf<String>()
        var scanned = 0
        mainDir().walkTopDown()
            .filter { it.isFile && it.extension in SCANNED_EXTENSIONS }
            .forEach { file ->
                scanned++
                file.readLines().forEachIndexed { idx, line ->
                    if (FORBIDDEN.any { it.containsMatchIn(line) }) {
                        violations += "${file.name}:${idx + 1} -> ${line.trim()}"
                    }
                }
            }

        // A zero-file walk would pass vacuously — fail loud if mainDir() resolved wrong.
        assertTrue("Guard scanned no source files — mainDir() resolved incorrectly", scanned > 0)
        assertTrue(
            "Fixture/demo literals leaked into app/src/main: $violations",
            violations.isEmpty(),
        )
    }

    private fun mainDir(): File {
        val direct = sequenceOf(File("src/main"), File("app/src/main"))
            .map { it.absoluteFile }
            .firstOrNull { it.isDirectory }
        if (direct != null) return direct

        return generateSequence(File("").absoluteFile) { it.parentFile }
            .map { File(it, "app/src/main") }
            .firstOrNull { it.isDirectory }
            ?: error("Could not locate app/src/main from ${File("").absolutePath}")
    }

    private companion object {
        val SCANNED_EXTENSIONS = setOf("kt", "xml", "txt")

        val FORBIDDEN = listOf(
            Regex("""\bTuesday Meetings\b"""),
            Regex("""Fourth entry mentions Tuesday meetings""", RegexOption.IGNORE_CASE),
            Regex("""State before:\s*cruising\.""", RegexOption.IGNORE_CASE),
            Regex("""After:\s*crashed\.""", RegexOption.IGNORE_CASE),
            Regex("""\bThe Email\b"""),
            Regex(""""Tired"\s+drift""", RegexOption.IGNORE_CASE),
            Regex("""battery yanked""", RegexOption.IGNORE_CASE),
            Regex("""post-meeting energy crash""", RegexOption.IGNORE_CASE),
            Regex("""matches Tue-Meetings""", RegexOption.IGNORE_CASE),
            Regex("""rosy pocket""", RegexOption.IGNORE_CASE),
            Regex("""go-pro""", RegexOption.IGNORE_CASE),
        )
    }
}
