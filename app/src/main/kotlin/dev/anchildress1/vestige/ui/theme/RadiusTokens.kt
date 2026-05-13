package dev.anchildress1.vestige.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

// Radii per poc/design-review.md §2.3. No raw dp for corner shapes that map to the scale.
object RadiusTokens {
    val RPill: Dp = 9999.dp
    val RXL: Dp = 8.dp
    val RL: Dp = 8.dp
    val RM: Dp = 6.dp
    val RS: Dp = 4.dp
    val RXS: Dp = 4.dp

    val Pill: RoundedCornerShape = RoundedCornerShape(RPill)
    val XL: RoundedCornerShape = RoundedCornerShape(RXL)
    val L: RoundedCornerShape = RoundedCornerShape(RL)
    val M: RoundedCornerShape = RoundedCornerShape(RM)
    val S: RoundedCornerShape = RoundedCornerShape(RS)
    val XS: RoundedCornerShape = RoundedCornerShape(RXS)
}
