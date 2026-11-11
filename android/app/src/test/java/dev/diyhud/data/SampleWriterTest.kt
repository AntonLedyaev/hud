package dev.diyhud.data

import dev.diyhud.domain.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.concurrent.*

class SampleWriterTest {
    private val root = File(requireNotNull(System.getProperty("hud.repoRoot")))
    private val config = EventCodec.decode(File(root, "shared/fixtures/event.v1.json").readText()).config
        .copy(samplingEnabled = true, sampleIntervalMs = 1000, sampleBudgetBytes = 100)
    private fun frame(time: Long, config: EffectiveConfig = this.config) = SampleFrame(
        Observation(1, "sample", 0, time, time, "m", "0".repeat(64), emptyList()), config, time,
        byteArrayOf(1, 2, 3), 1, 1, 0)
    private fun await(condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
        while (!condition() && System.nanoTime() < deadline) Thread.sleep(5)
        assertTrue(condition())
    }
    @Test fun disabledSamplingNeverCopiesSchedulesOrEncodes() {
        var copies = 0
        val writer = SampleWriter(config.copy(samplingEnabled = false), { fail("Disabled worker scheduled") },
            { _, _ -> error("Disabled encoder called") }, object : SampleSink {
                override fun save(frame: SampleFrame, jpeg: ByteArray) = fail("Disabled sink called")
            })
        repeat(10) { assertFalse(writer.offer(it.toLong()) { copies++; frame(it.toLong()) }) }
        assertEquals(0, copies); assertFalse(writer.finish().get().enabled)
    }
    @Test fun slowEncoderKeepsOnlyOnePendingCopyAndEnforcesCaptureRate() {
        val worker = Executors.newSingleThreadExecutor()
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        val saved = mutableListOf<SampleFrame>(); var copies = 0
        val writer = SampleWriter(config, worker, { candidate, _ ->
            if (candidate.observation.frameId == 0L) { entered.countDown(); check(release.await(3, TimeUnit.SECONDS)) }
            byteArrayOf(1, 2, 3, 4)
        }, object : SampleSink { override fun save(frame: SampleFrame, jpeg: ByteArray) { saved += frame } })
        try {
            assertTrue(writer.offer(0) { copies++; frame(0) }); assertTrue(entered.await(3, TimeUnit.SECONDS))
            assertFalse(writer.offer(500) { copies++; frame(500) })
            assertTrue(writer.offer(1000) { copies++; frame(1000) })
            assertFalse(writer.offer(2000) { copies++; frame(2000) })
            assertEquals(2, copies)
            writer.finish(); release.countDown()
            val status = writer.finished.get(3, TimeUnit.SECONDS)
            assertEquals(listOf(0L, 1000L), saved.map { it.observation.captureOffsetMs })
            assertEquals(2L, status.saved); assertEquals(8L, status.bytes)
            assertEquals(1L, status.dropped); assertEquals(1L, status.rateSkipped)
        } finally { release.countDown(); writer.finish(); worker.shutdown(); worker.awaitTermination(3, TimeUnit.SECONDS) }
    }
    @Test fun byteLimitStopsImagesWithoutSavingAnOversizedFile() {
        val worker = Executors.newSingleThreadExecutor(); var calls = 0
        val config = config.copy(sampleBudgetBytes = 6, sampleIntervalMs = 1)
        val writer = SampleWriter(config, worker, { _, _ -> ByteArray(4) },
            object : SampleSink { override fun save(frame: SampleFrame, jpeg: ByteArray) { calls++ } })
        try {
            writer.offer(0) { frame(0, config) }; await { writer.snapshot().saved == 1L }
            writer.offer(2) { frame(2, config) }; await { writer.snapshot().limited }
            assertFalse(writer.offer(3) { error("Limited sampler copied another frame") })
            val status = writer.finish().get(3, TimeUnit.SECONDS)
            assertEquals(1, calls); assertEquals(4L, status.bytes); assertTrue(status.limited); assertFalse(status.failed)
        } finally { writer.finish(); worker.shutdown(); worker.awaitTermination(3, TimeUnit.SECONDS) }
    }
    @Test fun diskFailureDisablesImageRetries() {
        val worker = Executors.newSingleThreadExecutor(); var calls = 0
        val writer = SampleWriter(config, worker, { _, _ -> ByteArray(4) }, object : SampleSink {
            override fun save(frame: SampleFrame, jpeg: ByteArray) { calls++; throw java.io.IOException("Disk unavailable") }
        })
        try {
            writer.offer(0) { frame(0) }; await { writer.snapshot().failed }
            repeat(10) { assertFalse(writer.offer(10000 + it.toLong()) { error("Failed sampler copied") }) }
            assertTrue(writer.finish().get(3, TimeUnit.SECONDS).failed); assertEquals(1, calls)
        } finally { writer.finish(); worker.shutdown(); worker.awaitTermination(3, TimeUnit.SECONDS) }
    }
    @Test fun rejectedWorkerIsVisibleAndCopiesNothing() {
        val writer = SampleWriter(config, { throw RejectedExecutionException() }, { _, _ -> error("encode") },
            object : SampleSink { override fun save(frame: SampleFrame, jpeg: ByteArray) = fail("save") })
        assertFalse(writer.offer(0) { error("copy") })
        assertTrue(writer.finish().get().failed)
    }
}
