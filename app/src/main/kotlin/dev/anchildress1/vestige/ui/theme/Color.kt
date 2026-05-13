package dev.anchildress1.vestige.ui.theme

import androidx.compose.ui.graphics.Color

// Canonical palette per poc/design-review.md §2.1 + poc/tokens.jsx. Dark only — no light theme.

// Surfaces
val Void: Color = Color(0xFF0A0E1A)
val Deep: Color = Void
val Bg: Color = Color(0xFF0E1124)
val S1: Color = Color(0xFF161A2E)
val S2: Color = Color(0xFF1E2238)
val S3: Color = Color(0xFF2A2E48)

// Ink levels — Faint is quieter than Mist (eyebrow rows, dim metadata).
val Ink: Color = Color(0xFFE8ECF4)
val Mist: Color = Color(0xFF7B8497)
val Faint: Color = Color(0xFF5F6A80)

// Mist alpha rails — Ghost (button outlines / drag handles), Hair (TraceBar inactive, card
// hairlines), Hair2 (faintest divider).
private const val MIST_R: Int = 0x7B
private const val MIST_G: Int = 0x84
private const val MIST_B: Int = 0x97
val Ghost: Color = Color(red = MIST_R, green = MIST_G, blue = MIST_B, alpha = 0x8C) // 0.55
val Hair: Color = Color(red = MIST_R, green = MIST_G, blue = MIST_B, alpha = 0x2E) // 0.18
val Hair2: Color = Color(red = MIST_R, green = MIST_G, blue = MIST_B, alpha = 0x17) // 0.09

// Accents — solid + documented alpha rails per tokens.jsx.
val Glow: Color = Color(0xFFA855F7)
val GlowDim: Color = Glow.copy(alpha = 0.18f)
val GlowSoft: Color = Glow.copy(alpha = 0.48f)
val GlowRule: Color = Glow.copy(alpha = 0.82f)

val Vapor: Color = Color(0xFF2563EB)
val VaporDim: Color = Vapor.copy(alpha = 0.18f)
val VaporSoft: Color = Vapor.copy(alpha = 0.48f)
val VaporRule: Color = Vapor.copy(alpha = 0.82f)

val Pulse: Color = Color(0xFF38A169)
val PulseDim: Color = Pulse.copy(alpha = 0.22f)

val ErrorRed: Color = Color(0xFFB3261E)
val ErrorSoft: Color = ErrorRed.copy(alpha = 0.50f)
