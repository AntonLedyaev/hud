package dev.diyhud.domain

import org.junit.Assert.*
import org.junit.Test
import java.io.File

class ConfirmationDebugTest {
    private val root = File(requireNotNull(System.getProperty("hud.repoRoot")))
    private val labels = EventCodec.labels(File(root, "android/app/build/generated/canonical/labels.json").readText())
    private val base = EventCodec.decode(File(root, "shared/fixtures/event.v1.json").readText()).config.copy(
        minimumHits = 3, confirmationWindowMs = 1000, confidenceThreshold = .5,
        rearmAbsenceMs = 2000, associationIou = .5)
    private val sign = Detection(labels.classes.single { it.name == "speed_limit_30" }.id, .9, Box(.1, .1, .3, .3))
    private fun observation(frame: Long, time: Long, detections: List<Detection> = listOf(sign)) =
        Observation(1, "s", 0, frame, time, "m", labels.sha256, detections)
    private fun tracker(config: EffectiveConfig = base, maxTracks: Int = 128) =
        TemporalConfirmation("s", "m", labels, config, maxTracks)

    @Test fun slowStreamExplainsWindowExpiryAndExperimentalWindowConfirms() {
        val strict = tracker()
        val wider = tracker(base.copy(version = 2, confirmationWindowMs = 3000))
        val saved = mutableListOf<ConfirmationDebug>()
        for ((index, time) in listOf(0L, 700L, 1400L).withIndex()) {
            assertNull(strict.accept(observation(index.toLong(), time)))
            val confirmed = wider.accept(observation(index.toLong(), time))
            if (index == 2) assertNotNull(confirmed) else assertNull(confirmed)
            saved += wider.diagnostics
        }
        assertEquals(listOf(1, 2, 3), saved.map { it.rows.single().hits })
        assertEquals(2, strict.diagnostics.rows.single().hits)
        assertEquals(ConfirmationReason.WINDOW_EXPIRED, strict.diagnostics.rows.single().reason)
        assertEquals(ConfirmationReason.CONFIRMED, wider.diagnostics.rows.single().reason)
        assertNull(wider.accept(observation(3, 2100)))
        assertEquals(ConfirmationReason.ALREADY_CONFIRMED, wider.diagnostics.rows.single().reason)
    }
    @Test fun rejectionMismatchMissAndRearmAreDistinguishable() {
        val tracker = tracker()
        tracker.accept(observation(0, 0, listOf(sign.copy(confidence = .1))))
        assertEquals(ConfirmationReason.BELOW_CONFIDENCE, tracker.diagnostics.rows.single().reason)
        tracker.accept(observation(1, 100))
        val oldId = tracker.diagnostics.rows.single().trackId
        val moved = sign.copy(box = Box(.7, .7, .9, .9))
        tracker.accept(observation(2, 200, listOf(moved)))
        assertEquals(ConfirmationReason.BOX_MISMATCH, tracker.diagnostics.rows.last().reason)
        assertNotEquals(oldId, tracker.diagnostics.rows.last().trackId)
        tracker.accept(observation(3, 300, emptyList()))
        assertTrue(tracker.diagnostics.rows.all { it.reason == ConfirmationReason.NOT_SEEN })
        tracker.accept(observation(4, 1500, emptyList()))
        assertTrue(tracker.diagnostics.rows.all { it.hits == 0 && it.reason == ConfirmationReason.WINDOW_EXPIRED })
        tracker.accept(observation(5, 2500))
        assertTrue(tracker.diagnostics.notes.any { it.contains("Rearmed 2") })
        assertEquals(1, tracker.diagnostics.rows.single().hits)
        tracker.reset(1)
        assertTrue(tracker.diagnostics.rows.isEmpty())
        assertTrue(tracker.diagnostics.notes.single().contains("reset"))
    }
    @Test fun diagnosticsAreBoundedImmutableAndDoNotConsumeExtraHits() {
        val tracker = tracker(maxTracks = 1)
        val repeated = List(500) { sign }
        tracker.accept(observation(0, 0, repeated))
        val snapshot = tracker.diagnostics
        assertEquals(1, snapshot.rows.single().hits)
        assertTrue(snapshot.notes.size <= 8)
        assertNull(tracker.accept(observation(0, 0, repeated)))
        assertEquals(snapshot, tracker.diagnostics)
        tracker.accept(observation(1, 100))
        assertEquals(1, snapshot.rows.single().hits)
        assertEquals(2, tracker.diagnostics.rows.single().hits)
        assertNotNull(tracker.diagnostics.rows.single().matchedIou)
    }
}
