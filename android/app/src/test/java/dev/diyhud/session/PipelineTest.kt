package dev.diyhud.session

import dev.diyhud.camera.copyRgba
import dev.diyhud.domain.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class PipelineTest {
    private class Frame(val id: Int) : AutoCloseable {
        var closed = 0
        override fun close() { closed++ }
    }
    @Test fun slowWorkerProcessesOnlyCurrentAndLatestAndClosesEverything() {
        val worker = Executors.newSingleThreadExecutor()
        val entered = CountDownLatch(1); val release = CountDownLatch(1); val finished = CountDownLatch(2)
        val processed = mutableListOf<Int>()
        val processor = LatestFrameProcessor<Frame>(worker, {
            if (it.id == 0) { entered.countDown(); check(release.await(3, TimeUnit.SECONDS)) }
            processed.add(it.id); finished.countDown()
        }, { throw AssertionError(it) })
        val frames = (0..99).map { Frame(it) }
        try {
            processor.offer(frames[0]); assertTrue(entered.await(3, TimeUnit.SECONDS))
            frames.drop(1).forEach(processor::offer)
            assertEquals(98L, processor.dropped)
            release.countDown(); assertTrue(finished.await(3, TimeUnit.SECONDS))
        } finally { release.countDown(); processor.close(); worker.shutdown(); worker.awaitTermination(3, TimeUnit.SECONDS) }
        assertEquals(listOf(0, 99), processed)
        assertTrue(frames.all { it.closed == 1 })
    }
    @Test fun stopReleasesPendingAndRejectsFutureFrames() {
        val tasks = ArrayDeque<Runnable>()
        val processor = LatestFrameProcessor<Frame>({ tasks.add(it) }, { fail("Stopped work executed") }, { throw it })
        val first = Frame(1); val late = Frame(2)
        processor.offer(first); processor.close(); processor.offer(late); tasks.removeFirst().run()
        assertEquals(1, first.closed); assertEquals(1, late.closed)
    }
    @Test fun fatalDetectorClosesCurrentAndPendingExactlyOnce() {
        val tasks = ArrayDeque<Runnable>(); var failures = 0
        lateinit var processor: LatestFrameProcessor<Frame>
        val pending = Frame(2); val current = Frame(1)
        processor = LatestFrameProcessor({ tasks.add(it) }, { processor.offer(pending); error("Engine failure") }, { failures++ })
        processor.offer(current); tasks.removeFirst().run()
        assertEquals(1, failures); assertEquals(1, current.closed); assertEquals(1, pending.closed)
    }
    private val root = File(requireNotNull(System.getProperty("hud.repoRoot")))
    private val labels = EventCodec.labels(File(root, "android/app/build/generated/canonical/labels.json").readText())
    private val config = EventCodec.decode(File(root, "shared/fixtures/event.v1.json").readText()).config
        .copy(minimumHits = 2, confirmationWindowMs = 1000, rearmAbsenceMs = 2000, estimateMaxAgeMs = 1000)
    private val sign = Detection(labels.classes.single { it.name == "speed_limit_80" }.id, .99, Box(.1, .1, .4, .4))
    @Test fun liveReplayMatchesAndTicksDoNotInvalidateSlowCaptureResults() {
        val events = mutableListOf<DomainEvent>()
        val live = LiveSession("s", "m", labels, config, events::add)
        val initial = live.state
        live.result(live.token(), 0, 0, 350, listOf(sign))
        live.tick(600)
        live.result(live.token(), 1, 400, 800, listOf(sign))
        assertEquals(80, live.state.speed.current)
        live.tick(1800)
        assertEquals(Freshness.STALE, live.state.speed.freshness)
        assertEquals(live.state, replay(initial, events, labels).last())
        assertTrue(events.zipWithNext().all { (a, b) -> a.sequence < b.sequence && a.offsetMs <= b.offsetMs })
    }
    @Test fun resetStopAndRestartCannotRestoreOldResults() {
        val live = LiveSession("s", "m", labels, config)
        val old = live.token()
        live.result(old, 0, 0, 100, listOf(sign)); live.reset(150)
        assertFalse(live.result(old, 1, 100, 200, listOf(sign)))
        assertFalse(live.result(live.token(), 1, 100, 200, listOf(sign)))
        live.result(live.token(), 2, 200, 300, listOf(sign))
        assertNull(live.state.speed.current)
        live.stop(); assertFalse(live.result(live.token(), 3, 300, 400, listOf(sign)))
        val next = LiveSession("new", "m", labels, config)
        assertFalse(next.result(old, 4, 400, 500, listOf(sign)))
        assertNull(next.state.speed.current)
    }
    @Test fun rgbaCropRespectsRowStrideChannelOrderAndBufferPosition() {
        val buffer = ByteBuffer.allocate(50)
        buffer.position(2)
        for (y in 0..1) for (x in 0..2) for (c in 0..3) buffer.put(2 + y * 16 + x * 4 + c, (y * 30 + x * 4 + c).toByte())
        assertArrayEquals(byteArrayOf(4, 5, 6, 8, 9, 10, 34, 35, 36, 38, 39, 40), copyRgba(buffer, 16, 4, 1, 0, 2, 2))
    }
    @Test fun metricsBoundMemoryExcludeWarmupAndComputeNearestRank() {
        val metrics = PipelineMetrics(100, 2)
        metrics.record(50, 999.0, 999.0)
        metrics.record(100, 10.0, 20.0); metrics.record(200, 30.0, 40.0); metrics.record(300, 50.0, 60.0)
        val stats = metrics.snapshot(400, 7)
        assertEquals(4L, stats.frames); assertEquals(1L, stats.warmupFrames); assertEquals(2, stats.steadySamples)
        assertEquals(30.0, stats.inferenceP50, 0.0); assertEquals(50.0, stats.inferenceP95, 0.0)
        assertEquals(10.0, stats.fps, .001); assertEquals(7L, stats.dropped)
    }
    @Test fun cameraClockPreservesIntervalsAcrossDifferentOrigins() {
        val clock = CameraClock()
        assertEquals(150L, clock.offsetMs(9_000_000_000, 150))
        assertEquals(183L, clock.offsetMs(9_033_000_000, 999))
        assertThrows(IllegalArgumentException::class.java) { clock.offsetMs(8_000_000_000, 1000) }
    }
}
