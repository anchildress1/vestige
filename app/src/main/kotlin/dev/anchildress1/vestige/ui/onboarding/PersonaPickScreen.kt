package dev.anchildress1.vestige.ui.onboarding

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import dev.anchildress1.vestige.R
import dev.anchildress1.vestige.model.Persona
import dev.anchildress1.vestige.ui.components.EyebrowE
import dev.anchildress1.vestige.ui.components.StatusDot
import dev.anchildress1.vestige.ui.components.VestigeListCard
import dev.anchildress1.vestige.ui.components.VestigeListCardInteraction
import dev.anchildress1.vestige.ui.theme.VestigeTheme

@Composable
internal fun PersonaPickScreen(
    selected: Persona,
    onSelect: (Persona) -> Unit,
    onContinue: () -> Unit,
    modifier: Modifier = Modifier,
    enabledCount: Int = 2,
) {
    OnboardingScaffold(
        enabledCount = enabledCount,
        modifier = modifier,
        primary = OnboardingAction(
            label = stringResource(id = R.string.onboarding_persona_select),
            onAction = onContinue,
        ),
        footerHelper = stringResource(id = R.string.onboarding_persona_footer).uppercase(),
    ) {
        OnboardingHeadline(text = stringResource(id = R.string.onboarding_persona_header))
        BodyParagraph(text = stringResource(id = R.string.onboarding_persona_subhead), dim = true)
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            personaOptions().forEach { option ->
                PersonaCard(
                    option = option,
                    isSelected = option.persona == selected,
                    onSelect = { onSelect(option.persona) },
                )
            }
        }
    }
}

@Composable
private fun PersonaCard(option: PersonaOption, isSelected: Boolean, onSelect: () -> Unit) {
    val colors = VestigeTheme.colors
    val accent = if (isSelected) colors.lime else colors.dim
    val name = stringResource(id = option.nameRes).uppercase()
    VestigeListCard(
        modifier = Modifier.fillMaxWidth(),
        // Selectable routes through Modifier.selectable — merged semantics, RadioButton role,
        // selected-state announce. The lime border + wash come from the primitive's selected
        // branch, not a call-site accent override.
        interaction = VestigeListCardInteraction.Selectable(
            selected = isSelected,
            onClick = onSelect,
            role = Role.RadioButton,
        ),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    StatusDot(color = accent, filled = isSelected)
                    EyebrowE(text = name, color = accent)
                }
                if (isSelected) {
                    EyebrowE(text = stringResource(id = R.string.onboarding_persona_selected), color = colors.lime)
                }
            }
            Text(text = name, style = VestigeTheme.typography.h1)
            Text(
                text = stringResource(id = option.cardRes),
                style = VestigeTheme.typography.p,
                color = colors.dim,
            )
        }
    }
}

private data class PersonaOption(val persona: Persona, val nameRes: Int, val cardRes: Int)

private fun personaOptions(): List<PersonaOption> = listOf(
    PersonaOption(
        Persona.WITNESS,
        R.string.onboarding_persona_witness_name,
        R.string.onboarding_persona_witness_card,
    ),
    PersonaOption(
        Persona.HARDASS,
        R.string.onboarding_persona_hardass_name,
        R.string.onboarding_persona_hardass_card,
    ),
    PersonaOption(
        Persona.EDITOR,
        R.string.onboarding_persona_editor_name,
        R.string.onboarding_persona_editor_card,
    ),
)
