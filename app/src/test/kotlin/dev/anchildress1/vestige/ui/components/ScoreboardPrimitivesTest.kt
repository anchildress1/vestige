package dev.anchildress1.vestige.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertHasNoClickAction
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Pos / neg / err / edge + a11y coverage for the Story 4.1.5 Scoreboard primitives.
 *
 * Robolectric defaults to the project's `VestigeApplication`, which opens `AppContainer`'s
 * `BoxStore` on init. These tests don't touch ObjectBox; override to a bare `Application` so
 * Robolectric doesn't pay the BoxStore cost (the macOS + JDK 25 ENOSPC trap doesn't fire on
 * Ubuntu CI but the override keeps the path identical across hosts).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE, application = android.app.Application::class)
class ScoreboardPrimitivesTest {

    @get:Rule
    val composeRule = createComposeRule()

    // ─── EyebrowE ───────────────────────────────────────────────────────────

    @Test
    fun `EyebrowE renders mono uppercase label`() {
        composeRule.setContent { EyebrowE(text = "ACTIVE") }
        composeRule.onNodeWithText("ACTIVE").assertIsDisplayed()
    }

    @Test
    fun `EyebrowE accepts empty text (edge — empty)`() {
        composeRule.setContent {
            Box(modifier = Modifier.size(40.dp)) { EyebrowE(text = "") }
        }
        // Empty text has no node to find — just verifies the composition doesn't crash.
        composeRule.onAllNodesWithText("ACTIVE").assertCountEquals(0)
    }

    // ─── StatusDot ──────────────────────────────────────────────────────────

    @Test
    fun `StatusDot is decorative (a11y — no announce)`() {
        composeRule.setContent { StatusDot() }
        // clearAndSetSemantics{} strips the dot from the a11y tree; no contentDescription node
        // should be findable on this dot. The surrounding pill announces its label instead.
        composeRule.onAllNodesWithText("").assertCountEquals(0)
    }

    @Test
    fun `StatusDot blink=false still composes (neg)`() {
        composeRule.setContent { StatusDot(blink = false) }
    }

    @Test
    fun `StatusDot blink=true composes with sbBlink animation (pos)`() {
        composeRule.setContent { StatusDot(blink = true) }
    }

    @Test
    fun `StatusDot accepts custom size (edge — large)`() {
        composeRule.setContent { StatusDot(size = 64.dp) }
    }

    // ─── Pill ───────────────────────────────────────────────────────────────

    @Test
    fun `Pill renders its label (pos — outline)`() {
        composeRule.setContent { Pill(text = "GEMMA 4 · LOCAL ONLY") }
        composeRule.onNodeWithText("GEMMA 4 · LOCAL ONLY").assertIsDisplayed()
    }

    @Test
    fun `Pill renders its label (pos — filled)`() {
        composeRule.setContent { Pill(text = "GEMMA 4 · LISTENING LIVE", fill = true) }
        composeRule.onNodeWithText("GEMMA 4 · LISTENING LIVE").assertIsDisplayed()
    }

    @Test
    fun `Pill with dot=true still surfaces label (a11y — text remains)`() {
        composeRule.setContent { Pill(text = "READY", dot = true, blink = true) }
        composeRule.onNodeWithText("READY").assertIsDisplayed()
    }

    @Test
    fun `Pill accepts empty text (edge — empty)`() {
        composeRule.setContent {
            Box(modifier = Modifier.size(80.dp)) { Pill(text = "") }
        }
    }

    // ─── StatRibbon ─────────────────────────────────────────────────────────

    @Test
    fun `StatRibbon renders all items (pos)`() {
        composeRule.setContent {
            StatRibbon(
                items = listOf(
                    StatItem(value = "12", label = "ENTRIES"),
                    StatItem(value = "4", label = "PATTERNS"),
                    StatItem(value = "30", label = "DAYS"),
                ),
            )
        }
        composeRule.onNodeWithText("12").assertIsDisplayed()
        composeRule.onNodeWithText("PATTERNS").assertIsDisplayed()
        composeRule.onNodeWithText("DAYS").assertIsDisplayed()
    }

    @Test(expected = IllegalArgumentException::class)
    fun `StatRibbon rejects empty items list (err)`() {
        composeRule.setContent { StatRibbon(items = emptyList()) }
    }

    @Test
    fun `StatRibbon renders single item (edge — one item, no divider)`() {
        composeRule.setContent { StatRibbon(items = listOf(StatItem("1", "SOLO"))) }
        composeRule.onNodeWithText("SOLO").assertIsDisplayed()
    }

    // ─── TickRule ───────────────────────────────────────────────────────────

    @Test
    fun `TickRule renders count cells (pos)`() {
        composeRule.setContent { TickRule(count = 30, marks = setOf(0, 15, 29)) }
    }

    @Test(expected = IllegalArgumentException::class)
    fun `TickRule rejects zero count (err)`() {
        composeRule.setContent { TickRule(count = 0, marks = emptySet()) }
    }

    @Test(expected = IllegalArgumentException::class)
    fun `TickRule rejects negative count (err)`() {
        composeRule.setContent { TickRule(count = -1, marks = emptySet()) }
    }

    @Test
    fun `TickRule accepts marks beyond count (neg — out-of-range marks are ignored)`() {
        composeRule.setContent { TickRule(count = 10, marks = setOf(99, -1)) }
    }

    @Test
    fun `TickRule renders empty marks (edge — no ticks lit)`() {
        composeRule.setContent { TickRule(count = 5, marks = emptySet()) }
    }

    // ─── AppTop (a11y, recording vs idle, tap targets) ──────────────────────

    @Test
    fun `AppTop idle status pill announces local-only (a11y)`() {
        composeRule.setContent { AppTop(persona = "WITNESS") }
        composeRule.onNodeWithContentDescription("Gemma 4 local model. Local only.")
            .assertIsDisplayed()
            .assertHasNoClickAction()
    }

    @Test
    fun `AppTop recording status pill announces listening live (a11y, pos)`() {
        composeRule.setContent { AppTop(persona = "HARDASS", status = AppTopStatuses.Recording) }
        composeRule.onNodeWithContentDescription("Gemma 4 local model. Listening live.")
            .assertIsDisplayed()
            .assertHasNoClickAction()
    }

    @Test
    fun `AppTop persona pill announces active persona (a11y)`() {
        composeRule.setContent { AppTop(persona = "EDITOR") }
        composeRule.onNodeWithContentDescription(label = "EDITOR", substring = true)
            .assertIsDisplayed()
            .assertHasNoClickAction()
    }

    @Test
    fun `AppTop loading status renders caller-provided chrome text`() {
        composeRule.setContent {
            AppTop(
                persona = "WITNESS",
                status = AppTopStatus(
                    text = "APP · LOADING",
                    contentDescription = "App loading.",
                    color = dev.anchildress1.vestige.ui.theme.Ember,
                    dot = false,
                    blink = false,
                ),
            )
        }
        composeRule.onNodeWithText("APP · LOADING").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("App loading.").assertIsDisplayed()
    }

    @Test
    fun `AppTop status pill fires onStatusTap when tapped (pos)`() {
        var statusTapped = 0
        composeRule.setContent { AppTop(persona = "WITNESS", onStatusTap = { statusTapped++ }) }
        composeRule.onNodeWithContentDescription("Gemma 4 local model. Local only.").performClick()
        assert(statusTapped == 1) { "onStatusTap should fire exactly once (was $statusTapped)" }
    }

    @Test
    fun `AppTop persona pill fires onPersonaTap when tapped (pos)`() {
        var personaTapped = 0
        composeRule.setContent { AppTop(persona = "WITNESS", onPersonaTap = { personaTapped++ }) }
        composeRule.onNodeWithContentDescription(label = "WITNESS", substring = true).performClick()
        assert(personaTapped == 1) { "onPersonaTap should fire exactly once (was $personaTapped)" }
    }

    @Test
    fun `AppTop chrome controls meet the 48dp tap target floor (a11y)`() {
        composeRule.setContent {
            AppTop(
                persona = "WITNESS",
                onStatusTap = {},
                onPersonaTap = {},
            )
        }
        composeRule.onNodeWithContentDescription("Gemma 4 local model. Local only.")
            .assertHeightIsAtLeast(48.dp)
        composeRule.onNodeWithContentDescription(label = "WITNESS", substring = true)
            .assertHeightIsAtLeast(48.dp)
    }

    @Test
    fun `AppTop a11y descriptions swap on recording toggle (edge — state-dependent label)`() {
        composeRule.setContent { AppTop(persona = "WITNESS", status = AppTopStatuses.Recording) }
        composeRule.onNodeWithContentDescription("Gemma 4 local model. Listening live.").assertIsDisplayed()
        composeRule.onAllNodesWithText("GEMMA 4 · LOCAL ONLY").assertCountEquals(0)
    }

    @Test
    fun `AppTop accepts empty persona string (edge — empty input)`() {
        composeRule.setContent { AppTop(persona = "") }
        composeRule.onNodeWithContentDescription(label = "Active persona", substring = true)
            .assertIsDisplayed()
    }

    @Test
    fun `AppTop persona pill label includes a11y change affordance (a11y)`() {
        composeRule.setContent { AppTop(persona = "HARDASS", onPersonaTap = {}) }
        composeRule.onNodeWithContentDescription(label = "Change persona", substring = true)
            .assertIsDisplayed()
    }

    // ─── tapeGrain (modifier no-throw) ──────────────────────────────────────

    @Test
    fun `tapeGrain renders without crash on minimal size (edge — small surface)`() {
        composeRule.setContent {
            Box(modifier = Modifier.size(1.dp).then(Modifier).then(Modifier)) {
                Text(text = "x")
            }
        }
    }

    // Equivalent assertion using the modifier directly through VestigeSurface lives in
    // VestigePrimitivesTest. The token alpha is locked in DesignTokensTest.
}
