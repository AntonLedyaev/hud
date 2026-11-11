package dev.diyhud.data

import dev.diyhud.domain.*
import kotlinx.serialization.Serializable
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

@Serializable data class SessionHeader(
    val id: String, val startedAtEpochMs: Long, val appVersion: String,
    val modelVersion: String, val labels: CanonicalLabels, val config: EffectiveConfig,
    val modelManifest: String, val backend: String, val device: String,
)
data class EvidenceStatus(
    val attempted: Long = 0, val stored: Long = 0, val dropped: Long = 0,
    val gaps: Long = 0, val disabled: Boolean = false, val warning: String? = null,
) { val incomplete get() = dropped > 0 || disabled }
data class SessionFooter(val endedAtEpochMs: Long, val reason: String,
                         val lastAttemptSequence: Long, val status: EvidenceStatus,
                         val sampling: SampleStatus? = null)
interface EvidenceSink {
    fun begin(header: SessionHeader)
    fun append(event: DomainEvent)
    fun end(id: String, footer: SessionFooter)
}
class EvidenceBudgetExceeded : Exception("Local evidence storage budget reached")

/** Nonblocking producers, one bounded queue, one writer, no per-event retries. */
class EvidenceWriter(val header: SessionHeader, executor: Executor, private val sink: EvidenceSink) {
    private val queue = ArrayBlockingQueue<DomainEvent>(header.config.eventQueueCapacity)
    private val status = AtomicReference(EvidenceStatus())
    private val producerLock = Any()
    @Volatile private var closing: SessionFooter? = null
    @Volatile private var lastAttempt: DomainEvent? = null
    private var lastStored = -1L
    private var lastStoredGeneration = 0L
    private var rejected = false
    val finished = CompletableFuture<EvidenceStatus>()
    val started = CompletableFuture<Boolean>()
    fun snapshot(): EvidenceStatus = status.get()
    init {
        try { executor.execute { drain() } } catch (error: java.util.concurrent.RejectedExecutionException) {
            status.set(EvidenceStatus(disabled = true, warning = "Evidence worker unavailable"))
            rejected = true
            started.complete(false)
        }
    }
    fun offer(event: DomainEvent): Boolean = synchronized(producerLock) {
        if (closing != null) return false
        require(event.sessionId == header.id && event.modelVersion == header.modelVersion &&
            event.labelsSha256 == header.labels.sha256 && event.config == header.config)
        require(event.sequence == (lastAttempt?.sequence ?: -1L) + 1) { "Evidence inputs must be contiguous" }
        lastAttempt = event
        status.updateAndGet { it.copy(attempted = it.attempted + 1) }
        if (status.get().disabled || !queue.offer(event)) {
            status.updateAndGet { it.copy(dropped = it.dropped + 1,
                warning = it.warning ?: "Evidence queue overflow; trip is incomplete") }
            false
        } else true
    }
    fun finish(reason: String, endedAtEpochMs: Long, sampling: SampleStatus? = null): CompletableFuture<EvidenceStatus> = synchronized(producerLock) {
        if (closing == null) closing = SessionFooter(endedAtEpochMs, reason, lastAttempt?.sequence ?: -1, status.get(), sampling)
        if (rejected) finished.complete(status.get())
        finished
    }
    private fun gapBefore(next: DomainEvent, final: Boolean = false) {
        val missing = next.sequence - lastStored - if (final) 0 else 1
        if (missing > 0) {
            // A missing Reset must not make the gap itself invisible to the old generation.
            sink.append(next.copy(sequence = lastStored + 1, resetGeneration = lastStoredGeneration,
                payload = Payload.Gap(missing)))
            status.updateAndGet { it.copy(gaps = it.gaps + 1) }
        }
    }
    private fun disable(error: Exception, currentDropped: Long = 0) {
        synchronized(producerLock) {
            val remaining = queue.size.toLong()
            status.updateAndGet { it.copy(disabled = true, dropped = it.dropped + remaining + currentDropped,
                warning = if (error is EvidenceBudgetExceeded) error.message else "Evidence write failed; recording disabled") }
            queue.clear()
        }
    }
    private fun drain() {
        var begun = false
        try {
            try { sink.begin(header); begun = true; started.complete(true) }
            catch (error: Exception) { disable(error); started.complete(false) }
            while (closing == null || queue.isNotEmpty()) {
                val event = queue.poll(100, TimeUnit.MILLISECONDS) ?: continue
                try {
                    gapBefore(event)
                    sink.append(event)
                    lastStored = event.sequence
                    lastStoredGeneration = event.resetGeneration
                    status.updateAndGet { it.copy(stored = it.stored + 1) }
                } catch (error: Exception) { disable(error, 1) }
            }
            if (begun) {
                if (!status.get().disabled) try { lastAttempt?.let { gapBefore(it, final = true) } }
                catch (error: Exception) { disable(error) }
                try { sink.end(header.id, closing!!.copy(status = status.get())) }
                catch (error: Exception) { disable(error) }
            }
        } catch (error: Exception) { disable(error) }
        finally { started.complete(false); finished.complete(status.get()) }
    }
}
