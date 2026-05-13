package dev.anchildress1.vestige.ui.components

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection

/**
 * Runs [block] against a real [DrawScope] backed by an `ImageBitmap` of [width] × [height] px.
 *
 * The backing bitmap is returned so tests can inspect pixels. Density 1f keeps `Dp.toPx()`
 * trivially equal to the raw value, so callers can reason about "3.dp on the leading edge".
 */
internal fun renderInBitmap(width: Int, height: Int, block: DrawScope.() -> Unit): ImageBitmap {
    val bitmap = ImageBitmap(width, height)
    val canvas = Canvas(bitmap)
    CanvasDrawScope().draw(
        density = Density(1f),
        layoutDirection = LayoutDirection.Ltr,
        canvas = canvas,
        size = Size(width.toFloat(), height.toFloat()),
        block = block,
    )
    return bitmap
}

internal fun ImageBitmap.alphaAt(x: Int, y: Int): Float = toPixelMap()[x, y].alpha
