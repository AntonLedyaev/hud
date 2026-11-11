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
    private var nextTrackId = 0L
    private data class Track(val id: Long, var detection: Detection, var lastSeen: Long,
                             val hits: ArrayDeque<Long>, var confirmed: Boolean = false,
                             var reason: ConfirmationReason = ConfirmationReason.NEW_CANDIDATE,
                             var matchedIou: Double? = null)
    private val tracks = mutableListOf<Track>()
    var diagnostics = debug(0, emptyList())
        private set
    private fun debug(time: Long, notes: List<String>, rejected: List<Detection> = emptyList()) = ConfirmationDebug(
        time, config.confirmationWindowMs, config.confidenceThreshold, config.associationIou,
        tracks.map { track ->
            val hits = track.hits.count { time - it <= config.confirmationWindowMs }
            val reason = if (!track.confirmed && track.reason == ConfirmationReason.NOT_SEEN && hits < track.hits.size)
                ConfirmationReason.WINDOW_EXPIRED else track.reason
            ConfirmationProgress(track.id, track.detection, hits, config.minimumHits, track.lastSeen, reason, track.matchedIou)
        } + rejected.map { ConfirmationProgress(null, it, 0, config.minimumHits, time, ConfirmationReason.BELOW_CONFIDENCE) },
        notes.toList())
    init { require(maxTracks > 0) }

    fun reset(newGeneration: Long) {
        require(newGeneration > generation)
        generation = newGeneration
        tracks.clear()
        diagnostics = debug(lastTime.coerceAtLeast(0), listOf("Manual reset: pending hits cleared"))
        lastFrame = -1
        // Preserve the session's monotonic lower bound across resets.
    }

    fun accept(observation: Observation): Payload.ConfirmedBatch? {
        if (observation.sessionId != sessionId || observation.modelVersion != modelVersion ||
            observation.labelsSha256 != labelsHash || observation.resetGeneration != generation ||
            observation.frameId <= lastFrame || observation.captureOffsetMs < lastTime) return null
        lastFrame = observation.frameId
        lastTime = observation.captureOffsetMs
        val notes = mutableListOf<String>()
        val expired = tracks.count { lastTime - it.lastSeen >= config.rearmAbsenceMs }
        if (expired > 0) notes += "Rearmed $expired track(s) after ${config.rearmAbsenceMs} ms absence"
        tracks.removeAll { lastTime - it.lastSeen >= config.rearmAbsenceMs }
        tracks.forEach {
            it.reason = if (it.confirmed) ConfirmationReason.ALREADY_CONFIRMED else ConfirmationReason.NOT_SEEN
            it.matchedIou = null
        }
        val matched = mutableListOf<Track>()
        val confirmed = mutableListOf<Detection>()
        for (detection in observation.detections.filter {
            it.classId in classIds && it.confidence >= config.confidenceThreshold
        }.sortedByDescending { it.confidence }.take(maxTracks)) {
            // Never let duplicate boxes in one frame count toward separate hits.
            if (matched.any { it.detection.classId == detection.classId &&
                    it.detection.box.iou(detection.box) >= config.associationIou }) {
                if (notes.size < 8) notes += "Duplicate box ignored in this frame"
                continue
            }
            var track = tracks.filter { it !in matched && it.detection.classId == detection.classId &&
                it.detection.box.iou(detection.box) >= config.associationIou }
                .maxByOrNull { it.detection.box.iou(detection.box) }
            if (track == null) {
                if (tracks.size >= maxTracks) {
                    if (notes.size < 8) notes += "Track capacity reached ($maxTracks)"
                    continue
                }
                val mismatch = tracks.any { it.detection.classId == detection.classId && it !in matched }
                track = Track(nextTrackId++, detection, lastTime, ArrayDeque(),
                    reason = if (mismatch) ConfirmationReason.BOX_MISMATCH else ConfirmationReason.NEW_CANDIDATE)
                tracks += track
            } else {
                track.matchedIou = track.detection.box.iou(detection.box)
                track.reason = if (track.confirmed) ConfirmationReason.ALREADY_CONFIRMED else ConfirmationReason.MATCHED
            }
            matched += track
            track.detection = detection
            track.lastSeen = lastTime
            var expiredHits = 0
            while (track.hits.isNotEmpty() && lastTime - track.hits.first() > config.confirmationWindowMs) {
                track.hits.removeFirst()
                expiredHits++
            }
            if (expiredHits > 0 && !track.confirmed) track.reason = ConfirmationReason.WINDOW_EXPIRED
            if (track.hits.size < config.minimumHits) track.hits.addLast(lastTime)
            if (!track.confirmed && track.hits.size >= config.minimumHits) {
                track.confirmed = true
                track.reason = ConfirmationReason.CONFIRMED
                confirmed += detection
            }
        }
        val rejected = observation.detections.filter { it.classId in classIds && it.confidence < config.confidenceThreshold }.take(maxTracks)
        if (observation.detections.isEmpty()) notes += "No model candidates above the decoder threshold"
        diagnostics = debug(lastTime, notes, rejected)
        return confirmed.takeIf { it.isNotEmpty() }?.let {
            Payload.ConfirmedBatch(observation.frameId, it.toList())
        }
    }
}
