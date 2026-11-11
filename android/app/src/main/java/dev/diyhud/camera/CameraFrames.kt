package dev.diyhud.camera

import androidx.camera.core.ImageProxy
import dev.diyhud.session.FrameToken
import java.nio.ByteBuffer

data class RgbFrame(val token: FrameToken, val id: Long, val captureMs: Long,
                    val rgb: ByteArray, val width: Int, val height: Int,
                    val rotation: Int) : AutoCloseable {
    override fun close() = Unit // Owns copied heap bytes, never a live ImageProxy.
}

/** CameraX API specifies R,G,B,A byte order; row/pixel strides and ROI are explicit. */
fun copyRgba(buffer: ByteBuffer, rowStride: Int, pixelStride: Int,
             left: Int, top: Int, width: Int, height: Int): ByteArray {
    require(pixelStride >= 4 && rowStride > 0 && width > 0 && height > 0)
    val base = buffer.position()
    val output = ByteArray(width * height * 3)
    for (y in 0 until height) for (x in 0 until width) {
        val source = base + (y + top) * rowStride + (x + left) * pixelStride
        for (channel in 0..2) output[(y * width + x) * 3 + channel] = buffer.get(source + channel)
    }
    return output
}

fun copyFrame(image: ImageProxy, token: FrameToken, id: Long, captureMs: Long): RgbFrame {
    val crop = image.cropRect
    val plane = image.planes[0]
    return RgbFrame(token, id, captureMs, copyRgba(plane.buffer, plane.rowStride,
        plane.pixelStride, crop.left, crop.top, crop.width(), crop.height()),
        crop.width(), crop.height(), image.imageInfo.rotationDegrees)
}
