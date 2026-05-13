package dev.anchildress1.vestige.ui.atmosphere

import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.ImageShader
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import kotlin.random.Random

/** Documented opacity band per poc/design-review.md §2.4. Outside this range the texture stops reading as grain. */
const val NOISE_GRAIN_MIN_OPACITY: Float = 0.05f
const val NOISE_GRAIN_MAX_OPACITY: Float = 0.18f

/** Tile size matches the §2.4 feTurbulence (180×180). */
internal const val NOISE_TILE_PX: Int = 180

internal const val NOISE_DEFAULT_SEED: Int = 0x1E57

private const val ALPHA_CHANNEL_RANGE: Int = 256
private const val ALPHA_CHANNEL_SHIFT: Int = 24

/** Overlay noise grain per poc/design-review.md §2.4. Opacity clamped to 0.05–0.18. */
fun Modifier.noiseGrain(opacity: Float = 0.10f, seed: Int = NOISE_DEFAULT_SEED): Modifier = drawWithCache {
    val clamped = clampGrainOpacity(opacity)
    val brush = sharedNoiseBrush(seed)
    onDrawBehind { drawNoiseOverlay(brush, clamped) }
}

internal fun clampGrainOpacity(raw: Float): Float = raw.coerceIn(NOISE_GRAIN_MIN_OPACITY, NOISE_GRAIN_MAX_OPACITY)

/** Deterministic alpha noise pixels packed as `0xAA000000`. Pure Kotlin so JVM tests can assert. */
internal fun noiseAlphaPixels(seed: Int): IntArray {
    val rng = Random(seed)
    return IntArray(NOISE_TILE_PX * NOISE_TILE_PX) {
        rng.nextInt(ALPHA_CHANNEL_RANGE) shl ALPHA_CHANNEL_SHIFT
    }
}

internal fun buildNoiseTile(seed: Int): ImageBitmap {
    val bitmap = android.graphics.Bitmap.createBitmap(
        NOISE_TILE_PX,
        NOISE_TILE_PX,
        android.graphics.Bitmap.Config.ALPHA_8,
    )
    bitmap.setPixels(noiseAlphaPixels(seed), 0, NOISE_TILE_PX, 0, 0, NOISE_TILE_PX, NOISE_TILE_PX)
    return bitmap.asImageBitmap()
}

internal fun sharedNoiseBrush(seed: Int): ShaderBrush = SharedNoiseBrushCache.brushFor(seed)

private fun DrawScope.drawNoiseOverlay(brush: Brush, opacity: Float) {
    if (size.width <= 0f || size.height <= 0f) return
    drawRect(brush = brush, alpha = opacity, blendMode = BlendMode.Overlay)
}

private object SharedNoiseBrushCache {
    private val brushes = mutableMapOf<Int, ShaderBrush>()

    fun brushFor(seed: Int): ShaderBrush = synchronized(this) {
        brushes.getOrPut(seed) {
            ShaderBrush(ImageShader(buildNoiseTile(seed), TileMode.Repeated, TileMode.Repeated))
        }
    }
}
