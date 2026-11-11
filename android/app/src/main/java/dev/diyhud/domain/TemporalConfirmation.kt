package dev.diyhud.domain

/** Session-scoped, bounded candidate association; owns no clock or camera. */
class TemporalConfirmation(
    private val sessionId: String,
    private val modelVersion: String,
    labels: CanonicalLabels,
    private val config: EffectiveConfig,
    private val maxTracks: Int = 128,
) {
    private val classIds = labels.classes.map { it.id }.toSet()
    private val labelsHash = labels.sha256
    private var generation = 0L
    private var lastFrame = -1L
    private var lastTime = -1L
    private data class Track(var detection: Detection, var lastSeen: Long,
                             val hits: ArrayDeque<Long>, var confirmed: Boolean = false)
    private val tracks = mutableListOf<Track>()
    init { require(maxTracks > 0) }

    fun reset(newGeneration: Long) {
        require(newGeneration > generation)
        generation = newGeneration
        tracks.clear()
        lastFrame = -1
        // Preserve the session's monotonic lower bound across resets.
    }

    fun accept(observation: Observation): Payload.ConfirmedBatch? {
        if (observation.sessionId != sessionId || observation.modelVersion != modelVersion ||
            observation.labelsSha256 != labelsHash || observation.resetGeneration != generation ||
            observation.frameId <= lastFrame || observation.captureOffsetMs < lastTime) return null
        lastFrame = observation.frameId
        lastTime = observation.captureOffsetMs
        tracks.removeAll { lastTime - it.lastSeen >= config.rearmAbsenceMs }
        val matched = mutableListOf<Track>()
        val confirmed = mutableListOf<Detection>()
        for (detection in observation.detections.filter {
            it.classId in classIds && it.confidence >= config.confidenceThreshold
        }.sortedByDescending { it.confidence }.take(maxTracks)) {
            // Never let duplicate boxes in one frame count toward separate hits.
            if (matched.any { it.detection.classId == detection.classId &&
                    it.detection.box.iou(detection.box) >= config.associationIou }) continue
            var track = tracks.filter { it !in matched && it.detection.classId == detection.classId &&
                it.detection.box.iou(detection.box) >= config.associationIou }
                .maxByOrNull { it.detection.box.iou(detection.box) }
            if (track == null) {
                if (tracks.size >= maxTracks) continue
                track = Track(detection, lastTime, ArrayDeque())
                tracks += track
            }
            matched += track
            track.detection = detection
            track.lastSeen = lastTime
            while (track.hits.isNotEmpty() && lastTime - track.hits.first() > config.confirmationWindowMs)
                track.hits.removeFirst()
            if (track.hits.size < config.minimumHits) track.hits.addLast(lastTime)
            if (!track.confirmed && track.hits.size >= config.minimumHits) {
                track.confirmed = true
                confirmed += detection
            }
        }
        return confirmed.takeIf { it.isNotEmpty() }?.let {
            Payload.ConfirmedBatch(observation.frameId, it.toList())
        }
    }
}
