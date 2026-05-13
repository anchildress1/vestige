package dev.anchildress1.vestige.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
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

/**
 * Brand text vocabulary — read everywhere via `VestigeTheme.typography` using the same pattern
 * as `MaterialTheme.typography`. Slot names speak Vestige's design language (`displayBig` for
 * the Anton-condensed scoreboard numbers, `eyebrow` for the mono uppercase labels, `pCompact`
 * for the dense card-meta line). M3's `Typography` lives underneath as a one-way bridge so M3
 * components keep resolving styles through `MaterialTheme.typography` without per-call overrides.
 */
@Immutable
@Suppress("LongParameterList") // Theme objects are wide by design — one slot per token.
data class VestigeTypography(
    val displayBig: TextStyle,
    val h1: TextStyle,
    val h2: TextStyle,
    val p: TextStyle,
    val pCompact: TextStyle,
    val title: TextStyle,
    val titleCompact: TextStyle,
    val personaLabel: TextStyle,
    val eyebrow: TextStyle,
)

internal val ScoreboardTypography: VestigeTypography = buildScoreboardTypography()

private fun buildScoreboardTypography(): VestigeTypography {
    // DisplayBig — Anton at scale. 56sp default; capture stats and pattern hero numbers
    // override up to ~96sp inline. `tnum` locks digit widths so scoreboard numbers don't
    // jitter when they update — ADR-011 §"Type stack" / "Tabular nums on stats".
    val displayBig = TextStyle(
        fontFamily = VestigeFonts.Display,
        fontWeight = FontWeight.Normal,
        fontSize = 56.sp,
        lineHeight = 56.sp,
        letterSpacing = (-0.01).em,
        fontFeatureSettings = "tnum",
    )
    val p = TextStyle(
        fontFamily = VestigeFonts.Body,
        fontWeight = FontWeight.Normal,
        fontSize = 15.sp,
        lineHeight = 23.sp,
    )
    return VestigeTypography(
        displayBig = displayBig,
        h1 = TextStyle(
            fontFamily = VestigeFonts.Body,
            fontWeight = FontWeight.Medium,
            fontSize = 26.sp,
            lineHeight = 32.sp,
        ),
        h2 = TextStyle(
            fontFamily = VestigeFonts.Body,
            fontWeight = FontWeight.Medium,
            fontSize = 22.sp,
            lineHeight = 28.sp,
        ),
        p = p,
        pCompact = p.copy(fontSize = 13.sp, lineHeight = 18.sp),
        title = p.copy(fontWeight = FontWeight.Medium, fontSize = 18.sp, lineHeight = 24.sp),
        titleCompact = p.copy(fontWeight = FontWeight.Medium, fontSize = 15.sp, lineHeight = 20.sp),
        personaLabel = TextStyle(
            fontFamily = VestigeFonts.Mono,
            fontWeight = FontWeight.Bold,
            fontSize = 10.sp,
            letterSpacing = 0.20.em,
        ),
        eyebrow = TextStyle(
            fontFamily = VestigeFonts.Mono,
            fontWeight = FontWeight.SemiBold,
            fontSize = 10.sp,
            letterSpacing = 0.18.em,
        ),
    )
}

internal val LocalVestigeTypography = staticCompositionLocalOf { ScoreboardTypography }

/** M3 bridge — Material components resolve text through `MaterialTheme.typography.X`. */
internal val M3Typography: Typography = Typography(
    displayLarge = ScoreboardTypography.displayBig,
    displayMedium = ScoreboardTypography.displayBig.copy(fontSize = 40.sp, lineHeight = 40.sp),
    displaySmall = ScoreboardTypography.displayBig.copy(fontSize = 28.sp, lineHeight = 30.sp),
    headlineLarge = ScoreboardTypography.h1,
    headlineMedium = ScoreboardTypography.h2,
    headlineSmall = ScoreboardTypography.h2,
    titleLarge = ScoreboardTypography.title,
    titleMedium = ScoreboardTypography.title,
    titleSmall = ScoreboardTypography.titleCompact,
    bodyLarge = ScoreboardTypography.p,
    bodyMedium = ScoreboardTypography.p.copy(fontSize = 14.sp, lineHeight = 20.sp),
    bodySmall = ScoreboardTypography.pCompact,
    labelLarge = ScoreboardTypography.eyebrow.copy(fontSize = 12.sp, letterSpacing = 0.10.em),
    labelMedium = ScoreboardTypography.eyebrow,
    labelSmall = ScoreboardTypography.personaLabel,
)
