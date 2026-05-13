package dev.anchildress1.vestige.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

private const val ALPHA_TOLERANCE: Float = 0.005f

/** Locks the Scoreboard palette + type + radii per ADR-011 + poc/energy-tokens.jsx. */
class DesignTokensTest {

    // Surface tokens — oklch values pre-converted to sRGB literals.
    @Test fun `floor matches spec`() = assertEquals(Color(0xFF0B0604), Floor)

    @Test fun `deep matches spec`() = assertEquals(Color(0xFF100A06), Deep)

    @Test fun `s1 matches spec`() = assertEquals(Color(0xFF1A120D), S1)

    @Test fun `s2 matches spec`() = assertEquals(Color(0xFF261D17), S2)

    @Test fun `s3 matches spec`() = assertEquals(Color(0xFF362B24), S3)

    // Ink levels.
    @Test fun `ink matches spec`() = assertEquals(Color(0xFFF3EEE3), Ink)

    @Test fun `dim matches spec`() = assertEquals(Color(0xFFA69D91), Dim)

    @Test fun `faint matches spec`() = assertEquals(Color(0xFF797066), Faint)

    // Accents.
    @Test fun `lime matches spec`() = assertEquals(Color(0xFFD8E830), Lime)

    @Test fun `coral matches spec`() = assertEquals(Color(0xFFFF6254), Coral)

    @Test fun `teal matches spec`() = assertEquals(Color(0xFF36CCCC), Teal)

    @Test fun `ember matches spec`() = assertEquals(Color(0xFFFFAC41), Ember)

    @Test fun `error aliases coral`() = assertEquals(Coral, ErrorRed)

    @Test
    fun `ink alpha rails carry the documented alphas`() {
        // 0.60 / 0.12 / 0.06 per poc/energy-tokens.jsx, rounded to byte.
        assertEquals(0.60f, Ghost.alpha, ALPHA_TOLERANCE)
        assertEquals(0.12f, Hair.alpha, ALPHA_TOLERANCE)
        assertEquals(0.06f, Hair2.alpha, ALPHA_TOLERANCE)
        // RGB tracks the Dim hue family for every rail so they read coherent over warm surfaces.
        listOf(Ghost, Hair, Hair2).forEach { rail ->
            assertEquals(0xA7 / 255f, rail.red, ALPHA_TOLERANCE)
            assertEquals(0x9D / 255f, rail.green, ALPHA_TOLERANCE)
            assertEquals(0x91 / 255f, rail.blue, ALPHA_TOLERANCE)
        }
    }

    @Test
    fun `lime alpha rails carry the documented alphas`() {
        assertEquals(0.20f, LimeDim.alpha, ALPHA_TOLERANCE)
        assertEquals(0.55f, LimeSoft.alpha, ALPHA_TOLERANCE)
    }

    @Test
    fun `coral alpha rails carry the documented alphas`() {
        assertEquals(0.20f, CoralDim.alpha, ALPHA_TOLERANCE)
        assertEquals(0.55f, CoralSoft.alpha, ALPHA_TOLERANCE)
    }

    @Test
    fun `teal alpha rail carries the documented alpha`() {
        assertEquals(0.20f, TealDim.alpha, ALPHA_TOLERANCE)
    }

    @Test
    fun `accents are distinct`() {
        val accents = listOf(Lime, Coral, Teal, Ember)
        assertEquals(accents.size, accents.toSet().size)
    }

    @Test
    fun `radii match spec`() {
        assertEquals(9999.dp, RadiusTokens.RPill)
        assertEquals(18.dp, RadiusTokens.RXL)
        assertEquals(12.dp, RadiusTokens.RL)
        assertEquals(8.dp, RadiusTokens.RM)
        assertEquals(4.dp, RadiusTokens.RS)
        assertEquals(2.dp, RadiusTokens.RXS)
    }

    @Test
    fun `font families assigned`() {
        // Display + Body alias the same system family until real .ttf bundling lands in Phase 5.
        // The split is preserved on the API surface so the swap is a one-line change.
        assertEquals(FontFamily.SansSerif, VestigeFonts.Body)
        assertEquals(FontFamily.SansSerif, VestigeFonts.Display)
        assertEquals(FontFamily.Monospace, VestigeFonts.Mono)
        assertNotEquals(VestigeFonts.Body, VestigeFonts.Mono)
    }

    @Test
    fun `DisplayBig is condensed display at 56sp`() {
        val style = VestigeTextStyles.DisplayBig
        assertEquals(VestigeFonts.Display, style.fontFamily)
        assertEquals(56f, style.fontSize.value)
    }

    @Test
    fun `H1 is body 26sp`() {
        val style = VestigeTextStyles.H1
        assertEquals(VestigeFonts.Body, style.fontFamily)
        assertEquals(26f, style.fontSize.value)
    }

    @Test
    fun `H2 is body 22sp`() {
        val style = VestigeTextStyles.H2
        assertEquals(VestigeFonts.Body, style.fontFamily)
        assertEquals(22f, style.fontSize.value)
    }

    @Test
    fun `P is body 15sp`() {
        val style = VestigeTextStyles.P
        assertEquals(VestigeFonts.Body, style.fontFamily)
        assertEquals(15f, style.fontSize.value)
    }

    @Test
    fun `PersonaLabel is mono 10sp with 0_20em tracking`() {
        val style = VestigeTextStyles.PersonaLabel
        assertEquals(VestigeFonts.Mono, style.fontFamily)
        assertEquals(10f, style.fontSize.value)
        assertEquals(0.20f, style.letterSpacing.value, 0.0001f)
    }

    @Test
    fun `Eyebrow is mono 10sp with 0_18em tracking`() {
        val style = VestigeTextStyles.Eyebrow
        assertEquals(VestigeFonts.Mono, style.fontFamily)
        assertEquals(10f, style.fontSize.value)
        assertEquals(0.18f, style.letterSpacing.value, 0.0001f)
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
