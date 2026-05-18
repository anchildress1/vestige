package dev.anchildress1.vestige.ui.onboarding

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertHasNoClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.anchildress1.vestige.ui.theme.VestigeTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Unit-tier a11y for the Wiring table rows — the indicator dot is decorative, so each row's
 * state must ride in its merged contentDescription with the correct click-action presence /
 * absence (AGENTS.md band a11y gate).
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], manifest = Config.NONE, application = OnboardingTestApplication::class)
class WiringScreenTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private fun render(vararg switches: WiringSwitch) {
        composeRule.activity.setContent {
            VestigeTheme { WiringScreen(switches = switches.toList()) }
        }
    }

    @Test
    fun `granted nav row carries state in its description and is clickable`() {
        render(
            WiringSwitch(
                label = "PERSONA",
                title = "Witness",
                description = "Voice picked on the previous screen.",
                state = WiringSwitchState.Granted,
                onTap = {},
                role = Role.Button,
            ),
        )
        composeRule.onNodeWithContentDescription("PERSONA. Witness. On.")
            .assertIsDisplayed()
            .assertHasClickAction()
    }

    @Test
    fun `granted row with no tap exposes no click action (a11y, neg)`() {
        render(
            WiringSwitch(
                label = "MIC",
                title = "Tap to grant mic",
                description = "Records dumps.",
                state = WiringSwitchState.Granted,
                onTap = null,
                role = Role.Switch,
            ),
        )
        composeRule.onNodeWithContentDescription("MIC. Tap to grant mic. On.")
            .assertHasNoClickAction()
    }

    @Test
    fun `pending switch row is toggleable and shows its hint`() {
        render(
            WiringSwitch(
                label = "MIC",
                title = "Tap to grant mic",
                description = "Records dumps.",
                state = WiringSwitchState.Pending,
                pendingHint = "REQUIRED FOR VOICE · OPTIONAL OTHERWISE",
                onTap = {},
                role = Role.Switch,
            ),
        )
        composeRule.onNodeWithContentDescription("MIC. Tap to grant mic. Off.")
            .assert(isToggleable())
        composeRule.onNodeWithText("REQUIRED FOR VOICE · OPTIONAL OTHERWISE").assertIsDisplayed()
    }

    @Test
    fun `blocked row reports the blocked state word (edge)`() {
        render(
            WiringSwitch(
                label = "MIC",
                title = "Tap to grant mic",
                description = "Records dumps.",
                state = WiringSwitchState.Blocked,
                pendingHint = "DENIED · TAP AGAIN OR SETTINGS → PERMISSIONS",
                onTap = {},
                role = Role.Switch,
            ),
        )
        composeRule.onNodeWithContentDescription("MIC. Tap to grant mic. Blocked.")
            .assert(isToggleable())
    }
}
