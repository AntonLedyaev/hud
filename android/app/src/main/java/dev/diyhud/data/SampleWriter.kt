package dev.diyhud.data

import dev.diyhud.domain.EffectiveConfig
import dev.diyhud.domain.Observation
import kotlinx.serialization.Serializable
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicReference

data class SampleFrame(val observation: Observation, val config: EffectiveConfig,
    val sourceSequence: Long, val rgb: ByteArray, val width: Int, val height: Int, val rotation: Int)
@Serializable data class SampleStatus(
    val enabled: Boolean = false, val accepted: Long = 0, val saved: Long = 0,
    val bytes: Long = 0, val dropped: Long = 0, val rateSkipped: Long = 0,
    val limited: Boolean = false, val failed: Boolean = false, val warning: String? = null,
)
class SampleByteLimit : Exception("Image byte budget reached")
interface SampleSink {
    fun save(frame: SampleFrame, jpeg: ByteArray)
    fun report(status: SampleStatus) = Unit
}

/** One encoding item and one pending copy. Producers never encode or access disk. */
class SampleWriter(
    private val config: EffectiveConfig, executor: Executor,
    private val encode: (SampleFrame, Int) -> ByteArray, private val sink: SampleSink,
) {
    private val queue = ArrayBlockingQueue<SampleFrame>(1)
    private val producerLock = Any()
    private val status = AtomicReference(SampleStatus(enabled = config.samplingEnabled))
    private var lastAcceptedMs: Long? = null
    @Volatile private var closing = false
    private var rejected = false
    val finished = CompletableFuture<SampleStatus>()
    fun snapshot(): SampleStatus = status.get()
    init {
        if (config.samplingEnabled) {
            try { executor.execute { drain() } } catch (error: RejectedExecutionException) {
                rejected = true
                status.updateAndGet { it.copy(failed = true, warning = "Image worker unavailable") }
            }
        }
    }
    fun offer(captureMs: Long, copy: () -> SampleFrame): Boolean = synchronized(producerLock) {
        val current = status.get()
        if (!current.enabled || closing || current.failed || current.limited) return false
        if (lastAcceptedMs?.let { captureMs - it < config.sampleIntervalMs } == true) {
            status.updateAndGet { it.copy(rateSkipped = it.rateSkipped + 1) }; return false
        }
        if (queue.remainingCapacity() == 0) {
            status.updateAndGet { it.copy(dropped = it.dropped + 1) }; return false
        }
        val frame = copy()
        require(frame.config == config && frame.observation.captureOffsetMs == captureMs)
        check(queue.offer(frame))
        lastAcceptedMs = captureMs
        status.updateAndGet { it.copy(accepted = it.accepted + 1) }
        true
    }
    fun finish(): CompletableFuture<SampleStatus> = synchronized(producerLock) {
        closing = true
        if (!config.samplingEnabled || rejected) finished.complete(status.get())
        finished
    }
    private fun terminal(error: Exception, currentDropped: Long) = synchronized(producerLock) {
        val lost = queue.size.toLong() + currentDropped
        status.updateAndGet { it.copy(limited = error is SampleByteLimit, failed = error !is SampleByteLimit,
            dropped = it.dropped + lost, warning = error.message ?: "Image write failed") }
        queue.clear()
    }
    private fun drain() {
        var reported = false
        try {
            while (!closing || queue.isNotEmpty()) {
                val current = status.get()
                if ((current.failed || current.limited) && !reported) {
                    try { sink.report(current) } catch (_: Exception) { /* Footer also retains the failure. */ }
                    reported = true
                }
                val frame = queue.poll(100, TimeUnit.MILLISECONDS) ?: continue
                try {
                    val remaining = (config.sampleBudgetBytes - current.bytes).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
                    if (remaining <= 0) throw SampleByteLimit()
                    val jpeg = encode(frame, remaining)
                    if (jpeg.isEmpty() || jpeg.size > remaining) throw SampleByteLimit()
                    sink.save(frame, jpeg)
                    status.updateAndGet { it.copy(saved = it.saved + 1, bytes = it.bytes + jpeg.size,
                        limited = it.bytes + jpeg.size >= config.sampleBudgetBytes,
                        warning = if (it.bytes + jpeg.size >= config.sampleBudgetBytes) "Image byte budget reached" else it.warning) }
                } catch (error: Exception) { terminal(error, 1) }
            }
            try { sink.report(status.get()) } catch (error: Exception) { terminal(error, 0) }
        } catch (error: Exception) {
            terminal(error, 0)
        } finally { finished.complete(status.get()) }
    }
}
