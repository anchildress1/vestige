package dev.anchildress1.vestige.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp

// Type stack per ADR-011 + poc/energy-tokens.jsx.
// Real .ttf bundling (Anton, Space Grotesk) is a Phase 5 swap if it earns its keep — until then,
// each family aliases a system fallback. Call sites consume VestigeFonts.Display / Body / Mono so
// the swap is a one-line change here.
//
// Display reads as a condensed sans on system; the real font is Anton (huge condensed numbers).
// Body reads as the system sans; the real font is Space Grotesk.
// Mono is JetBrains Mono; system Monospace stays the fallback.
object VestigeFonts {
    val Display: FontFamily = FontFamily.SansSerif
    val Body: FontFamily = FontFamily.SansSerif
    val Mono: FontFamily = FontFamily.Monospace
}

object VestigeTextStyles {
    // DisplayBig — Anton at scale. 56sp default; capture stats and pattern hero numbers
    // override up to ~96sp inline. `tnum` locks digit widths so scoreboard numbers don't
    // jitter when they update — ADR-011 §"Type stack" / "Tabular nums on stats".
    val DisplayBig: TextStyle = TextStyle(
        fontFamily = VestigeFonts.Display,
        fontWeight = FontWeight.Normal,
        fontSize = 56.sp,
        lineHeight = 56.sp,
        letterSpacing = (-0.01).em,
        fontFeatureSettings = "tnum",
    )

    val H1: TextStyle = TextStyle(
        fontFamily = VestigeFonts.Body,
        fontWeight = FontWeight.Medium,
        fontSize = 26.sp,
        lineHeight = 32.sp,
    )

    val H2: TextStyle = TextStyle(
        fontFamily = VestigeFonts.Body,
        fontWeight = FontWeight.Medium,
        fontSize = 22.sp,
        lineHeight = 28.sp,
    )

    val P: TextStyle = TextStyle(
        fontFamily = VestigeFonts.Body,
        fontWeight = FontWeight.Normal,
        fontSize = 15.sp,
        lineHeight = 23.sp,
    )

    val PersonaLabel: TextStyle = TextStyle(
        fontFamily = VestigeFonts.Mono,
        fontWeight = FontWeight.Bold,
        fontSize = 10.sp,
        letterSpacing = 0.20.em,
    )

    val Eyebrow: TextStyle = TextStyle(
        fontFamily = VestigeFonts.Mono,
        fontWeight = FontWeight.SemiBold,
        fontSize = 10.sp,
        letterSpacing = 0.18.em,
    )

    val Title: TextStyle = P.copy(fontWeight = FontWeight.Medium, fontSize = 18.sp, lineHeight = 24.sp)

    val TitleCompact: TextStyle = P.copy(fontWeight = FontWeight.Medium, fontSize = 15.sp, lineHeight = 20.sp)

    val PCompact: TextStyle = P.copy(fontSize = 13.sp, lineHeight = 18.sp)
}

val VestigeTypography: Typography = Typography(
    displayLarge = VestigeTextStyles.DisplayBig,
    displayMedium = VestigeTextStyles.DisplayBig.copy(fontSize = 40.sp, lineHeight = 40.sp),
    displaySmall = VestigeTextStyles.DisplayBig.copy(fontSize = 28.sp, lineHeight = 30.sp),
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
