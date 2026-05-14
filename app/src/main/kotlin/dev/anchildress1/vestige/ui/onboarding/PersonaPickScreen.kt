package dev.anchildress1.vestige.ui.onboarding

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
    val selectedName = stringResource(id = personaNameRes(selected)).uppercase()
    OnboardingScaffold(
        step = OnboardingStep.PersonaPick,
        modifier = modifier,
        rightStatus = "SKIP NONE",
        primary = OnboardingAction(
            label = stringResource(id = R.string.onboarding_persona_continue, selectedName),
            onAction = onContinue,
        ),
        footerHelper = stringResource(id = R.string.onboarding_persona_footer).uppercase(),
    ) {
        EyebrowE(text = stringResource(id = R.string.onboarding_persona_eyebrow))
        OnboardingHeadline(text = stringResource(id = R.string.onboarding_persona_header))
        BodyParagraph(text = stringResource(id = R.string.onboarding_persona_subhead))
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
    VestigeListCard(
        modifier = Modifier.fillMaxWidth(),
        onClick = onSelect,
        // `selected != null` routes through `Modifier.selectable` — the Compose primitive for
        // radio cards. Merges descendant semantics, dispatches taps on the persona name Text
        // to the card's onClick, and announces "radio button, selected" to TalkBack.
        selected = isSelected,
        role = Role.RadioButton,
        accentModifier = if (isSelected) Modifier.limeLeftRuleForActive() else Modifier,
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = stringResource(id = option.nameRes).uppercase(),
                        style = VestigeTheme.typography.h1,
                    )
                    EyebrowE(
                        text = "  " + stringResource(id = option.tagRes),
                        modifier = Modifier.padding(start = 4.dp),
                    )
                }
                Text(
                    text = stringResource(id = option.descRes),
                    style = VestigeTheme.typography.p,
                    color = colors.dim,
                )
            }
            PersonaCheckIndicator(isSelected = isSelected)
        }
    }
}

@Composable
private fun PersonaCheckIndicator(isSelected: Boolean) {
    val colors = VestigeTheme.colors
    Box(
        modifier = Modifier
            .size(28.dp)
            .padding(2.dp),
        contentAlignment = Alignment.Center,
    ) {
        if (isSelected) {
            Text(text = "✓", style = VestigeTheme.typography.title, color = colors.lime)
        } else {
            Text(text = "□", style = VestigeTheme.typography.title, color = colors.dim)
        }
    }
}

private data class PersonaOption(val persona: Persona, val nameRes: Int, val tagRes: Int, val descRes: Int)

private fun personaOptions(): List<PersonaOption> = listOf(
    PersonaOption(
        Persona.WITNESS,
        R.string.onboarding_persona_witness_name,
        R.string.onboarding_persona_witness_tag,
        R.string.onboarding_persona_witness_desc,
    ),
    PersonaOption(
        Persona.HARDASS,
        R.string.onboarding_persona_hardass_name,
        R.string.onboarding_persona_hardass_tag,
        R.string.onboarding_persona_hardass_desc,
    ),
    PersonaOption(
        Persona.EDITOR,
        R.string.onboarding_persona_editor_name,
        R.string.onboarding_persona_editor_tag,
        R.string.onboarding_persona_editor_desc,
    ),
)

private fun personaNameRes(persona: Persona): Int = when (persona) {
    Persona.WITNESS -> R.string.onboarding_persona_witness_name
    Persona.HARDASS -> R.string.onboarding_persona_hardass_name
    Persona.EDITOR -> R.string.onboarding_persona_editor_name
}
