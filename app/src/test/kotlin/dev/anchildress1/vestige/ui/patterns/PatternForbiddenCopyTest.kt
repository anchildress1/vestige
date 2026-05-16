package dev.anchildress1.vestige.ui.patterns

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * `spec-pattern-action-buttons.md` §P0.6 — the retired vocabulary ("snooze", "dismiss",
 * "mark resolved", "resolve") must never reach a user-visible surface. The persisted enum /
 * state names (`SNOOZED`, `PatternState.DROPPED`, …) stay as code identifiers; only rendered
 * copy is policed. This walks the real `app/src/main` tree so a future copy regression fails
 * here instead of in a screenshot review.
 *
 * Patterns use `\b` word boundaries where needed to avoid false positives on unrelated English
 * words (e.g. "resolution"). Block comments (/* … */) are intentionally out of scope — they
 * cannot appear inside a `Text("…")` literal, so the scan can only be falsely strict, never
 * falsely green.
 */
class PatternForbiddenCopyTest {

    private val forbidden: List<Regex> = listOf(
        Regex("""\bsnooze""", RegexOption.IGNORE_CASE),
        Regex("""\bdismiss""", RegexOption.IGNORE_CASE),
        Regex("""mark\s+resolved""", RegexOption.IGNORE_CASE),
        Regex("""\bresolve[sd]?\b""", RegexOption.IGNORE_CASE),
    )

    @Test
    fun `no string resource value contains retired user-facing vocabulary`() {
        val stringsXml = File(mainDir(), "res/values/strings.xml")
        assertTrue("strings.xml must exist at ${stringsXml.absolutePath}", stringsXml.isFile)

        val violations = STRING_ELEMENT.findAll(stringsXml.readText())
            .map { it.groupValues[1].trim() }
            .filter { value -> forbidden.any { it.containsMatchIn(value) } }
            .toList()

        assertTrue(
            "Retired vocabulary in string resource values: $violations",
            violations.isEmpty(),
        )
    }

    @Test
    fun `no composable Text literal contains retired user-facing vocabulary`() {
        val violations = mutableListOf<String>()
        mainDir().walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .forEach { file ->
                file.readLines().forEachIndexed { idx, rawLine ->
                    val line = rawLine.stripComment()
                    TEXT_LITERALS.forEach { pattern ->
                        pattern.findAll(line).forEach { match ->
                            val literal = match.groupValues[1]
                            if (forbidden.any { it.containsMatchIn(literal) }) {
                                violations += "${file.name}:${idx + 1} -> \"$literal\""
                            }
                        }
                    }
                }
            }

        assertTrue(
            "Retired vocabulary in Text(\"...\") literals: $violations",
            violations.isEmpty(),
        )
    }

    /**
     * Drop a line comment tail so `// snooze later` style notes never trip the scan. Only
     * trims an unquoted `//`; a `//` inside a string literal stays (rare, and conservative —
     * keeping it can only make the test stricter, never falsely green).
     */
    private fun String.stripComment(): String {
        var inString = false
        var i = 0
        while (i < length - 1) {
            val c = this[i]
            if (c == '"' && (i == 0 || this[i - 1] != '\\')) inString = !inString
            if (!inString && c == '/' && this[i + 1] == '/') return substring(0, i)
            i++
        }
        return this
    }

    private fun mainDir(): File {
        // Gradle runs JVM unit tests with the module dir as CWD; walk up as a fallback so the
        // test still resolves if the runner's working directory differs.
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
        // <string name="...">VALUE</string> — captures the element text, ignoring the key.
        val STRING_ELEMENT = Regex("""<string[^>]*>(.*?)</string>""", RegexOption.DOT_MATCHES_ALL)

        // Flags a user-visible string literal: the first positional arg of a Text(...) call
        // (Text("Dropped.")) OR a `text = "..."` named arg (Text(modifier = m, text = "Skip")).
        // stringResource(R.string.x)/identifiers are out of scope by design.
        val TEXT_LITERALS = listOf(
            Regex("""\bText\s*\(\s*"((?:[^"\\]|\\.)*)""""),
            Regex("""\btext\s*=\s*"((?:[^"\\]|\\.)*)""""),
        )
    }
}
