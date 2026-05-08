package dev.anchildress1.vestige

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import dev.anchildress1.vestige.ui.theme.VestigeTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            VestigeTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { padding ->
                    Text(
                        text = "Vestige",
                        modifier = Modifier.padding(padding),
                    )
                }
            }
        }
    }
}

@Preview
@Composable
private fun MainPreview() {
    VestigeTheme {
        Text("Vestige")
    }
}
