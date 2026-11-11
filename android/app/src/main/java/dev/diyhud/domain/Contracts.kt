package dev.diyhud.domain

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
data class EffectiveConfig(
    val version: Int,
    @SerialName("confidence_threshold") val confidenceThreshold: Double,
    @SerialName("minimum_hits") val minimumHits: Int,
    @SerialName("confirmation_window_ms") val confirmationWindowMs: Long,
    @SerialName("association_iou") val associationIou: Double,
    @SerialName("rearm_absence_ms") val rearmAbsenceMs: Long,
    @SerialName("estimate_max_age_ms") val estimateMaxAgeMs: Long,
    @SerialName("stop_display_ms") val stopDisplayMs: Long,
    @SerialName("sampling_enabled") val samplingEnabled: Boolean,
    @SerialName("sample_interval_ms") val sampleIntervalMs: Long,
    @SerialName("sample_budget_bytes") val sampleBudgetBytes: Long,
    @SerialName("event_queue_capacity") val eventQueueCapacity: Int,
    @SerialName("storage_budget_bytes") val storageBudgetBytes: Long,
) {
    init {
        require(version > 0 && minimumHits > 0 && eventQueueCapacity > 0)
        require(confidenceThreshold in 0.0..1.0 && associationIou in 0.0..1.0)
        require(listOf(confirmationWindowMs, rearmAbsenceMs, estimateMaxAgeMs,
            stopDisplayMs, sampleIntervalMs).all { it > 0 })
        require(sampleBudgetBytes >= 0 && storageBudgetBytes > 0)
    }
}

@Serializable
data class Box(val left: Double, val top: Double, val right: Double, val bottom: Double) {
    init {
        require(listOf(left, top, right, bottom).all { it.isFinite() && it in 0.0..1.0 })
        require(left < right && top < bottom)
    }
    fun iou(other: Box): Double {
        val intersection = (minOf(right, other.right) - maxOf(left, other.left)).coerceAtLeast(0.0) *
            (minOf(bottom, other.bottom) - maxOf(top, other.top)).coerceAtLeast(0.0)
        return intersection / ((right - left) * (bottom - top) +
            (other.right - other.left) * (other.bottom - other.top) - intersection)
    }
}

@Serializable
data class Detection(@SerialName("class_id") val classId: Int, val confidence: Double, val box: Box) {
    init { require(classId >= 0 && confidence.isFinite() && confidence in 0.0..1.0) }
}

@Serializable
data class Observation(
    @SerialName("schema_version") val schemaVersion: Int,
    @SerialName("session_id") val sessionId: String,
    @SerialName("reset_generation") val resetGeneration: Long,
    @SerialName("frame_id") val frameId: Long,
    @SerialName("capture_offset_ms") val captureOffsetMs: Long,
    @SerialName("model_version") val modelVersion: String,
    @SerialName("labels_sha256") val labelsSha256: String,
    val detections: List<Detection>,
) {
    init {
        require(schemaVersion == 1 && sessionId.isNotBlank() && modelVersion.isNotBlank())
        require(resetGeneration >= 0 && frameId >= 0 && captureOffsetMs >= 0)
        require(labelsSha256.matches(Regex("[0-9a-f]{64}")))
    }
}

@Serializable
sealed interface Payload {
    @Serializable @SerialName("confirmed_batch")
    data class ConfirmedBatch(@SerialName("frame_id") val frameId: Long, val signs: List<Detection>) : Payload {
        init { require(frameId >= 0 && signs.isNotEmpty()) }
    }
    @Serializable @SerialName("tick") data object Tick : Payload
    @Serializable @SerialName("reset") data object Reset : Payload
    @Serializable @SerialName("gap")
    data class Gap(@SerialName("dropped_events") val droppedEvents: Long) : Payload {
        init { require(droppedEvents > 0) }
    }
    @Serializable @SerialName("transition")
    data class Transition(val reason: String, @SerialName("source_sequences") val sourceSequences: List<Long>,
                          val details: String) : Payload
    @Serializable @SerialName("conflict")
    data class Conflict(val reason: String, @SerialName("source_sequences") val sourceSequences: List<Long>,
                        val details: String) : Payload
    @Serializable @SerialName("error")
    data class Error(val reason: String, @SerialName("source_sequences") val sourceSequences: List<Long>,
                     val details: String) : Payload
}

@Serializable
data class DomainEvent(
    @SerialName("schema_version") val schemaVersion: Int,
    @SerialName("session_id") val sessionId: String,
    @SerialName("reset_generation") val resetGeneration: Long,
    val sequence: Long,
    @SerialName("offset_ms") val offsetMs: Long,
    @SerialName("model_version") val modelVersion: String,
    @SerialName("labels_sha256") val labelsSha256: String,
    val config: EffectiveConfig,
    val payload: Payload,
) {
    init {
        require(schemaVersion == 1 && sessionId.isNotBlank() && modelVersion.isNotBlank())
        require(resetGeneration >= 0 && sequence >= 0 && offsetMs >= 0)
        require(labelsSha256.matches(Regex("[0-9a-f]{64}")))
    }
}

@Serializable
data class Label(val id: Int, val name: String)
@Serializable
data class CanonicalLabels(val version: Int, val sha256: String, val classes: List<Label>) {
    init {
        require(version > 0 && sha256.matches(Regex("[0-9a-f]{64}")))
        require(classes.isNotEmpty() && classes.map { it.id } == classes.indices.toList())
        require(classes.map { it.name }.distinct().size == classes.size)
    }
}

object EventCodec {
    private val json = Json { classDiscriminator = "kind"; encodeDefaults = true }
    fun encode(event: DomainEvent): String = json.encodeToString(event)
    fun decode(value: String): DomainEvent = json.decodeFromString(value)
    fun observation(value: String): Observation = json.decodeFromString(value)
    fun labels(value: String): CanonicalLabels = json.decodeFromString(value)
}
