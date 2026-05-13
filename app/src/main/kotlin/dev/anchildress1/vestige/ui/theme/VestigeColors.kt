package dev.anchildress1.vestige.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * Brand color vocabulary for the Scoreboard direction. Slot names speak Vestige's design language
 * (`lime` for signal, `coral` for heat, `dim` for secondary text, `hair` for hairlines), not
 * Material 3's primary / secondary / surface vocabulary. M3 lives underneath as a one-way bridge
 * (`darkColorScheme` in `Theme.kt`); every other foreground / background / accent at the call
 * site reads from this object via [VestigeTheme.colors] using the same pattern as
 * `MaterialTheme.colorScheme`.
 */
@Immutable
@Suppress("LongParameterList") // Theme objects are wide by design — one slot per token.
data class VestigeColors(
    // Surfaces — warm espresso, not blue. Floor → Deep → S1 → S2 → S3 raise toward the user.
    val floor: Color,
    val deep: Color,
    val s1: Color,
    val s2: Color,
    val s3: Color,
    // Ink — warm cream. Primary / secondary / tertiary / dropped text.
    val ink: Color,
    val dim: Color,
    val faint: Color,
    val ghost: Color,
    // Hairlines + tape grain.
    val hair: Color,
    val hair2: Color,
    val tapeGrain: Color,
    // Accents — one per element. Lime = signal; coral = heat / recording / destructive;
    // teal = settled; ember = secondary stats / snoozed.
    val lime: Color,
    val limeDim: Color,
    val limeSoft: Color,
    val coral: Color,
    val coralDim: Color,
    val coralSoft: Color,
    val teal: Color,
    val tealDim: Color,
    val ember: Color,
    val errorRed: Color,
)

/** Canonical instance — every slot resolves to the Scoreboard tokens in `Color.kt`. */
internal val ScoreboardColors: VestigeColors = VestigeColors(
    floor = Floor,
    deep = Deep,
    s1 = S1,
    s2 = S2,
    s3 = S3,
    ink = Ink,
    dim = Dim,
    faint = Faint,
    ghost = Ghost,
    hair = Hair,
    hair2 = Hair2,
    tapeGrain = TapeGrain,
    lime = Lime,
    limeDim = LimeDim,
    limeSoft = LimeSoft,
    coral = Coral,
    coralDim = CoralDim,
    coralSoft = CoralSoft,
    teal = Teal,
    tealDim = TealDim,
    ember = Ember,
    errorRed = ErrorRed,
)

internal val LocalVestigeColors = staticCompositionLocalOf { ScoreboardColors }
