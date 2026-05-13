package dev.anchildress1.vestige.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp

// System fallbacks now; .ttf swap later won't touch call-sites. Spec: poc/design-review.md §2.2 + §8.
object VestigeFonts {
    val Body: FontFamily = FontFamily.SansSerif
    val Display: FontFamily = FontFamily.Serif
    val Mono: FontFamily = FontFamily.Monospace
}

object VestigeTextStyles {
    val HDisplay: TextStyle = TextStyle(
        fontFamily = VestigeFonts.Display,
        fontStyle = FontStyle.Italic,
        fontSize = 38.sp,
        lineHeight = 44.sp,
    )

    val H1: TextStyle = TextStyle(
        fontFamily = VestigeFonts.Body,
        fontWeight = FontWeight.Medium,
        fontSize = 26.sp,
        lineHeight = 32.sp,
    )

    val P: TextStyle = TextStyle(
        fontFamily = VestigeFonts.Body,
        fontWeight = FontWeight.Normal,
        fontSize = 15.sp,
        lineHeight = 22.sp,
    )

    val PersonaLabel: TextStyle = TextStyle(
        fontFamily = VestigeFonts.Mono,
        fontWeight = FontWeight.Medium,
        fontSize = 10.sp,
        letterSpacing = 0.24.em,
    )

    val Eyebrow: TextStyle = TextStyle(
        fontFamily = VestigeFonts.Mono,
        fontWeight = FontWeight.Medium,
        fontSize = 10.sp,
        letterSpacing = 0.20.em,
    )

    val H2: TextStyle = H1.copy(fontSize = 22.sp, lineHeight = 28.sp)

    val Title: TextStyle = P.copy(fontWeight = FontWeight.Medium, fontSize = 18.sp, lineHeight = 24.sp)

    val TitleCompact: TextStyle = P.copy(fontWeight = FontWeight.Medium, fontSize = 15.sp, lineHeight = 20.sp)

    val PCompact: TextStyle = P.copy(fontSize = 13.sp, lineHeight = 18.sp)
}

val VestigeTypography: Typography = Typography(
    displayLarge = VestigeTextStyles.HDisplay,
    displayMedium = VestigeTextStyles.HDisplay.copy(fontSize = 32.sp, lineHeight = 38.sp),
    displaySmall = VestigeTextStyles.HDisplay.copy(fontSize = 28.sp, lineHeight = 34.sp),
    headlineLarge = VestigeTextStyles.H1,
    headlineMedium = VestigeTextStyles.H2,
    headlineSmall = VestigeTextStyles.H2,
    titleLarge = VestigeTextStyles.Title,
    titleMedium = VestigeTextStyles.Title,
    titleSmall = VestigeTextStyles.TitleCompact,
    bodyLarge = VestigeTextStyles.P,
    bodyMedium = VestigeTextStyles.P.copy(fontSize = 14.sp, lineHeight = 20.sp),
    bodySmall = VestigeTextStyles.PCompact,
    labelLarge = VestigeTextStyles.Eyebrow.copy(fontSize = 12.sp, letterSpacing = 0.10.em),
    labelMedium = VestigeTextStyles.Eyebrow,
    labelSmall = VestigeTextStyles.PersonaLabel,
)
