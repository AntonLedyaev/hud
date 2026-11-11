package dev.diyhud.session

import dev.diyhud.domain.*

data class FrameToken(val sessionId: String, val generation: Long)

/** Called under coordinator serialization. Event time is availability time;
 * observation time remains capture time, so ticks cannot discard valid slow results. */
class LiveSession(
    val id: String, val model: String, val labels: CanonicalLabels,
    val config: EffectiveConfig, private val onEvent: (DomainEvent) -> Unit = {},
) {
    private val confirmation = TemporalConfirmation(id, model, labels, config)
    private val reducer = RestrictionReducer(labels)
    private var sequence = 0L
    private var active = true
    private var generationStartMs = 0L
    var state = RestrictionState(id, model, labels.sha256, config)
        private set
    fun token() = FrameToken(id, state.generation)
    fun accepts(token: FrameToken) = active && token == token()
    private fun emit(time: Long, payload: Payload, generation: Long = state.generation) {
        val event = DomainEvent(1, id, generation, sequence++, time.coerceAtLeast(state.offsetMs),
            model, labels.sha256, config, payload)
        state = reducer.reduce(state, event)
        onEvent(event)
    }
    fun result(token: FrameToken, frameId: Long, captureMs: Long,
               availableMs: Long, detections: List<Detection>): Boolean {
        if (!accepts(token) || captureMs < generationStartMs) return false
        val batch = confirmation.accept(Observation(1, id, token.generation, frameId,
            captureMs, model, labels.sha256, detections.toList()))
        if (batch != null) emit(availableMs, batch)
        else tick(availableMs)
        return true
    }
    fun tick(now: Long) { if (active) emit(now, Payload.Tick) }
    fun reset(now: Long) {
        if (!active) return
        val next = state.generation + 1
        generationStartMs = now.coerceAtLeast(state.offsetMs)
        confirmation.reset(next)
        emit(now, Payload.Reset, next)
    }
    fun stop() { active = false }
}
