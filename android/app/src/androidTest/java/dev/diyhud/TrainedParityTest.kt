package dev.diyhud

import androidx.test.platform.app.InstrumentationRegistry
import dev.diyhud.inference.Letterbox
import dev.diyhud.inference.LiteRtDetector
import dev.diyhud.inference.ModelLoadException
import dev.diyhud.inference.ModelJson
import org.json.JSONObject
import dev.diyhud.inference.decodeYolo
import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class TrainedParityTest {
    @Test fun corruptAndMissingBundlesFailWithoutPoisoningValidReload() {
        val assets = InstrumentationRegistry.getInstrumentation().targetContext.assets
        fun read(name: String) = assets.open(name).use { it.readBytes() }
        assertThrows(ModelLoadException::class.java) {
            LiteRtDetector.open({ name -> if (name == "model/model.tflite") byteArrayOf(0) else read(name) })
        }
        assertThrows(ModelLoadException::class.java) {
            LiteRtDetector.open({ name -> if (name == "model/manifest.json") "{}".toByteArray() else read(name) })
        }
        val modified = JSONObject(read("model/manifest.json").toString(Charsets.UTF_8))
        modified.getJSONObject("output").getJSONObject("tensor").getJSONArray("shape").put(2, 10)
        val modifiedBytes = modified.toString().toByteArray()
        val profile = JSONObject(read("model/preprocessing-profile.json").toString(Charsets.UTF_8))
        profile.put("manifest_sha256", ModelJson.sha256(modifiedBytes))
        assertThrows(ModelLoadException::class.java) {
            LiteRtDetector.open({ name -> when (name) {
                "model/manifest.json" -> modifiedBytes
                "model/preprocessing-profile.json" -> profile.toString().toByteArray()
                else -> read(name)
            } })
        }
        LiteRtDetector.open(assets).use { assertEquals("CPU", it.backend) }
    }

    @Test fun trainedCpuMatchesRoadSceneFixtures() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val fixtures = instrumentation.context.assets
        val results = mutableListOf<String>()
        var decodedCount = 0
        LiteRtDetector.open(instrumentation.targetContext.assets).use { detector ->
            assertEquals("CPU", detector.backend)
            for (name in listOf("00605", "00611", "00613")) {
                fun floats(suffix: String): FloatArray {
                    val bytes = fixtures.open("trained/$name.$suffix").use { it.readBytes() }
                    val values = FloatArray(bytes.size / 4)
                    ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().get(values)
                    return values
                }
                val rgb = fixtures.open("trained/$name.rgb").use { it.readBytes() }
                val geometry = Letterbox(1360, 800, 640, 640)
                val preprocessed = geometry.preprocess(rgb, detector.contract.preprocessing.paddingValue)
                assertArrayEquals(floats("input"), preprocessed, 0.000001f)
                val expected = floats("expected")
                val before = System.nanoTime()
                val actual = detector.inferTensor(preprocessed)
                val elapsedMs = (System.nanoTime() - before) / 1e6
                var maxError = 0f
                for (i in expected.indices) {
                    val error = kotlin.math.abs(expected[i] - actual[i])
                    maxError = maxOf(maxError, error)
                    assertTrue("$name tensor mismatch at $i: $error",
                        error <= 0.01f + 0.0001f * kotlin.math.abs(expected[i]))
                }
                val reference = decodeYolo(expected, detector.contract, geometry)
                val detections = decodeYolo(actual, detector.contract, geometry)
                decodedCount += detections.size
                assertEquals(reference.size, detections.size)
                for ((a, b) in reference.zip(detections)) {
                    assertEquals(a.classId, b.classId)
                    assertEquals(a.confidence, b.confidence, 0.0001)
                    assertTrue("Decoded box mismatch", a.box.iou(b.box) > 0.99)
                }
                results += "$name: maxError=$maxError, CPU inference ms=$elapsedMs, detections=${detections.size}"
            }
        }
        assertTrue("Parity corpus must exercise nonempty decoded detections", decodedCount > 0)
        LiteRtDetector.open(instrumentation.targetContext.assets, requestedBackend = "GPU").use {
            assertEquals("CPU", it.backend)
            assertNotNull(it.fallbackReason)
        }
        val output = instrumentation.targetContext.filesDir.resolve("trained-cpu-parity.txt")
        output.writeText(results.joinToString("\n"))
        println(results.joinToString("\n"))
    }
}
