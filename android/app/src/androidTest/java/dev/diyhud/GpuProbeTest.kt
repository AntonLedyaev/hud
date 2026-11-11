package dev.diyhud

import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Test
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.gpu.CompatibilityList
import org.tensorflow.lite.gpu.GpuDelegate
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs

/** Probe only. Passing this test does not enable GPU in the application. */
class GpuProbeTest {
    @Test fun recordGpuCompatibilityAndParity() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val reports = mutableListOf<String>()
        var runtime: Interpreter? = null
        var delegate: GpuDelegate? = null
        try {
            CompatibilityList().use { compatibility ->
                if (!compatibility.isDelegateSupportedOnThisDevice) {
                    reports += "GPU unsupported by delegate compatibility check; CPU retained"
                    return@use
                }
                val assets = instrumentation.targetContext.assets
                fun buffer(bytes: ByteArray): ByteBuffer = ByteBuffer.allocateDirect(bytes.size)
                    .order(ByteOrder.nativeOrder()).apply { put(bytes); rewind() }
                val model = buffer(assets.open("model/model.tflite").use { it.readBytes() })
                delegate = GpuDelegate(GpuDelegate.Options().setPrecisionLossAllowed(false))
                runtime = Interpreter(model, Interpreter.Options().addDelegate(requireNotNull(delegate)))
                val engine = requireNotNull(runtime)
                engine.allocateTensors()
                for (name in listOf("00605", "00611", "00613")) {
                    val fixtures = instrumentation.context.assets
                    val input = buffer(fixtures.open("trained/$name.input").use { it.readBytes() })
                    val expected = ByteBuffer.wrap(fixtures.open("trained/$name.expected").use { it.readBytes() })
                        .order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
                    val output = ByteBuffer.allocateDirect(engine.getOutputTensor(0).numBytes())
                        .order(ByteOrder.nativeOrder())
                    val start = System.nanoTime()
                    engine.run(input, output)
                    val elapsedMs = (System.nanoTime() - start) / 1e6
                    output.rewind()
                    val actual = output.asFloatBuffer()
                    var passed = true
                    var maximum = 0f
                    for (i in 0 until expected.limit()) {
                        val error = abs(actual[i] - expected[i])
                        maximum = maxOf(maximum, error)
                        if (!error.isFinite() || error > 0.01f + 0.0001f * abs(expected[i])) passed = false
                    }
                    reports += "$name: GPU raw parity=$passed, maxError=$maximum, ms=$elapsedMs"
                }
                reports += "Experimental probe only; CPU retained pending decoded/sustained GPU validation"
            }
        } catch (failure: Exception) {
            reports += "GPU unavailable/failed: ${failure.javaClass.simpleName}: ${failure.message}; CPU retained"
        } finally {
            runtime?.close()
            delegate?.close()
            instrumentation.targetContext.filesDir.resolve("gpu-probe.txt")
                .writeText(reports.joinToString("\n"))
            println(reports.joinToString("\n"))
        }
    }
}
