package dev.diyhud.inference

import dev.diyhud.domain.Box
import dev.diyhud.domain.EventCodec
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer

class InferenceTest {
    private val root = File(requireNotNull(System.getProperty("hud.repoRoot")))
    private val labels = EventCodec.labels(File(root, "android/app/build/generated/canonical/labels.json").readText())
    private val contract = ModelJson.json.decodeFromString<ModelContract>(
        File(root, "ml/runs/trained-bundle-v2/manifest.json").readText())

    @Test fun compatibleContractAndRejectionPaths() {
        contract.validate(labels)
        for (bad in listOf(contract.copy(schemaVersion = 9),
            contract.copy(input = contract.input.copy(dtype = "int8", quantization = Quantization(0.1, 0))),
            contract.copy(input = contract.input.copy(shape = listOf(1, 640, 640, 1))),
            contract.copy(output = contract.output.copy(nmsOwner = "model")),
            contract.copy(preprocessing = contract.preprocessing.copy(channelOrder = "BGR")),
            contract.copy(labels = labels.copy(sha256 = "0".repeat(64))))) {
            assertThrows(IllegalArgumentException::class.java) { bad.validate(labels) }
        }
    }

    @Test fun letterboxInversePaddingAndPreviewCrop() {
        val geometry = Letterbox(200, 100, 640, 640)
        assertEquals(160, geometry.top)
        assertEquals(Box(0.25, 0.25, 0.75, 0.75), geometry.sourceBox(320.0, 320.0, 320.0, 160.0))
        assertNull(geometry.sourceBox(10.0, 10.0, 5.0, 5.0))
        assertEquals(listOf(-50.0, 0.0, 150.0, 100.0),
            previewBox(Box(0.0, 0.0, 1.0, 1.0), 200, 100, 100, 100))
    }

    @Test fun rgbRotationNormalizationAndPadding() {
        val rgb = byteArrayOf(-1, 0, 0, 0, -1, 0)
        val rotated = rotateRgb(rgb, 2, 1, 90)
        assertEquals(1, rotated.width)
        assertEquals(2, rotated.height)
        assertArrayEquals(rgb, rotated.pixels)
        val tensor = Letterbox(2, 1, 2, 2).preprocess(rgb, 114)
        assertArrayEquals(floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f), tensor.take(6).toFloatArray(), 0f)
        assertEquals(114 / 255f, tensor[6])
        assertArrayEquals(rgb, rotateRgb(rotateRgb(rgb, 2, 1, 180).pixels, 2, 1, 180).pixels)
        assertArrayEquals(rgb, rotateRgb(rotated.pixels, 1, 2, 270).pixels)
    }

    @Test fun classAwareNmsAppliedOnceAndScoreDecoding() {
        val candidateCount = 3
        val shape = contract.output.tensor.copy(shape = listOf(1, labels.classes.size + 4, candidateCount))
        val testContract = contract.copy(output = contract.output.copy(tensor = shape))
        val output = FloatArray((labels.classes.size + 4) * candidateCount)
        for (i in 0..2) {
            output[i] = 320f; output[candidateCount + i] = 320f
            output[2 * candidateCount + i] = 100f; output[3 * candidateCount + i] = 100f
        }
        output[4 * candidateCount] = 0.9f
        output[4 * candidateCount + 1] = 0.8f
        output[5 * candidateCount + 2] = 0.7f
        val result = decodeYolo(output, testContract, Letterbox(640, 640, 640, 640))
        assertEquals(listOf(labels.classes[0].id, labels.classes[1].id), result.map { it.classId })
        output[4 * candidateCount] = Float.NaN
        assertEquals(2, decodeYolo(output, testContract, Letterbox(640, 640, 640, 640)).size)
    }

    @Test fun fatalRuntimeFailureClosesOnceAndPreventsStaleReuse() {
        var closes = 0
        val detector = LiteRtDetector.withTestRuntime(contract, object : TensorRunner {
            override fun run(input: ByteBuffer, output: ByteBuffer) {
                throw IllegalStateException("simulated kernel failure")
            }
            override fun close() { closes++ }
        })
        val input = FloatArray(640 * 640 * 3)
        assertThrows(InferenceException::class.java) { detector.inferTensor(input) }
        assertEquals(1, closes)
        assertThrows(IllegalStateException::class.java) { detector.inferTensor(input) }
        detector.close()
        assertEquals(1, closes)
    }
}
