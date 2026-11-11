package dev.diyhud.inference

import android.content.res.AssetManager
import dev.diyhud.domain.Detection
import dev.diyhud.domain.EventCodec
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.tensorflow.lite.DataType
import org.tensorflow.lite.Interpreter
import java.nio.ByteBuffer
import java.nio.ByteOrder

class ModelLoadException(message: String, cause: Throwable? = null) : Exception(message, cause)
class InferenceException(cause: Throwable) : Exception("Inference failed; session must stop", cause)

internal interface TensorRunner {
    fun run(input: ByteBuffer, output: ByteBuffer)
    fun close()
}

/** Create, use and close on the same background worker. Never shares camera buffers. */
class LiteRtDetector private constructor(
    val contract: ModelContract,
    private val modelBuffer: ByteBuffer,
    private val interpreter: TensorRunner,
    val fallbackReason: String? = null,
) : AutoCloseable {
    val backend = "CPU"
    private val owner = Thread.currentThread()
    private var unavailable = false
    private val input = ByteBuffer.allocateDirect(contract.input.shape.reduce(Int::times) * 4)
        .order(ByteOrder.nativeOrder())
    private val output = ByteBuffer.allocateDirect(contract.output.tensor.shape.reduce(Int::times) * 4)
        .order(ByteOrder.nativeOrder())

    fun inferTensor(values: FloatArray): FloatArray {
        check(Thread.currentThread() === owner) { "Detector worker changed" }
        check(!unavailable) { "Detector is closed or failed" }
        require(values.size * 4 == input.capacity() && values.all { it.isFinite() })
        try {
            input.clear()
            input.asFloatBuffer().put(values)
            output.clear()
            interpreter.run(input, output)
            output.rewind()
            val result = FloatArray(output.capacity() / 4)
            output.asFloatBuffer().get(result)
            require(result.all { it.isFinite() }) { "Non-finite inference result" }
            return result
        } catch (failure: Exception) {
            unavailable = true
            interpreter.close()
            throw InferenceException(failure)
        }
    }

    fun detect(rgb: ByteArray, width: Int, height: Int, rotation: Int = 0): List<Detection> {
        val upright = rotateRgb(rgb, width, height, rotation)
        val geometry = Letterbox(upright.width, upright.height,
            contract.input.shape[2], contract.input.shape[1])
        val tensor = geometry.preprocess(upright.pixels, contract.preprocessing.paddingValue)
        return decodeYolo(inferTensor(tensor), contract, geometry)
    }

    override fun close() {
        check(Thread.currentThread() === owner)
        if (!unavailable) { unavailable = true; interpreter.close() }
    }

    companion object {
        internal fun withTestRuntime(contract: ModelContract, runtime: TensorRunner): LiteRtDetector =
            LiteRtDetector(contract, ByteBuffer.allocateDirect(1), runtime)

        fun open(assets: AssetManager, requestedBackend: String = "CPU"): LiteRtDetector {
            return open({ name -> assets.open(name).use { it.readBytes() } }, requestedBackend)
        }

        fun open(read: (String) -> ByteArray, requestedBackend: String = "CPU"): LiteRtDetector {
            var runtime: Interpreter? = null
            try {
                val manifestBytes = read("model/manifest.json")
                val manifest = manifestBytes.toString(Charsets.UTF_8)
                val contract = ModelJson.json.decodeFromString<ModelContract>(manifest)
                val labels = EventCodec.labels(read("labels.json").toString(Charsets.UTF_8))
                contract.validate(labels)
                val profile = ModelJson.json.parseToJsonElement(
                    read("model/preprocessing-profile.json").toString(Charsets.UTF_8)).jsonObject
                val expected = mapOf("version" to "1", "interpolation" to "nearest_pixel_centers",
                    "rounding" to "ties_to_even", "input_dtype" to "RGB888",
                    "manifest_sha256" to ModelJson.sha256(manifestBytes))
                require(profile.mapValues { it.value.jsonPrimitive.content } == expected)
                val bytes = read("model/model.tflite")
                require(ModelJson.sha256(bytes) == contract.modelSha256) { "Model checksum mismatch" }
                val buffer = ByteBuffer.allocateDirect(bytes.size).order(ByteOrder.nativeOrder())
                    .apply { put(bytes); rewind() }
                runtime = Interpreter(buffer, Interpreter.Options().setNumThreads(2).setUseXNNPACK(false))
                runtime.allocateTensors()
                require(runtime.inputTensorCount == 1 && runtime.outputTensorCount == 1)
                require(runtime.getInputTensor(0).dataType() == DataType.FLOAT32 &&
                    runtime.getOutputTensor(0).dataType() == DataType.FLOAT32)
                require(runtime.getInputTensor(0).shape().toList() == contract.input.shape &&
                    runtime.getOutputTensor(0).shape().toList() == contract.output.tensor.shape) {
                    "Model tensor metadata mismatch"
                }
                require(requestedBackend in listOf("CPU", "GPU"))
                val engine = runtime
                val runner = object : TensorRunner {
                    override fun run(input: ByteBuffer, output: ByteBuffer) = engine.run(input, output)
                    override fun close() = engine.close()
                }
                return LiteRtDetector(contract, buffer, runner,
                    if (requestedBackend == "GPU") "GPU is not approved for this bundle; using validated CPU" else null)
            } catch (failure: Exception) {
                runtime?.close()
                throw ModelLoadException("Missing, incompatible or corrupt model bundle", failure)
            }
        }
    }
}
