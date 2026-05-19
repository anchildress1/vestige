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
        mainDir().walkTopDown()
            .filter { it.isFile && (it.extension == "kt" || it.extension == "xml") }
            .forEach { file ->
                file.readLines().forEachIndexed { idx, line ->
                    if (FORBIDDEN.any { it.containsMatchIn(line) }) {
                        violations += "${file.name}:${idx + 1} -> ${line.trim()}"
                    }
                }
            }

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
        val FORBIDDEN = listOf(
            Regex("""\bTuesday Meetings\b"""),
            Regex("""Fourth entry mentions Tuesday meetings""", RegexOption.IGNORE_CASE),
            Regex("""State before:\s*cruising\.""", RegexOption.IGNORE_CASE),
            Regex("""After:\s*crashed\.""", RegexOption.IGNORE_CASE),
            Regex("""\bThe Email\b"""),
            Regex(""""Tired"\s+drift""", RegexOption.IGNORE_CASE),
        )
    }
}
