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
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.anchildress1.vestige.R
import dev.anchildress1.vestige.ui.theme.VestigeTheme

/**
 * Shared scaffold for the 8 onboarding screens: scrollable column with header, optional
 * subhead, slot for body content, and 1–2 footer actions. Screens are intentionally not boxed
 * inside `VestigeSurface` — onboarding is full-bleed prose, not a card stack.
 */
@Suppress("LongParameterList") // Scaffold primitive — wide by design.
@Composable
internal fun OnboardingScaffold(
    header: String,
    primaryActionLabel: String,
    onPrimary: () -> Unit,
    modifier: Modifier = Modifier,
    subhead: String? = null,
    primaryEnabled: Boolean = true,
    secondaryActionLabel: String? = null,
    onSecondary: (() -> Unit)? = null,
    content: @Composable () -> Unit = {},
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 32.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(text = header, style = VestigeTheme.typography.h1)
        if (subhead != null) {
            Text(
                text = subhead,
                style = VestigeTheme.typography.p,
                color = VestigeTheme.colors.dim,
            )
        }
        content()
        Spacer(modifier = Modifier.height(8.dp))
        OnboardingActionRow(
            primaryLabel = primaryActionLabel,
            onPrimary = onPrimary,
            primaryEnabled = primaryEnabled,
            secondaryLabel = secondaryActionLabel,
            onSecondary = onSecondary,
        )
    }
}

@Composable
private fun OnboardingActionRow(
    primaryLabel: String,
    onPrimary: () -> Unit,
    primaryEnabled: Boolean,
    secondaryLabel: String?,
    onSecondary: (() -> Unit)?,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(
            onClick = onPrimary,
            enabled = primaryEnabled,
            modifier = Modifier
                .fillMaxWidth()
                .semantics { role = Role.Button },
            contentPadding = PaddingValues(vertical = 14.dp),
        ) {
            Text(text = primaryLabel)
        }
        if (secondaryLabel != null && onSecondary != null) {
            OutlinedButton(
                onClick = onSecondary,
                modifier = Modifier
                    .fillMaxWidth()
                    .semantics { role = Role.Button },
                contentPadding = PaddingValues(vertical = 14.dp),
            ) {
                Text(text = secondaryLabel)
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
