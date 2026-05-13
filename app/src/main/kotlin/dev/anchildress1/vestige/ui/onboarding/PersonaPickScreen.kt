package dev.anchildress1.vestige.ui.onboarding

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.anchildress1.vestige.R
import dev.anchildress1.vestige.model.Persona
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
private fun PersonaCard(
    option: PersonaOption,
    isSelected: Boolean,
    onSelect: () -> Unit,
) {
    val colors = VestigeTheme.colors
    val shape = VestigeTheme.shapes.l
    val accent = if (isSelected) colors.lime else colors.hair
    val containerColor = if (isSelected) colors.s2 else colors.s1
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .semantics {
                role = Role.RadioButton
                selected = isSelected
            }
            .clickable(role = Role.RadioButton, onClick = onSelect),
        color = containerColor,
        contentColor = colors.ink,
        shape = shape,
        border = BorderStroke(if (isSelected) 2.dp else 1.dp, accent),
    ) {
        Column(
            modifier = Modifier.padding(PaddingValues(horizontal = 16.dp, vertical = 14.dp)),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = stringResource(id = option.nameRes),
                style = VestigeTheme.typography.title,
            )
            Text(
                text = stringResource(id = option.descRes),
                style = VestigeTheme.typography.p,
                color = colors.dim,
            )
        }
    }
}

private data class PersonaOption(
    val persona: Persona,
    val nameRes: Int,
    val descRes: Int,
)

private fun personaOptions(): List<PersonaOption> = listOf(
    PersonaOption(Persona.WITNESS, R.string.onboarding_persona_witness_name, R.string.onboarding_persona_witness_desc),
    PersonaOption(Persona.HARDASS, R.string.onboarding_persona_hardass_name, R.string.onboarding_persona_hardass_desc),
    PersonaOption(Persona.EDITOR, R.string.onboarding_persona_editor_name, R.string.onboarding_persona_editor_desc),
)
