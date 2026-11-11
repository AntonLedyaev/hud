package dev.diyhud.data

import dev.diyhud.domain.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.Collections
import java.util.concurrent.*

class EvidenceWriterTest {
    private val root = File(requireNotNull(System.getProperty("hud.repoRoot")))
    private val labels = EventCodec.labels(File(root, "android/app/build/generated/canonical/labels.json").readText())
    private val config = EventCodec.decode(File(root, "shared/fixtures/event.v1.json").readText()).config
        .copy(eventQueueCapacity = 2, minimumHits = 1, estimateMaxAgeMs = 10000)
    private fun header(capacity: Int = 2) = SessionHeader("s", 1000, "test", "m", labels,
        config.copy(eventQueueCapacity = capacity), "{}", "CPU", "fixture")
    private fun event(sequence: Long, header: SessionHeader) = DomainEvent(1, header.id, 0,
        sequence, sequence, header.modelVersion, labels.sha256, header.config, Payload.Tick)
    private open class Sink : EvidenceSink {
        val events = Collections.synchronizedList(mutableListOf<DomainEvent>())
        var footer: SessionFooter? = null
        override fun begin(header: SessionHeader) = Unit
        override fun append(event: DomainEvent) { events += event }
        override fun end(id: String, footer: SessionFooter) { this.footer = footer }
    }
    @Test fun blockedDiskKeepsProducerBoundedAndPersistsTailGap() {
        val worker = Executors.newSingleThreadExecutor()
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        val sink = object : Sink() {
            override fun begin(header: SessionHeader) { entered.countDown(); check(release.await(3, TimeUnit.SECONDS)) }
        }
        val header = header(); val writer = EvidenceWriter(header, worker, sink)
        try {
            assertTrue(entered.await(3, TimeUnit.SECONDS))
            val start = System.nanoTime()
            repeat(100) { writer.offer(event(it.toLong(), header)) }
            assertTrue("Producer waited on disk", System.nanoTime() - start < TimeUnit.SECONDS.toNanos(1))
            assertEquals(98L, writer.snapshot().dropped)
            writer.finish("stop", 2000); release.countDown()
            val status = writer.finished.get(3, TimeUnit.SECONDS)
            assertEquals(2L, status.stored); assertEquals(98L, status.dropped)
            assertTrue(status.incomplete)
            assertEquals(listOf(0L, 1L, 2L), sink.events.map { it.sequence })
            assertEquals(Payload.Gap(98), sink.events.last().payload)
            assertEquals(99L, sink.footer!!.lastAttemptSequence)
        } finally { release.countDown(); writer.finish("cleanup", 2000); worker.shutdown(); worker.awaitTermination(3, TimeUnit.SECONDS) }
    }
    @Test fun overflowGapPrecedesLaterRecoveredWrites() {
        val worker = Executors.newSingleThreadExecutor()
        val entered = CountDownLatch(1); val release = CountDownLatch(1); val drained = CountDownLatch(3)
        val sink = object : Sink() {
            override fun append(event: DomainEvent) {
                if (event.sequence == 0L) { entered.countDown(); check(release.await(3, TimeUnit.SECONDS)) }
                super.append(event); if (event.sequence <= 2) drained.countDown()
            }
        }
        val header = header(); val writer = EvidenceWriter(header, worker, sink)
        try {
            writer.offer(event(0, header)); assertTrue(entered.await(3, TimeUnit.SECONDS))
            (1L..4L).forEach { writer.offer(event(it, header)) }
            release.countDown(); assertTrue(drained.await(3, TimeUnit.SECONDS))
            assertTrue(writer.offer(event(5, header)))
            val status = writer.finish("stop", 2000).get(3, TimeUnit.SECONDS)
            assertEquals(listOf(0L, 1L, 2L, 3L, 5L), sink.events.map { it.sequence })
            assertEquals(Payload.Gap(2), sink.events[3].payload)
            assertEquals(2L, status.dropped); assertEquals(4L, status.stored)
        } finally { release.countDown(); writer.finish("cleanup", 2000); worker.shutdown(); worker.awaitTermination(3, TimeUnit.SECONDS) }
    }
    @Test fun fullStorageDisablesWritesWithoutChangingLiveRecognition() {
        val worker = Executors.newSingleThreadExecutor(); var calls = 0
        val sink = object : Sink() {
            override fun append(event: DomainEvent) { calls++; throw EvidenceBudgetExceeded() }
        }
        val header = header(32); val writer = EvidenceWriter(header, worker, sink)
        val live = dev.diyhud.session.LiveSession(header.id, header.modelVersion, labels, header.config) { writer.offer(it) }
        try {
            val sign = Detection(labels.classes.single { it.name == "speed_limit_30" }.id, .99, Box(.1, .1, .3, .3))
            live.result(live.token(), 0, 0, 100, listOf(sign))
            assertEquals(30, live.state.speed.current)
            val status = writer.finish("stop", 2000).get(3, TimeUnit.SECONDS)
            assertEquals(1, calls); assertTrue(status.disabled); assertTrue(status.incomplete)
            assertEquals(status.attempted, status.dropped)
            assertTrue(status.warning!!.contains("budget"))
            assertEquals(30, live.state.speed.current)
        } finally { writer.finish("cleanup", 2000); worker.shutdown(); worker.awaitTermination(3, TimeUnit.SECONDS) }
    }
    @Test fun rejectedWorkerDoesNotAccumulateWorkOrHideLostEvents() {
        val header = header()
        val writer = EvidenceWriter(header, { throw RejectedExecutionException() }, Sink())
        assertFalse(writer.offer(event(0, header)))
        val status = writer.finish("stop", 2000).get(1, TimeUnit.SECONDS)
        assertTrue(status.disabled); assertEquals(1L, status.dropped)
    }
    @Test fun failedDatabaseOpenReportsUnavailableWithoutRetryingOrKeepingEvents() {
        val worker = Executors.newSingleThreadExecutor(); var calls = 0
        val sink = object : Sink() {
            override fun begin(header: SessionHeader) { calls++; throw java.io.IOException("disk unavailable") }
        }
        val header = header(); val writer = EvidenceWriter(header, worker, sink)
        try {
            writer.offer(event(0, header))
            val status = writer.finish("stop", 2000).get(3, TimeUnit.SECONDS)
            assertEquals(1, calls); assertTrue(status.disabled)
            assertEquals(1L, status.dropped); assertEquals(0L, status.stored)
            assertNull(sink.footer); assertTrue(sink.events.isEmpty())
        } finally { writer.finish("cleanup", 2000); worker.shutdown(); worker.awaitTermination(3, TimeUnit.SECONDS) }
    }
    @Test fun lostResetStillGetsAGapThatClearsTheCommittedGeneration() {
        val worker = Executors.newSingleThreadExecutor(); val release = CountDownLatch(1)
        val sink = object : Sink() {
            override fun begin(header: SessionHeader) { check(release.await(3, TimeUnit.SECONDS)) }
        }
        val header = header(1); val writer = EvidenceWriter(header, worker, sink)
        val sign = Detection(labels.classes.single { it.name == "speed_limit_30" }.id, .99, Box(.1, .1, .3, .3))
        try {
            writer.offer(event(0, header).copy(payload = Payload.ConfirmedBatch(0, listOf(sign))))
            writer.offer(event(1, header).copy(resetGeneration = 1, payload = Payload.Reset))
            writer.offer(event(2, header).copy(resetGeneration = 1))
            writer.finish("stop", 2000); release.countDown(); writer.finished.get(3, TimeUnit.SECONDS)
            assertEquals(0L, sink.events.last().resetGeneration)
            assertEquals(Payload.Gap(2), sink.events.last().payload)
            val states = replay(RestrictionState(header.id, header.modelVersion, labels.sha256, header.config), sink.events, labels)
            assertEquals(30, states[1].speed.current); assertNull(states.last().speed.current)
        } finally { release.countDown(); writer.finish("cleanup", 2000); worker.shutdown(); worker.awaitTermination(3, TimeUnit.SECONDS) }
    }
}
