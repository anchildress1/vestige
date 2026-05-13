package dev.anchildress1.vestige.ui.components

import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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
            VestigeSurface(modifier = Modifier.size(120.dp).glowLeftRule()) {
                Text(text = "glow-rule")
            }
        }
        composeRule.onNodeWithText("glow-rule").assertIsDisplayed()
    }

    @Test
    fun `vapor halo accepts zero level without drawing`() {
        composeRule.setContent {
            VestigeSurface(
                modifier = Modifier.size(120.dp).vaporHaloOnRecording(level = 0f),
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
                modifier = Modifier.size(120.dp).vaporHaloOnRecording(level = 0.8f),
            ) {
                Text(text = "halo-active")
            }
        }
        composeRule.onNodeWithText("halo-active").assertIsDisplayed()
    }

    @Test
    fun `pulse dot renders`() {
        composeRule.setContent {
            VestigeSurface(modifier = Modifier.pulseDotForReady()) {}
        }
        // Nothing to assert by text — the test ensures the modifier composes without
        // throwing through the drawBehind path.
        assertTrue(true)
    }

    @Test
    fun `error fill renders`() {
        composeRule.setContent {
            VestigeListCard(modifier = Modifier.size(120.dp).errorFillForDestructive()) {
                Text(text = "destructive")
            }
        }
        composeRule.onNodeWithText("destructive").assertIsDisplayed()
    }
}
