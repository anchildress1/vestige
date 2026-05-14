package dev.anchildress1.vestige.ui.capture

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetState
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.anchildress1.vestige.ui.components.EyebrowE
import dev.anchildress1.vestige.ui.theme.VestigeTheme

/**
 * Modal bottom sheet for the typed-entry fallback. Caller supplies the submit callback so the
 * ViewModel stays out of this composable — the sheet is a pure renderer over locally-held text
 * state. Closes itself on submit or swipe-dismiss; the caller flips visibility.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TypeEntrySheet(
    onDismiss: () -> Unit,
    onSubmit: (String) -> Unit,
    sheetState: SheetState = rememberModalBottomSheetState(),
) {
    var text by rememberSaveable { mutableStateOf("") }
    val colors = VestigeTheme.colors
    val canSubmit = text.trim().length >= MIN_LENGTH

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = colors.s1,
        contentColor = colors.ink,
        scrimColor = colors.deep.copy(alpha = SCRIM_ALPHA),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 18.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            EyebrowE(text = "TYPED ENTRY")
            BasicTextField(
                value = text,
                onValueChange = { text = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(TEXT_FIELD_HEIGHT)
                    .background(colors.s2)
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                textStyle = VestigeTheme.typography.p.copy(color = colors.ink),
                cursorBrush = SolidColor(colors.lime),
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.Sentences,
                    imeAction = ImeAction.Default,
                ),
                decorationBox = { inner ->
                    Box(modifier = Modifier.fillMaxWidth()) {
                        if (text.isEmpty()) {
                            Text(
                                text = CaptureCopy.TYPE_PLACEHOLDER,
                                style = VestigeTheme.typography.p,
                                color = colors.faint,
                            )
                        }
                        inner()
                    }
                },
            )
            SubmitRow(
                canSubmit = canSubmit,
                onSubmit = {
                    onSubmit(text.trim())
                    text = ""
                },
            )
        }
    }
}

@Composable
private fun SubmitRow(canSubmit: Boolean, onSubmit: () -> Unit) {
    val colors = VestigeTheme.colors
    val bg = if (canSubmit) colors.ink else colors.s3
    val fg = if (canSubmit) colors.deep else colors.faint
    val cd = if (canSubmit) "${CaptureCopy.TYPE_SUBMIT}, enabled" else "${CaptureCopy.TYPE_SUBMIT}, disabled"
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(bg)
            .clickable(enabled = canSubmit, onClick = onSubmit)
            .semantics(mergeDescendants = true) {
                role = Role.Button
                contentDescription = cd
            }
            .padding(SUBMIT_BUTTON_PADDING),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = CaptureCopy.TYPE_SUBMIT.uppercase(),
            style = VestigeTheme.typography.displayBig.copy(fontSize = 18.sp, lineHeight = 18.sp),
            color = fg,
        )
    }
}

private val TEXT_FIELD_HEIGHT = 160.dp
private val SUBMIT_BUTTON_PADDING = PaddingValues(vertical = 14.dp)
private const val MIN_LENGTH: Int = 3
private const val SCRIM_ALPHA: Float = 0.6f
