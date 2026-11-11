package dev.diyhud

import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.tensorflow.lite.Interpreter
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import kotlin.math.abs

/** Explicit test-only harness: this model is untrained and never drives the HUD. */
class ExportParityTest {
    @Test
    fun cpuOutputsMatchSourceFixture() {
        val assets = InstrumentationRegistry.getInstrumentation().context.assets
        val modelBytes = assets.open("experiment.tflite").use { it.readBytes() }
        val report = JSONObject(assets.open("report.json").bufferedReader().use { it.readText() })
        assertEquals("converted", report.getString("status"))
        val digest = MessageDigest.getInstance("SHA-256").digest(modelBytes)
            .joinToString("") { "%02x".format(it) }
        assertEquals(report.getString("tflite_sha256"), digest)
        fun buffer(bytes: ByteArray): ByteBuffer = ByteBuffer.allocateDirect(bytes.size)
            .order(ByteOrder.nativeOrder()).apply { put(bytes); rewind() }
        val model = buffer(modelBytes)
        val input = buffer(assets.open("input_nhwc.bin").use { it.readBytes() })
        val expected = ByteBuffer.wrap(assets.open("expected_output.bin").use { it.readBytes() })
            .order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
        // Disable optional delegates so this is a reproducible CPU baseline.
        val options = Interpreter.Options().setNumThreads(2).setUseXNNPACK(false)
        Interpreter(model, options).use { interpreter ->
            assertTrue(interpreter.getInputTensor(0).shape().contentEquals(intArrayOf(1, 320, 320, 3)))
            val shape = interpreter.getOutputTensor(0).shape()
            val declared = report.getJSONArray("output_shape")
            assertTrue(shape.contentEquals(IntArray(declared.length()) { declared.getInt(it) }))
            val output = ByteBuffer.allocateDirect(interpreter.getOutputTensor(0).numBytes())
                .order(ByteOrder.nativeOrder())
            interpreter.run(input, output)
            output.rewind()
            val actual = output.asFloatBuffer()
            assertEquals(expected.limit(), actual.limit())
            var maximumError = 0f
            for (index in 0 until expected.limit()) {
                val error = abs(expected[index] - actual[index])
                maximumError = maxOf(maximumError, error)
                assertTrue("Tensor error at $index: $error", error <= 0.001f + 0.0001f * abs(expected[index]))
            }
            val candidates = shape[2]
            val classes = report.getJSONObject("classes").length()
            // Decode YOLO BCN: four cx/cy/w/h channels followed by class scores.
            for (candidate in 0 until candidates) {
                fun topClass(values: java.nio.FloatBuffer): Int = (0 until classes)
                    .maxBy { values[(it + 4) * candidates + candidate] }
                assertEquals(topClass(expected), topClass(actual))
                for (coordinate in 0..3) {
                    val offset = coordinate * candidates + candidate
                    assertTrue(abs(expected[offset] - actual[offset]) <= 0.01f)
                }
            }
            println("CPU export parity passed; maximum absolute error=$maximumError")
        }
    }
}
