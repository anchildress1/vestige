package dev.anchildress1.vestige.ui.components

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import java.util.Locale

/**
 * Uppercased editorial headline with the final whitespace-delimited token accented (e.g.
 * `YET.` lime), matching `poc/patterns-empty-final.png`. Shared so History and Patterns
 * render identically. Single-word headers render fully accented.
 */
fun accentedHeadline(raw: String, inkColor: Color, accentColor: Color) = buildAnnotatedString {
    val up = raw.uppercase(Locale.US)
    val split = up.lastIndexOf(' ')
    if (split > 0) {
        withStyle(SpanStyle(color = inkColor)) { append(up.substring(0, split + 1)) }
    }
    withStyle(SpanStyle(color = accentColor)) { append(up.substring(split + 1)) }
}
