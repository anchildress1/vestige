package dev.anchildress1.vestige.ui.components

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import dev.anchildress1.vestige.ui.theme.VestigeTheme

/**
 * App shell scaffold. Top-level screens own the floor/ink pairing here so shared surface tokens
 * stay readable even when a screen renders plain content outside `VestigeSurface`.
 */
@Composable
fun VestigeScaffold(
    modifier: Modifier = Modifier,
    topBar: @Composable () -> Unit = {},
    snackbarHost: @Composable () -> Unit = {},
    content: @Composable (PaddingValues) -> Unit,
) {
    Scaffold(
        modifier = modifier,
        containerColor = VestigeTheme.colors.floor,
        contentColor = VestigeTheme.colors.ink,
        topBar = topBar,
        snackbarHost = snackbarHost,
        content = content,
    )
}
