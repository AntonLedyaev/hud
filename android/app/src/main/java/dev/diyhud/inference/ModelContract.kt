package dev.diyhud.inference

import dev.diyhud.domain.CanonicalLabels
import dev.diyhud.domain.EffectiveConfig
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.security.MessageDigest

@Serializable data class Quantization(val scale: Double, @SerialName("zero_point") val zeroPoint: Int)
@Serializable data class TensorSpec(val shape: List<Int>, val dtype: String, val quantization: Quantization?)
@Serializable data class Normalization(val scale: Double, val offset: Double)
@Serializable data class Preprocessing(
    @SerialName("channel_order") val channelOrder: String, val layout: String,
    val normalization: Normalization, val resize: String,
    @SerialName("padding_value") val paddingValue: Int, val rotation: String,
)
@Serializable data class OutputSpec(
    val tensor: TensorSpec, val layout: String,
    @SerialName("box_format") val boxFormat: String, val coordinates: String,
    @SerialName("score_decoding") val scoreDecoding: String,
    @SerialName("nms_owner") val nmsOwner: String,
    @SerialName("confidence_threshold") val confidenceThreshold: Double,
    @SerialName("nms_iou") val nmsIou: Double,
)
@Serializable data class Provenance(
    @SerialName("run_id") val runId: String,
    @SerialName("checkpoint_sha256") val checkpointSha256: String,
    @SerialName("config_sha256") val configSha256: String,
    @SerialName("dataset_manifest_sha256") val datasetManifestSha256: String,
    val tools: Map<String, String>,
)
@Serializable data class ModelContract(
    @SerialName("schema_version") val schemaVersion: Int,
    @SerialName("model_version") val modelVersion: String,
    @SerialName("model_sha256") val modelSha256: String,
    val labels: CanonicalLabels, val provenance: Provenance,
    val input: TensorSpec, val preprocessing: Preprocessing,
    val output: OutputSpec, val config: EffectiveConfig,
) {
    fun validate(expected: CanonicalLabels) {
        require(schemaVersion == 1 && modelVersion.isNotBlank()) { "Unsupported model version" }
        require(labels == expected) { "Model labels differ from canonical build labels" }
        require(input.dtype == "float32" && input.quantization == null &&
            output.tensor.dtype == "float32" && output.tensor.quantization == null) {
            "Only validated FP32 contracts are supported"
        }
        require(input.shape.size == 4 && input.shape[0] == 1 && input.shape[3] == 3 &&
            input.shape[1] > 0 && input.shape[2] > 0)
        require(preprocessing.channelOrder == "RGB" && preprocessing.layout == "NHWC" &&
            preprocessing.resize == "letterbox" && preprocessing.rotation == "upright_before_resize")
        require(preprocessing.paddingValue in 0..255 &&
            preprocessing.normalization.scale == 1.0 / 255 && preprocessing.normalization.offset == 0.0)
        require(output.layout == "BCN" && output.boxFormat == "cxcywh" &&
            output.coordinates == "input_pixels" && output.scoreDecoding == "class_probability" &&
            output.nmsOwner == "consumer") { "Unsupported detector output contract" }
        require(output.tensor.shape.size == 3 && output.tensor.shape[0] == 1 &&
            output.tensor.shape[1] == labels.classes.size + 4 && output.tensor.shape[2] > 0)
        require(output.confidenceThreshold in 0.0..1.0 && output.nmsIou in 0.0..1.0)
        val hashes = listOf(modelSha256, provenance.checkpointSha256, provenance.configSha256,
            provenance.datasetManifestSha256)
        require(hashes.all { it.matches(Regex("[0-9a-f]{64}")) })
    }
}

object ModelJson {
    val json = Json { ignoreUnknownKeys = false }
    fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { "%02x".format(it) }
}
