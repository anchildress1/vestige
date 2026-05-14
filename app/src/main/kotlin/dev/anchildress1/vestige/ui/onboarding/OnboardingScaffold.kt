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
import androidx.compose.ui.graphics.compositeOver
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
    primary: OnboardingAction,
    modifier: Modifier = Modifier,
    rightStatus: String? = null,
    secondary: OnboardingAction? = null,
    footerHelper: String? = null,
    content: @Composable () -> Unit = {},
) {
    Column(modifier = modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 24.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            OnboardingChrome(enabledCount = enabledCount, rightStatus = rightStatus)
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
private fun OnboardingPrimaryBar(primary: OnboardingAction, secondary: OnboardingAction?, footerHelper: String?) {
    val colors = VestigeTheme.colors
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Button(
            onClick = primary.onAction,
            enabled = primary.enabled,
            modifier = Modifier
                .fillMaxWidth()
                .semantics { role = Role.Button },
            contentPadding = PaddingValues(vertical = 18.dp, horizontal = 20.dp),
            colors = ButtonDefaults.buttonColors(
                // Calibrated lime — pure `colors.lime` reads as screaming neon on device,
                // `limeSoft` (55% alpha) reads as dark olive. 85% alpha composited over the
                // floor sits between: clearly lime, clearly bright, no retina burn.
                containerColor = colors.lime.copy(alpha = LIME_BAR_ALPHA).compositeOver(colors.floor),
                contentColor = colors.deep,
                disabledContainerColor = colors.s2,
                disabledContentColor = colors.dim,
            ),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(text = primary.label.uppercase(), style = VestigeTheme.typography.title)
                Text(text = "→", style = VestigeTheme.typography.title)
            }
        }
        if (secondary != null) {
            OutlinedButton(
                onClick = secondary.onAction,
                enabled = secondary.enabled,
                modifier = Modifier
                    .fillMaxWidth()
                    .semantics { role = Role.Button },
                contentPadding = PaddingValues(vertical = 14.dp),
            ) {
                Text(text = secondary.label)
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

// Tuned by hand on device. Pure lime (1.0) burns; limeSoft (0.55) looks olive; 0.85 reads
// flat; 0.92 keeps most of the lime's intensity while still trimming the neon edge.
private const val LIME_BAR_ALPHA: Float = 0.92f
