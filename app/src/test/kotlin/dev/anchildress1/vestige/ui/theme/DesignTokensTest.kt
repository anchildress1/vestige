package dev.anchildress1.vestige.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

private const val ALPHA_TOLERANCE: Float = 0.005f

/** Locks the canonical V palette per poc/design-review.md §2.1 + poc/tokens.jsx. */
class DesignTokensTest {

    @Test fun `void matches spec`() = assertEquals(Color(0xFF0A0E1A), Void)

    @Test fun `deep aliases void`() = assertEquals(Void, Deep)

    @Test fun `bg matches spec`() = assertEquals(Color(0xFF0E1124), Bg)

    @Test fun `s1 matches spec`() = assertEquals(Color(0xFF161A2E), S1)

    @Test fun `s2 matches spec`() = assertEquals(Color(0xFF1E2238), S2)

    @Test fun `s3 matches spec`() = assertEquals(Color(0xFF2A2E48), S3)

    @Test fun `ink matches spec`() = assertEquals(Color(0xFFE8ECF4), Ink)

    @Test fun `mist matches spec`() = assertEquals(Color(0xFF7B8497), Mist)

    @Test fun `glow matches spec`() = assertEquals(Color(0xFFA855F7), Glow)

    @Test fun `vapor matches spec`() = assertEquals(Color(0xFF2563EB), Vapor)

    @Test fun `pulse matches spec`() = assertEquals(Color(0xFF38A169), Pulse)

    @Test fun `error matches spec`() = assertEquals(Color(0xFFB3261E), ErrorRed)

    @Test fun `faint matches spec`() = assertEquals(Color(0xFF5F6A80), Faint)

    @Test
    fun `mist alpha rails carry the documented alphas`() {
        // 0.55 / 0.18 / 0.09 per poc/tokens.jsx, rounded to byte.
        assertEquals(0.55f, Ghost.alpha, ALPHA_TOLERANCE)
        assertEquals(0.18f, Hair.alpha, ALPHA_TOLERANCE)
        assertEquals(0.09f, Hair2.alpha, ALPHA_TOLERANCE)
        // RGB tracks Mist for every rail.
        listOf(Ghost, Hair, Hair2).forEach { rail ->
            assertEquals(Mist.red, rail.red, ALPHA_TOLERANCE)
            assertEquals(Mist.green, rail.green, ALPHA_TOLERANCE)
            assertEquals(Mist.blue, rail.blue, ALPHA_TOLERANCE)
        }
    }

    @Test
    fun `glow alpha rails carry the documented alphas`() {
        assertEquals(0.18f, GlowDim.alpha, ALPHA_TOLERANCE)
        assertEquals(0.48f, GlowSoft.alpha, ALPHA_TOLERANCE)
        assertEquals(0.82f, GlowRule.alpha, ALPHA_TOLERANCE)
    }

    @Test
    fun `vapor alpha rails carry the documented alphas`() {
        assertEquals(0.18f, VaporDim.alpha, ALPHA_TOLERANCE)
        assertEquals(0.48f, VaporSoft.alpha, ALPHA_TOLERANCE)
        assertEquals(0.82f, VaporRule.alpha, ALPHA_TOLERANCE)
    }

    @Test
    fun `pulse and error alpha rails carry the documented alphas`() {
        assertEquals(0.22f, PulseDim.alpha, ALPHA_TOLERANCE)
        assertEquals(0.50f, ErrorSoft.alpha, ALPHA_TOLERANCE)
    }

    @Test
    fun `accents are distinct`() {
        val accents = listOf(Glow, Vapor, Pulse, ErrorRed)
        assertEquals(accents.size, accents.toSet().size)
    }

    @Test
    fun `radii match spec`() {
        assertEquals(9999.dp, RadiusTokens.RPill)
        assertEquals(8.dp, RadiusTokens.RXL)
        assertEquals(8.dp, RadiusTokens.RL)
        assertEquals(6.dp, RadiusTokens.RM)
        assertEquals(4.dp, RadiusTokens.RS)
        assertEquals(4.dp, RadiusTokens.RXS)
    }

    @Test
    fun `font families assigned`() {
        assertEquals(FontFamily.SansSerif, VestigeFonts.Body)
        assertEquals(FontFamily.Serif, VestigeFonts.Display)
        assertEquals(FontFamily.Monospace, VestigeFonts.Mono)
        assertNotEquals(VestigeFonts.Body, VestigeFonts.Display)
        assertNotEquals(VestigeFonts.Body, VestigeFonts.Mono)
    }

    @Test
    fun `HDisplay is editorial italic serif at 38sp`() {
        val style = VestigeTextStyles.HDisplay
        assertEquals(VestigeFonts.Display, style.fontFamily)
        assertEquals(FontStyle.Italic, style.fontStyle)
        assertEquals(38f, style.fontSize.value)
    }

    @Test
    fun `H1 is body sans 26sp`() {
        val style = VestigeTextStyles.H1
        assertEquals(VestigeFonts.Body, style.fontFamily)
        assertEquals(26f, style.fontSize.value)
    }

    @Test
    fun `H2 is body sans 22sp`() {
        val style = VestigeTextStyles.H2
        assertEquals(VestigeFonts.Body, style.fontFamily)
        assertEquals(22f, style.fontSize.value)
    }

    @Test
    fun `P is body sans 15sp`() {
        val style = VestigeTextStyles.P
        assertEquals(VestigeFonts.Body, style.fontFamily)
        assertEquals(15f, style.fontSize.value)
    }

    @Test
    fun `PersonaLabel is mono 10sp with 0_24em tracking`() {
        val style = VestigeTextStyles.PersonaLabel
        assertEquals(VestigeFonts.Mono, style.fontFamily)
        assertEquals(10f, style.fontSize.value)
        assertEquals(0.24f, style.letterSpacing.value, 0.0001f)
    }

    @Test
    fun `Eyebrow is mono 10sp with 0_20em tracking`() {
        val style = VestigeTextStyles.Eyebrow
        assertEquals(VestigeFonts.Mono, style.fontFamily)
        assertEquals(10f, style.fontSize.value)
        assertEquals(0.20f, style.letterSpacing.value, 0.0001f)
    }

    @Test
    fun `screen facing typography slots map to vestige styles`() {
        assertEquals(VestigeTextStyles.Title, VestigeTypography.titleLarge)
        assertEquals(VestigeTextStyles.Title, VestigeTypography.titleMedium)
        assertEquals(VestigeTextStyles.TitleCompact, VestigeTypography.titleSmall)
        assertEquals(VestigeTextStyles.H2, VestigeTypography.headlineSmall)
        assertEquals(VestigeTextStyles.PCompact, VestigeTypography.bodySmall)
    }
}
