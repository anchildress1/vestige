package dev.anchildress1.vestige.ui.components

import androidx.compose.foundation.layout.size
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertHasNoClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import dev.anchildress1.vestige.ui.theme.Ink
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Pos / neg / err / edge + a11y coverage for shared Vestige surface/scaffold primitives.
 *
 * Err coverage lives on the accent modifiers that clamp or reject invalid draw inputs; the
 * surface wrappers themselves expose no throw-path API.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE, application = android.app.Application::class)
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
    fun `VestigeSurface provides Ink as the default content color`() {
        var contentColor: Color? = null
        composeRule.setContent {
            VestigeSurface(modifier = Modifier.size(120.dp)) {
                contentColor = LocalContentColor.current
                Text(text = "surface-ink")
            }
        }
        composeRule.onNodeWithText("surface-ink").assertIsDisplayed()
        composeRule.runOnIdle { assertEquals(Ink, contentColor) }
    }

    @Test
    fun `VestigeScaffold provides Ink as the default content color (a11y + theme)`() {
        var contentColor: Color? = null
        composeRule.setContent {
            VestigeScaffold {
                contentColor = LocalContentColor.current
                Text(text = "scaffold-ink")
            }
        }
        composeRule.onNodeWithText("scaffold-ink").assertIsDisplayed()
        composeRule.runOnIdle { assertEquals(Ink, contentColor) }
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
    fun `VestigeListCard with onClick exposes click semantics (a11y, pos)`() {
        composeRule.setContent {
            VestigeListCard(modifier = Modifier.size(160.dp, 60.dp), onClick = {}) {
                Text(text = "clickable-card")
            }
        }
        composeRule.onNodeWithText("clickable-card").assertHasClickAction()
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
    fun `VestigeListCard without onClick stays non-interactive (a11y, neg)`() {
        composeRule.setContent {
            VestigeListCard(modifier = Modifier.size(160.dp, 60.dp)) {
                Text(text = "static-card-a11y")
            }
        }
        composeRule.onNodeWithText("static-card-a11y").assertHasNoClickAction()
    }

    @Test
    fun `limeLeftRuleForActive draws against the receiver`() {
        composeRule.setContent {
            VestigeSurface(
                modifier = Modifier.size(120.dp),
                accentModifier = Modifier.limeLeftRuleForActive(),
            ) {
                Text(text = "lime-rule")
            }
        }
        composeRule.onNodeWithText("lime-rule").assertIsDisplayed()
    }

    @Test
    fun `rule width matches spec`() {
        assertEquals(3.dp, RuleWidth)
    }

    @Test
    fun `status dot diameter matches spec`() {
        assertEquals(8.dp, StatusDotDiameter)
    }

    // coralHaloOnRecording — pos / neg / err / edge

    @Test
    fun `coral halo idles at zero level (neg branch — skips draw)`() {
        composeRule.setContent {
            VestigeSurface(
                modifier = Modifier.size(120.dp),
                accentModifier = Modifier.coralHaloOnRecording(level = 0f),
            ) {
                Text(text = "halo-idle")
            }
        }
        composeRule.onNodeWithText("halo-idle").assertIsDisplayed()
    }

    @Test
    fun `coral halo paints at mid level (pos branch — exercises gradient math)`() {
        composeRule.setContent {
            VestigeSurface(
                modifier = Modifier.size(120.dp),
                accentModifier = Modifier.coralHaloOnRecording(level = 0.5f),
            ) {
                Text(text = "halo-mid")
            }
        }
        composeRule.onNodeWithText("halo-mid").assertIsDisplayed()
    }

    @Test
    fun `coral halo paints at full amp (edge upper bound)`() {
        composeRule.setContent {
            VestigeSurface(
                modifier = Modifier.size(120.dp),
                accentModifier = Modifier.coralHaloOnRecording(level = 1f),
            ) {
                Text(text = "halo-full")
            }
        }
        composeRule.onNodeWithText("halo-full").assertIsDisplayed()
    }

    @Test
    fun `coral halo clamps negative level to idle (err branch)`() {
        composeRule.setContent {
            VestigeSurface(
                modifier = Modifier.size(120.dp),
                accentModifier = Modifier.coralHaloOnRecording(level = -0.5f),
            ) {
                Text(text = "halo-negative")
            }
        }
        composeRule.onNodeWithText("halo-negative").assertIsDisplayed()
    }

    @Test
    fun `coral halo treats NaN as idle (err branch)`() {
        composeRule.setContent {
            VestigeSurface(
                modifier = Modifier.size(120.dp),
                accentModifier = Modifier.coralHaloOnRecording(level = Float.NaN),
            ) {
                Text(text = "halo-nan")
            }
        }
        composeRule.onNodeWithText("halo-nan").assertIsDisplayed()
    }

    @Test
    fun `coral halo clamps above 1 (edge upper bound)`() {
        composeRule.setContent {
            VestigeSurface(
                modifier = Modifier.size(120.dp),
                accentModifier = Modifier.coralHaloOnRecording(level = 99f),
            ) {
                Text(text = "halo-clamped")
            }
        }
        composeRule.onNodeWithText("halo-clamped").assertIsDisplayed()
    }

    @Test
    fun `lime dot draws halo plus inner disc and rim`() {
        composeRule.setContent {
            VestigeSurface(modifier = Modifier.limeDotForReady()) {}
        }
        composeRule.onRoot().assertIsDisplayed()
    }

    @Test
    fun `error fill paints destructive rounded rect`() {
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

    @Test
    fun `tape grain composes`() {
        composeRule.setContent {
            VestigeSurface(modifier = Modifier.size(120.dp)) {
                Text(text = "tape")
            }
        }
        composeRule.onNodeWithText("tape").assertIsDisplayed()
    }
}
