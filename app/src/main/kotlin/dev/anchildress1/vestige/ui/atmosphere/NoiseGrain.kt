package dev.anchildress1.vestige.ui.atmosphere

import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import kotlin.math.roundToInt
import kotlin.random.Random

/** Documented opacity band per poc/design-review.md §2.4. */
const val NOISE_GRAIN_MIN_OPACITY: Float = 0.05f
const val NOISE_GRAIN_MAX_OPACITY: Float = 0.18f

/** Tile size matches the §2.4 feTurbulence (180×180). */
internal const val NOISE_TILE_PX: Int = 180

internal const val NOISE_DEFAULT_SEED: Int = 0x1E57

private const val ALPHA_CHANNEL_RANGE: Int = 256
private const val ALPHA_CHANNEL_SHIFT: Int = 24

/**
 * Overlay noise-grain texture per poc/design-review.md §2.4 + §8.
 *
 * Bakes a deterministic 180×180 monochrome tile once via [remember] and stamps it across the
 * surface with [BlendMode.Overlay]. Opacity is clamped to the documented 0.05–0.18 band so
 * callers can't drift toward visible noise.
 */
fun Modifier.noiseGrain(
    opacity: Float = 0.10f,
    seed: Int = NOISE_DEFAULT_SEED,
): Modifier = composed {
    val clamped = clampGrainOpacity(opacity)
    val tile = remember(seed) { buildNoiseTile(seed) }
    drawBehind { drawNoiseOverlay(tile, clamped) }
}

internal fun clampGrainOpacity(raw: Float): Float =
    raw.coerceIn(NOISE_GRAIN_MIN_OPACITY, NOISE_GRAIN_MAX_OPACITY)

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

private fun DrawScope.drawNoiseOverlay(tile: ImageBitmap, opacity: Float) {
    val w = size.width.roundToInt()
    val h = size.height.roundToInt()
    if (w <= 0 || h <= 0) return
    drawImage(
        image = tile,
        srcOffset = IntOffset.Zero,
        srcSize = IntSize(NOISE_TILE_PX, NOISE_TILE_PX),
        dstOffset = IntOffset.Zero,
        dstSize = IntSize(w, h),
        alpha = opacity,
        colorFilter = ColorFilter.tint(Color.White, BlendMode.SrcIn),
        blendMode = BlendMode.Overlay,
    )
}
