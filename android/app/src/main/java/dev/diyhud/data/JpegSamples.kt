package dev.diyhud.data

import android.graphics.Bitmap
import dev.diyhud.inference.rotateRgb
import java.io.ByteArrayOutputStream
import java.io.OutputStream

/** The output stream enforces the remaining budget while Bitmap compresses. */
fun encodeSample(frame: SampleFrame, maximumBytes: Int): ByteArray {
    val upright = rotateRgb(frame.rgb, frame.width, frame.height, frame.rotation)
    val pixels = IntArray(upright.width * upright.height) { index ->
        val offset = index * 3
        (255 shl 24) or ((upright.pixels[offset].toInt() and 255) shl 16) or
            ((upright.pixels[offset + 1].toInt() and 255) shl 8) or (upright.pixels[offset + 2].toInt() and 255)
    }
    val bitmap = Bitmap.createBitmap(pixels, upright.width, upright.height, Bitmap.Config.ARGB_8888)
    val output = ByteArrayOutputStream(minOf(maximumBytes, 65536))
    var limited = false
    val bounded = object : OutputStream() {
        override fun write(value: Int) {
            if (output.size() >= maximumBytes) { limited = true; throw SampleByteLimit() }
            output.write(value)
        }
        override fun write(bytes: ByteArray, offset: Int, count: Int) {
            if (count > maximumBytes - output.size()) { limited = true; throw SampleByteLimit() }
            output.write(bytes, offset, count)
        }
    }
    try {
        val success = bitmap.compress(Bitmap.CompressFormat.JPEG, 85, bounded)
        if (limited) throw SampleByteLimit()
        check(success) { "JPEG encoding failed" }
        return output.toByteArray()
    } catch (error: Exception) {
        if (limited) throw SampleByteLimit()
        throw error
    } finally { bitmap.recycle(); output.close() }
}
