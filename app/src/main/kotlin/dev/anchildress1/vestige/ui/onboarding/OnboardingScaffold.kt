package dev.anchildress1.vestige.ui.onboarding

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.anchildress1.vestige.R
import dev.anchildress1.vestige.ui.components.EyebrowE
import dev.anchildress1.vestige.ui.theme.VestigeTheme

/** Shared scaffold for the 3-screen onboarding hub flow. */
@Suppress("LongParameterList") // Scaffold primitive — wide by design.
@Composable
internal fun OnboardingScaffold(
    enabledCount: Int,
    modifier: Modifier = Modifier,
    primary: OnboardingAction? = null,
    secondary: OnboardingAction? = null,
    footerHelper: String? = null,
    content: @Composable () -> Unit = {},
) {
    Column(modifier = modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                // The window-inset padding from VestigeScaffold already clears the camera
                // cutout — only a small top breath is added here, not a second full gap.
                .padding(PaddingValues(start = 24.dp, top = 8.dp, end = 24.dp, bottom = 24.dp)),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            OnboardingChrome(enabledCount = enabledCount)
            content()
            Spacer(modifier = Modifier.height(16.dp))
        }
        OnboardingPrimaryBar(
            primary = primary,
            secondary = secondary,
            footerHelper = footerHelper,
        )
    }
}

@Composable
private fun OnboardingPrimaryBar(primary: OnboardingAction?, secondary: OnboardingAction?, footerHelper: String?) {
    val colors = VestigeTheme.colors
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (primary != null) {
            Button(
                onClick = primary.onAction,
                enabled = primary.enabled,
                modifier = Modifier
                    .fillMaxWidth()
                    .semantics { role = Role.Button },
                shape = RectangleShape,
                contentPadding = PaddingValues(vertical = 18.dp, horizontal = 20.dp),
                // Cream by default; greens once the screen's gate is fully met (Wiring "all
                // rows green"). Dark text reads on both. Single source — no per-screen override.
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (primary.highlighted) colors.lime else colors.ink,
                    contentColor = colors.deep,
                    disabledContainerColor = colors.s2,
                    disabledContentColor = colors.dim,
                ),
            ) {
                // Label + arrow travel together, centered — the M3 Button centers its content,
                // so the inner row must wrap (not fillMaxWidth/SpaceBetween, which pinned the
                // label left and the arrow right).
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text(text = primary.label.uppercase(), style = VestigeTheme.typography.title)
                    Text(text = "→", style = VestigeTheme.typography.title)
                }
            }
        }
        if (secondary != null) {
            OutlinedButton(
                onClick = secondary.onAction,
                enabled = secondary.enabled,
                modifier = Modifier
                    .fillMaxWidth()
                    .semantics { role = Role.Button },
                shape = RectangleShape,
                contentPadding = PaddingValues(vertical = 16.dp),
            ) {
                // Outlined, centered, uppercase — the download-screen Pause / Retry register.
                Text(text = secondary.label.uppercase(), style = VestigeTheme.typography.title)
            }
        }
        if (footerHelper != null) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                EyebrowE(text = footerHelper)
            }
        }
    }
}

@Composable
internal fun OnboardingFooterLink(text: String, modifier: Modifier = Modifier) {
    Row(modifier = modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = text,
            style = VestigeTheme.typography.pCompact,
            color = VestigeTheme.colors.dim,
        )
    }
}

@Composable
internal fun onboardingDefaultBack(): String = stringResource(id = R.string.onboarding_back)
