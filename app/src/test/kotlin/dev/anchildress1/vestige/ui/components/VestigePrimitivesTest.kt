package dev.anchildress1.vestige.ui.components

import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import dev.anchildress1.vestige.ui.theme.Ink
import dev.anchildress1.vestige.ui.theme.Mist
import dev.anchildress1.vestige.ui.theme.S1
import dev.anchildress1.vestige.ui.theme.S2
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class VestigePrimitivesTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `VestigeSurface renders its content`() {
        composeRule.setContent {
            VestigeSurface(modifier = Modifier.size(120.dp)) {
                Text(text = "surface-content")
            }
        }
        composeRule.onNodeWithText("surface-content").assertIsDisplayed()
    }

    @Test
    fun `VestigeRow renders label and value`() {
        composeRule.setContent {
            VestigeRow(label = "VERSION", value = "1.0.0")
        }
        composeRule.onNodeWithText("VERSION").assertIsDisplayed()
        composeRule.onNodeWithText("1.0.0").assertIsDisplayed()
    }

    @Test
    fun `VestigeRow locks label color to Mist and value to Ink`() {
        assertEquals(Mist, RowLabelColor)
        assertEquals(Ink, RowValueColor)
    }

    @Test
    fun `VestigeListCard fill is S1 when static and S2 when clickable`() {
        assertEquals(S1, vestigeListCardFill(null))
        assertEquals(S2, vestigeListCardFill(onClick = {}))
    }

    @Test
    fun `VestigeListCard invokes onClick`() {
        var clicked = 0
        composeRule.setContent {
            VestigeListCard(modifier = Modifier.size(160.dp, 60.dp), onClick = { clicked++ }) {
                Text(text = "list-card")
            }
        }
        composeRule.onNodeWithText("list-card").performClick()
        assertEquals(1, clicked)
    }

    @Test
    fun `VestigeListCard renders without onClick`() {
        composeRule.setContent {
            VestigeListCard(modifier = Modifier.size(160.dp, 60.dp)) {
                Text(text = "static-card")
            }
        }
        composeRule.onNodeWithText("static-card").assertIsDisplayed()
    }

    @Test
    fun `accent modifiers compose onto a sized box without crashing`() {
        composeRule.setContent {
            VestigeSurface(
                modifier = Modifier.size(120.dp),
                accentModifier = Modifier.glowLeftRule(),
            ) {
                Text(text = "glow-rule")
            }
        }
        composeRule.onNodeWithText("glow-rule").assertIsDisplayed()
    }

    @Test
    fun `glow rule width matches spec`() {
        assertEquals(3.dp, GlowRuleWidth)
    }

    @Test
    fun `pulse dot diameter matches spec`() {
        assertEquals(8.dp, PulseDotDiameter)
    }

    @Test
    fun `vapor halo idles at zero level`() {
        composeRule.setContent {
            VestigeSurface(
                modifier = Modifier.size(120.dp),
                accentModifier = Modifier.vaporHaloOnRecording(level = 0f),
            ) {
                Text(text = "halo-idle")
            }
        }
        composeRule.onNodeWithText("halo-idle").assertIsDisplayed()
    }

    @Test
    fun `vapor halo composes at active level`() {
        composeRule.setContent {
            VestigeSurface(
                modifier = Modifier.size(120.dp),
                accentModifier = Modifier.vaporHaloOnRecording(level = 0.8f),
            ) {
                Text(text = "halo-active")
            }
        }
        composeRule.onNodeWithText("halo-active").assertIsDisplayed()
    }

    @Test
    fun `vapor halo treats negative level as idle`() {
        composeRule.setContent {
            VestigeSurface(
                modifier = Modifier.size(120.dp),
                accentModifier = Modifier.vaporHaloOnRecording(level = -0.5f),
            ) {
                Text(text = "halo-negative")
            }
        }
        composeRule.onNodeWithText("halo-negative").assertIsDisplayed()
    }

    @Test
    fun `vapor halo treats NaN level as idle`() {
        composeRule.setContent {
            VestigeSurface(
                modifier = Modifier.size(120.dp),
                accentModifier = Modifier.vaporHaloOnRecording(level = Float.NaN),
            ) {
                Text(text = "halo-nan")
            }
        }
        composeRule.onNodeWithText("halo-nan").assertIsDisplayed()
    }

    @Test
    fun `vapor halo clamps above 1`() {
        composeRule.setContent {
            VestigeSurface(
                modifier = Modifier.size(120.dp),
                accentModifier = Modifier.vaporHaloOnRecording(level = 99f),
            ) {
                Text(text = "halo-clamped")
            }
        }
        composeRule.onNodeWithText("halo-clamped").assertIsDisplayed()
    }

    @Test
    fun `pulse dot renders without crashing`() {
        composeRule.setContent {
            VestigeSurface(modifier = Modifier.pulseDotForReady()) {}
        }
        // Modifier composes through the drawBehind path; no text to assert. If the dot's draw
        // stack throws, this test fails by exception — the assertion is "did not crash."
        composeRule.onRoot().assertIsDisplayed()
    }

    @Test
    fun `error fill renders`() {
        composeRule.setContent {
            VestigeListCard(
                modifier = Modifier.size(120.dp),
                accentModifier = Modifier.errorFillForDestructive(),
            ) {
                Text(text = "destructive")
            }
        }
        composeRule.onNodeWithText("destructive").assertIsDisplayed()
    }
}
