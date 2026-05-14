package dev.anchildress1.vestige.ui.onboarding

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.anchildress1.vestige.R
import dev.anchildress1.vestige.model.Persona
import dev.anchildress1.vestige.ui.components.VestigeListCard
import dev.anchildress1.vestige.ui.components.limeLeftRuleForActive
import dev.anchildress1.vestige.ui.theme.VestigeTheme

@Composable
internal fun PersonaPickScreen(
    selected: Persona,
    onSelect: (Persona) -> Unit,
    onContinue: () -> Unit,
    modifier: Modifier = Modifier,
) {
    OnboardingScaffold(
        modifier = modifier,
        header = stringResource(id = R.string.onboarding_persona_header),
        subhead = stringResource(id = R.string.onboarding_persona_subhead),
        primaryActionLabel = stringResource(id = R.string.onboarding_continue),
        onPrimary = onContinue,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            personaOptions().forEach { option ->
                PersonaCard(
                    option = option,
                    isSelected = option.persona == selected,
                    onSelect = { onSelect(option.persona) },
                )
            }
            OnboardingFooterLink(text = stringResource(id = R.string.onboarding_persona_footer))
        }
    }
}

@Composable
private fun PersonaCard(option: PersonaOption, isSelected: Boolean, onSelect: () -> Unit) {
    VestigeListCard(
        modifier = Modifier
            .fillMaxWidth()
            .semantics { selected = isSelected },
        onClick = onSelect,
        role = Role.RadioButton,
        accentModifier = if (isSelected) Modifier.limeLeftRuleForActive() else Modifier,
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(text = stringResource(id = option.nameRes), style = VestigeTheme.typography.title)
            Text(
                text = stringResource(id = option.descRes),
                style = VestigeTheme.typography.p,
                color = VestigeTheme.colors.dim,
            )
        }
    }
}

private data class PersonaOption(val persona: Persona, val nameRes: Int, val descRes: Int)

private fun personaOptions(): List<PersonaOption> = listOf(
    PersonaOption(Persona.WITNESS, R.string.onboarding_persona_witness_name, R.string.onboarding_persona_witness_desc),
    PersonaOption(Persona.HARDASS, R.string.onboarding_persona_hardass_name, R.string.onboarding_persona_hardass_desc),
    PersonaOption(Persona.EDITOR, R.string.onboarding_persona_editor_name, R.string.onboarding_persona_editor_desc),
)
