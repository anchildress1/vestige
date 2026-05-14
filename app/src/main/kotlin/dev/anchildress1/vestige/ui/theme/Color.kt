package dev.anchildress1.vestige.ui.theme

import androidx.compose.ui.graphics.Color

// Scoreboard palette per ADR-011 + poc/energy-tokens.jsx. Dark only.
// sRGB hex literals are pre-converted from oklch source values — the comment on each line
// pins the oklch input so the conversion is auditable. No runtime oklch path.

// Surfaces — warm espresso, not blue. Floor → Deep → S1 → S2 → S3 raise toward the user.
val Floor: Color = Color(0xFF0B0604) // oklch(13% 0.012 55) — page floor
val Deep: Color = Color(0xFF100A06) // oklch(15% 0.014 55) — device interior / shell
val S1: Color = Color(0xFF1A120D) // oklch(19% 0.016 55) — card base
val S2: Color = Color(0xFF261D17) // oklch(24% 0.018 55) — raised
val S3: Color = Color(0xFF362B24) // oklch(30% 0.020 55) — hover / outline

// Ink — warm cream, never pure white. Body / dim metadata / faint annotation.
val Ink: Color = Color(0xFFF3EEE3) // oklch(95% 0.015 85)
val Dim: Color = Color(0xFFA69D91) // oklch(70% 0.020 75)
val Faint: Color = Color(0xFF797066) // oklch(55% 0.018 70)

// Ink alpha rails — Ghost (dropped text / disabled), Hair (hairline borders), Hair2 (faintest).
// RGB tracks the Dim line so they read coherent over warm surfaces.
private const val HAIR_R: Int = 0xA7
private const val HAIR_G: Int = 0x9D
private const val HAIR_B: Int = 0x91
val Ghost: Color = Color(red = HAIR_R, green = HAIR_G, blue = HAIR_B, alpha = 0x99) // 0.60
val Hair: Color = Color(red = HAIR_R, green = HAIR_G, blue = HAIR_B, alpha = 0x1F) // 0.12
val Hair2: Color = Color(red = HAIR_R, green = HAIR_G, blue = HAIR_B, alpha = 0x0F) // 0.06
val TapeGrain: Color = Color(red = HAIR_R, green = HAIR_G, blue = HAIR_B, alpha = 0x08) // 0.03 per POC TAPE_BG

// Accents — ONE per element. Lime and Coral never co-occur on the same atom.
// Lime: signal — live / active / "on" (model ready or listening, pattern active, AppTop status
// pill in both idle and recording states per ADR-011 Addendum 2026-05-14).
val Lime: Color = Color(0xFFD8E830) // oklch(89% 0.19 115)
val LimeDim: Color = Lime.copy(alpha = 0.20f)
val LimeSoft: Color = Lime.copy(alpha = 0.55f)

// Coral: heat — REC button on-air, roast, danger / destructive. AppTop status pill stays lime
// per ADR-011 Addendum 2026-05-14. The Mist `ErrorRed` collapses into coral.
val Coral: Color = Color(0xFFFF6254) // oklch(72% 0.21 28)
val CoralDim: Color = Coral.copy(alpha = 0.20f)
val CoralSoft: Color = Coral.copy(alpha = 0.55f)

// Teal: cool — resolved, settled (resolved patterns, dismissed states).
val Teal: Color = Color(0xFF36CCCC) // oklch(77% 0.12 195)
val TealDim: Color = Teal.copy(alpha = 0.20f)

// Ember: warm gold — secondary stats / snoozed accents.
val Ember: Color = Color(0xFFFFAC41) // oklch(82% 0.16 65)

// Destructive maps to Coral. Material 3 components reach for colorScheme.error directly, so the
// `ErrorRed` name is kept as the bridge — Theme.kt assigns Coral to colorScheme.error.
val ErrorRed: Color = Coral
