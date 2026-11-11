package dev.diyhud.inference

import dev.diyhud.domain.Box
import kotlin.math.round

/** Coordinates are upright source-image pixels; model padding is removed here. */
data class Letterbox(val sourceWidth: Int, val sourceHeight: Int, val width: Int, val height: Int) {
    init { require(listOf(sourceWidth, sourceHeight, width, height).all { it > 0 }) }
    private val scale = minOf(width.toDouble() / sourceWidth, height.toDouble() / sourceHeight)
    val resizedWidth = round(sourceWidth * scale).toInt().coerceAtLeast(1)
    val resizedHeight = round(sourceHeight * scale).toInt().coerceAtLeast(1)
    val left = (width - resizedWidth) / 2
    val top = (height - resizedHeight) / 2

    fun sourceBox(cx: Double, cy: Double, w: Double, h: Double): Box? {
        if (listOf(cx, cy, w, h).any { !it.isFinite() } || w <= 0 || h <= 0) return null
        val x1 = ((cx - w / 2 - left) / resizedWidth).coerceIn(0.0, 1.0)
        val y1 = ((cy - h / 2 - top) / resizedHeight).coerceIn(0.0, 1.0)
        val x2 = ((cx + w / 2 - left) / resizedWidth).coerceIn(0.0, 1.0)
        val y2 = ((cy + h / 2 - top) / resizedHeight).coerceIn(0.0, 1.0)
        return if (x1 < x2 && y1 < y2) Box(x1, y1, x2, y2) else null
    }

    fun preprocess(rgb: ByteArray, padding: Int): FloatArray {
        require(rgb.size == sourceWidth * sourceHeight * 3)
        val output = FloatArray(width * height * 3) { padding / 255f }
        for (y in 0 until resizedHeight) for (x in 0 until resizedWidth) {
            val sx = ((x + 0.5) * sourceWidth / resizedWidth).toInt().coerceAtMost(sourceWidth - 1)
            val sy = ((y + 0.5) * sourceHeight / resizedHeight).toInt().coerceAtMost(sourceHeight - 1)
            for (channel in 0..2) output[((y + top) * width + x + left) * 3 + channel] =
                (rgb[(sy * sourceWidth + sx) * 3 + channel].toInt() and 255) / 255f
        }
        return output
    }
}

data class UprightRgb(val pixels: ByteArray, val width: Int, val height: Int)

fun rotateRgb(rgb: ByteArray, width: Int, height: Int, degrees: Int): UprightRgb {
    require(rgb.size == width * height * 3 && width > 0 && height > 0)
    require(degrees in listOf(0, 90, 180, 270))
    val ow = if (degrees % 180 == 0) width else height
    val oh = if (degrees % 180 == 0) height else width
    val result = ByteArray(rgb.size)
    for (y in 0 until height) for (x in 0 until width) {
        val (dx, dy) = when (degrees) {
            90 -> height - 1 - y to x
            180 -> width - 1 - x to height - 1 - y
            270 -> y to width - 1 - x
            else -> x to y
        }
        for (c in 0..2) result[(dy * ow + dx) * 3 + c] = rgb[(y * width + x) * 3 + c]
    }
    return UprightRgb(result, ow, oh)
}

/** Maps normalized upright boxes to a center-cropped preview (not model input). */
fun previewBox(box: Box, sourceWidth: Int, sourceHeight: Int,
               previewWidth: Int, previewHeight: Int): List<Double> {
    require(listOf(sourceWidth, sourceHeight, previewWidth, previewHeight).all { it > 0 })
    val scale = maxOf(previewWidth.toDouble() / sourceWidth, previewHeight.toDouble() / sourceHeight)
    val dx = (previewWidth - sourceWidth * scale) / 2
    val dy = (previewHeight - sourceHeight * scale) / 2
    return listOf(box.left * sourceWidth * scale + dx, box.top * sourceHeight * scale + dy,
        box.right * sourceWidth * scale + dx, box.bottom * sourceHeight * scale + dy)
}
