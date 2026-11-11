package dev.diyhud.session

import kotlin.math.ceil

data class PipelineStats(
    val frames: Long = 0, val fps: Double = 0.0, val dropped: Long = 0,
    val inferenceP50: Double = 0.0, val inferenceP95: Double = 0.0,
    val latencyP50: Double = 0.0, val latencyP95: Double = 0.0,
    val warmupFrames: Long = 0, val warmupEndMs: Long = 5000,
    val steadySamples: Int = 0,
)

/** Bounded rolling latency samples; FPS counts the whole steady-state interval. */
class PipelineMetrics(private val warmupMs: Long = 5000, private val capacity: Int = 512) {
    private val inference = ArrayDeque<Double>()
    private val latency = ArrayDeque<Double>()
    private var warmupFrames = 0L
    private var frames = 0L
    fun record(nowMs: Long, inferenceMs: Double, latencyMs: Double) {
        frames++
        if (nowMs < warmupMs) { warmupFrames++; return }
        if (inference.size == capacity) { inference.removeFirst(); latency.removeFirst() }
        inference.addLast(inferenceMs); latency.addLast(latencyMs)
    }
    fun snapshot(nowMs: Long, dropped: Long) = PipelineStats(frames,
        if (nowMs > warmupMs) (frames - warmupFrames) * 1000.0 / (nowMs - warmupMs) else 0.0,
        dropped, percentile(inference, .50), percentile(inference, .95),
        percentile(latency, .50), percentile(latency, .95), warmupFrames, warmupMs, inference.size)
    private fun percentile(values: Collection<Double>, percentile: Double): Double =
        if (values.isEmpty()) 0.0 else values.sorted()[(ceil(values.size * percentile).toInt() - 1).coerceAtLeast(0)]
}

/** Camera timestamps can have a different origin. Anchor once, preserve intervals. */
class CameraClock {
    private var cameraBase: Long? = null
    private var sessionBase = 0L
    private var last = -1L
    fun offsetMs(cameraNs: Long, sessionNowMs: Long, realtimeNowNs: Long? = null): Long {
        if (cameraBase == null) { cameraBase = cameraNs; sessionBase = sessionNowMs }
        val time = (if (realtimeNowNs != null) sessionNowMs - (realtimeNowNs - cameraNs) / 1_000_000
            else sessionBase + (cameraNs - cameraBase!!) / 1_000_000).coerceAtLeast(0)
        require(time >= last) { "Camera timestamps moved backwards" }
        last = time
        return time
    }
}
