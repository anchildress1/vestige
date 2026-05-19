package dev.anchildress1.vestige.ui.components

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import java.util.Locale

/**
 * Uppercased editorial headline with only the **trailing punctuation** accented (e.g. the
 * `.` in `NOTHING RECORDED YET.` coral) — the shared empty-state treatment so History and
 * Patterns render identically. No trailing punctuation ⇒ no accent.
 */
fun accentedHeadline(raw: String, inkColor: Color, accentColor: Color) = buildAnnotatedString {
    val up = raw.uppercase(Locale.US)
    val punctStart = up.indexOfLast { it.isLetterOrDigit() } + 1
    if (punctStart > 0) {
        withStyle(SpanStyle(color = inkColor)) { append(up.substring(0, punctStart)) }
    }
    if (punctStart < up.length) {
        withStyle(SpanStyle(color = accentColor)) { append(up.substring(punctStart)) }
    }
}
