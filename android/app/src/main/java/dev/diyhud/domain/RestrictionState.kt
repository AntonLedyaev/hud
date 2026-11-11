package dev.diyhud.domain

import kotlinx.serialization.Serializable

@Serializable enum class Freshness { UNKNOWN, KNOWN, STALE }
@Serializable data class Estimate<T>(
    val value: T? = null,
    val freshness: Freshness = Freshness.UNKNOWN,
    val sourceSequence: Long? = null,
    val confirmedAtMs: Long? = null,
) {
    val current: T? get() = value.takeIf { freshness == Freshness.KNOWN }
    fun expire(now: Long, maxAge: Long): Estimate<T> =
        if (freshness == Freshness.KNOWN && confirmedAtMs != null && now - confirmedAtMs >= maxAge)
            copy(freshness = Freshness.STALE) else this
}

@Serializable data class RestrictionState(
    val sessionId: String,
    val modelVersion: String,
    val labelsSha256: String,
    val config: EffectiveConfig,
    val generation: Long = 0,
    val sequence: Long = -1,
    val offsetMs: Long = 0,
    val frameId: Long = -1,
    val speed: Estimate<Int> = Estimate(),
    val overtaking: Estimate<Boolean> = Estimate(),
    val stopUntilMs: Long? = null,
    val reasons: List<String> = emptyList(),
)

/** Pure reducer: every time-dependent change comes from an explicit event. */
class RestrictionReducer(labels: CanonicalLabels) {
    private val names = labels.classes.associate { it.id to it.name }
    private val hash = labels.sha256

    fun reduce(previous: RestrictionState, event: DomainEvent): RestrictionState {
        if (event.sessionId != previous.sessionId || event.modelVersion != previous.modelVersion ||
            event.labelsSha256 != hash || event.labelsSha256 != previous.labelsSha256 ||
            event.config != previous.config || event.sequence <= previous.sequence ||
            event.offsetMs < previous.offsetMs) return previous
        if (event.payload == Payload.Reset) {
            if (event.resetGeneration != previous.generation + 1) return previous
            return RestrictionState(previous.sessionId, previous.modelVersion, hash, previous.config,
                event.resetGeneration, event.sequence, event.offsetMs, reasons = listOf("manual_reset"))
        }
        if (event.resetGeneration != previous.generation) return previous
        val payload = event.payload
        if (payload is Payload.Transition || payload is Payload.Conflict)
            return previous.copy(sequence = event.sequence, offsetMs = event.offsetMs)
        if (payload is Payload.ConfirmedBatch && payload.frameId <= previous.frameId) return previous
        var state = previous.copy(sequence = event.sequence, offsetMs = event.offsetMs, reasons = emptyList())
        val reasons = mutableListOf<String>()
        val speed = state.speed.expire(event.offsetMs, state.config.estimateMaxAgeMs)
        val overtaking = state.overtaking.expire(event.offsetMs, state.config.estimateMaxAgeMs)
        if (speed != state.speed) reasons += "speed_stale"
        if (overtaking != state.overtaking) reasons += "overtaking_stale"
        if (state.stopUntilMs != null && event.offsetMs >= state.stopUntilMs) reasons += "stop_expired"
        state = state.copy(speed = speed, overtaking = overtaking,
            stopUntilMs = state.stopUntilMs?.takeIf { event.offsetMs < it })
        if (payload is Payload.Gap || payload is Payload.Error) {
            return state.copy(speed = Estimate(), overtaking = Estimate(), stopUntilMs = null,
                reasons = listOf(if (payload is Payload.Gap) "evidence_gap" else "recognition_error"))
        }
        if (payload !is Payload.ConfirmedBatch) return state.copy(reasons = reasons.toList())
        val signs = payload.signs.filter { it.confidence >= state.config.confidenceThreshold }
            .mapNotNull { names[it.classId] }.toSet()
        val limits = signs.filter { it.startsWith("speed_limit_") }
            .mapNotNull { it.removePrefix("speed_limit_").toIntOrNull() }.toSet()
        val cancelSpeed = "end_all_restrictions" in signs ||
            ("end_speed_limit_80" in signs && state.speed.value == 80)
        if (limits.size > 1 || (limits.isNotEmpty() && cancelSpeed)) {
            state = state.copy(speed = Estimate()); reasons += "speed_conflict"
        } else if (limits.isNotEmpty()) {
            state = state.copy(speed = Estimate(limits.single(), Freshness.KNOWN, event.sequence, event.offsetMs))
            reasons += "speed_confirmed"
        } else if (cancelSpeed) {
            state = state.copy(speed = Estimate()); reasons += "speed_cancelled"
        }
        val restrict = "no_overtaking" in signs
        val cancel = "end_no_overtaking" in signs || "end_all_restrictions" in signs
        if (restrict && cancel) {
            state = state.copy(overtaking = Estimate()); reasons += "overtaking_conflict"
        } else if (restrict) {
            state = state.copy(overtaking = Estimate(true, Freshness.KNOWN, event.sequence, event.offsetMs))
            reasons += "overtaking_confirmed"
        } else if (cancel) {
            state = state.copy(overtaking = Estimate()); reasons += "overtaking_cancelled"
        }
        if ("stop" in signs) {
            state = state.copy(stopUntilMs = event.offsetMs + state.config.stopDisplayMs)
            reasons += "stop_observed"
        }
        return state.copy(frameId = payload.frameId, reasons = reasons.toList())
    }
}

fun replay(initial: RestrictionState, events: List<DomainEvent>, labels: CanonicalLabels): List<RestrictionState> {
    val reducer = RestrictionReducer(labels)
    return events.runningFold(initial) { state, event -> reducer.reduce(state, event) }
}
