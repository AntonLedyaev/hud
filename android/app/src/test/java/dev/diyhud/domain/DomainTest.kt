package dev.diyhud.domain

import java.io.File
import org.junit.Assert.*
import org.junit.Test

class DomainTest {
    private val root = File(requireNotNull(System.getProperty("hud.repoRoot")))
    private val labels = EventCodec.labels(File(root, "android/app/build/generated/canonical/labels.json").readText())
    private val sample = EventCodec.decode(File(root, "shared/fixtures/event.v1.json").readText())
    private val config = sample.config.copy(minimumHits = 3, confidenceThreshold = 0.7,
        confirmationWindowMs = 500, rearmAbsenceMs = 1000, estimateMaxAgeMs = 5000,
        stopDisplayMs = 500, associationIou = 0.5)
    private val initial = RestrictionState("trip", "model", labels.sha256, config)
    private val reducer = RestrictionReducer(labels)
    private fun sign(name: String, confidence: Double = 0.9, box: Box = Box(0.1, 0.1, 0.3, 0.3)) =
        Detection(labels.classes.single { it.name == name }.id, confidence, box)
    private fun event(sequence: Long, time: Long, payload: Payload, generation: Long = 0) =
        DomainEvent(1, "trip", generation, sequence, time, "model", labels.sha256, config, payload)
    private fun batch(sequence: Long, time: Long, vararg names: String) =
        event(sequence, time, Payload.ConfirmedBatch(sequence, names.map { sign(it) }))
    private fun observation(frame: Long, time: Long, detections: List<Detection>, generation: Long = 0) =
        Observation(1, "trip", generation, frame, time, "model", labels.sha256, detections)
    private fun confirmation() = TemporalConfirmation("trip", "model", labels, config)

    @Test fun eventAndObservationRoundtripSharedFixtures() {
        assertEquals(sample, EventCodec.decode(EventCodec.encode(sample)))
        val fixture = File(root, "shared/fixtures/observation.v1.json").readText()
        assertEquals(1, EventCodec.observation(fixture).schemaVersion)
        val events = listOf(batch(1, 100, "speed_limit_80"), event(2, 200, Payload.Tick))
        val decoded = events.map { EventCodec.decode(EventCodec.encode(it)) }
        assertEquals(events, decoded)
        assertEquals(config, decoded.first().config)
        assertEquals(replay(initial, events, labels), replay(initial, decoded, labels))
    }

    @Test fun unknownAtStartAndReplacement() {
        assertNull(initial.speed.current)
        var state = reducer.reduce(initial, batch(1, 100, "speed_limit_80"))
        assertEquals(80, state.speed.current)
        state = reducer.reduce(state, batch(2, 200, "speed_limit_50"))
        assertEquals(50, state.speed.current)
        assertEquals(2L, state.speed.sourceSequence)
    }

    @Test fun matchingAndNonmatchingCancellation() {
        for (limit in listOf(50, 80)) {
            var state = reducer.reduce(initial, batch(1, 100, "speed_limit_$limit"))
            state = reducer.reduce(state, batch(2, 200, "end_speed_limit_80"))
            assertEquals(if (limit == 80) null else 50, state.speed.current)
        }
        assertNull(reducer.reduce(initial, batch(1, 100, "end_speed_limit_80")).speed.current)
    }

    @Test fun independentOvertakingAndStop() {
        var state = reducer.reduce(initial, batch(1, 100, "speed_limit_50", "no_overtaking", "stop"))
        assertEquals(true, state.overtaking.current)
        assertEquals(600L, state.stopUntilMs)
        state = reducer.reduce(state, batch(2, 200, "end_no_overtaking"))
        assertNull(state.overtaking.current)
        assertEquals(50, state.speed.current)
        state = reducer.reduce(state, event(3, 600, Payload.Tick))
        assertNull(state.stopUntilMs)
        assertEquals(50, state.speed.current)
    }

    @Test fun endAllClearsBothWithoutInferringDefault() {
        var state = reducer.reduce(initial, batch(1, 100, "speed_limit_80", "no_overtaking"))
        state = reducer.reduce(state, batch(2, 200, "end_all_restrictions"))
        assertNull(state.speed.current)
        assertNull(state.overtaking.current)
    }

    @Test fun conflictsInvalidateOnlyAffectedField() {
        var state = reducer.reduce(initial, batch(1, 100, "no_overtaking"))
        state = reducer.reduce(state, batch(2, 200, "speed_limit_50", "speed_limit_80"))
        assertNull(state.speed.current)
        assertEquals(true, state.overtaking.current)
        assertTrue("speed_conflict" in state.reasons)
        state = reducer.reduce(state, batch(3, 300, "no_overtaking", "end_no_overtaking"))
        assertNull(state.overtaking.current)
        assertTrue("overtaking_conflict" in state.reasons)
    }

    @Test fun noImmediateCancellationAndExactExpiry() {
        val state = reducer.reduce(initial, batch(1, 100, "speed_limit_80"))
        assertEquals(80, reducer.reduce(state, event(2, 5099, Payload.Tick)).speed.current)
        val stale = reducer.reduce(state, event(2, 5100, Payload.Tick))
        assertNull(stale.speed.current)
        assertEquals(80, stale.speed.value)
        assertEquals(Freshness.STALE, stale.speed.freshness)
        assertEquals(1L, stale.speed.sourceSequence)
    }

    @Test fun oldResultsCannotUndoResetOrNewSession() {
        var state = reducer.reduce(initial, batch(1, 100, "speed_limit_80"))
        state = reducer.reduce(state, event(2, 200, Payload.Reset, 1))
        assertNull(state.speed.current)
        assertEquals(state, reducer.reduce(state, batch(3, 300, "speed_limit_50")))
        assertEquals(state, reducer.reduce(state, batch(3, 300, "speed_limit_50").copy(sessionId = "old")))
        val valid = batch(3, 300, "speed_limit_50").copy(resetGeneration = 1)
        assertEquals(50, reducer.reduce(state, valid).speed.current)
    }

    @Test fun orderingAndIdentityChecks() {
        val first = batch(2, 200, "speed_limit_80")
        val state = reducer.reduce(initial, first)
        val next = batch(3, 300, "speed_limit_50")
        for (invalid in listOf(first, next.copy(offsetMs = 199), next.copy(modelVersion = "old"),
            next.copy(labelsSha256 = "0".repeat(64)), next.copy(config = config.copy(minimumHits = 4)),
            next.copy(payload = Payload.ConfirmedBatch(2, listOf(sign("speed_limit_50")))))) {
            assertEquals(state, reducer.reduce(state, invalid))
        }
    }

    @Test fun unsupportedAndLowConfidenceDoNotChangeRestriction() {
        val signs = listOf(sign("speed_limit_80", 0.1), Detection(999, 1.0, Box(0.1, 0.1, 0.3, 0.3)))
        assertNull(reducer.reduce(initial, event(1, 100, Payload.ConfirmedBatch(1, signs))).speed.current)
    }

    @Test fun confirmationRequiresDistinctFramesAndFiltersNoise() {
        val tracker = confirmation()
        val signs = listOf(sign("speed_limit_50"), sign("speed_limit_50"))
        assertNull(tracker.accept(observation(1, 100, signs)))
        assertNull(tracker.accept(observation(1, 100, signs)))
        assertNull(tracker.accept(observation(2, 200, signs)))
        assertEquals(1, tracker.accept(observation(3, 300, signs))!!.signs.size)
        assertNull(tracker.accept(observation(4, 400, signs)))
    }

    @Test fun rearmingAndWindowBoundaries() {
        val tracker = confirmation()
        val signs = listOf(sign("speed_limit_50"))
        for (frame in 1L..3L) tracker.accept(observation(frame, frame * 100, signs))
        assertNull(tracker.accept(observation(4, 1300, signs)))
        assertNull(tracker.accept(observation(5, 1400, signs)))
        assertNotNull(tracker.accept(observation(6, 1500, signs)))
        val sparse = confirmation()
        for (frame in 1L..10L) assertNull(sparse.accept(observation(frame, frame * 600, signs)))
    }

    @Test fun spatiallyDifferentSignsDoNotShareHits() {
        val tracker = confirmation()
        assertNull(tracker.accept(observation(1, 100, listOf(sign("speed_limit_50")))))
        assertNull(tracker.accept(observation(2, 200,
            listOf(sign("speed_limit_50", box = Box(0.7, 0.7, 0.9, 0.9))))))
        assertNull(tracker.accept(observation(3, 300, listOf(sign("speed_limit_50", confidence = 0.1)))))
    }

    @Test fun resetClearsConfirmationAndRejectsLateFrames() {
        val tracker = confirmation()
        val signs = listOf(sign("speed_limit_50"))
        tracker.accept(observation(1, 100, signs))
        tracker.accept(observation(2, 200, signs))
        tracker.reset(1)
        assertNull(tracker.accept(observation(3, 300, signs)))
        assertNull(tracker.accept(observation(3, 300, signs, 1)))
        assertNull(tracker.accept(observation(4, 400, signs, 1)))
        assertNotNull(tracker.accept(observation(5, 500, signs, 1)))
    }

    @Test fun debugReplayWithoutCameraIsDeterministic() {
        val events = listOf(batch(1, 100, "speed_limit_80"), batch(2, 200, "no_overtaking"),
            event(3, 5100, Payload.Tick), event(4, 5200, Payload.Reset, 1))
        val result = replay(initial, events, labels)
        assertEquals(result, replay(initial, events.map { EventCodec.decode(EventCodec.encode(it)) }, labels))
        println(result.joinToString("\n") { "${it.offsetMs}: speed=${it.speed.current}, reasons=${it.reasons}" })
        val output = File(root, "android/app/build/reports/domain/events.jsonl")
        requireNotNull(output.parentFile).mkdirs()
        output.writeText(events.joinToString("\n", postfix = "\n") { EventCodec.encode(it) })
    }

    @Test fun boundedTrackerAndIdentityGuards() {
        val tracker = TemporalConfirmation("trip", "model", labels, config, maxTracks = 1)
        val first = observation(1, 100, listOf(sign("speed_limit_50")))
        tracker.accept(first)
        assertNull(tracker.accept(first.copy(frameId = 2, captureOffsetMs = 200, sessionId = "old")))
        assertNull(tracker.accept(first.copy(frameId = 2, captureOffsetMs = 90)))
        assertNull(tracker.accept(first.copy(frameId = 2, captureOffsetMs = 200,
            labelsSha256 = "0".repeat(64))))
        assertNull(tracker.accept(observation(2, 200, listOf(sign("speed_limit_80")))))
        assertNull(tracker.accept(observation(3, 300, listOf(sign("speed_limit_50")))))
        assertEquals(sign("speed_limit_50").classId,
            tracker.accept(observation(4, 400, listOf(sign("speed_limit_50"))))!!.signs.single().classId)
    }

    @Test fun cancellationConflictAndErrors() {
        val state = reducer.reduce(initial, batch(1, 100, "speed_limit_80"))
        val conflict = reducer.reduce(state, batch(2, 200, "speed_limit_50", "end_speed_limit_80"))
        assertNull(conflict.speed.current)
        assertTrue("speed_conflict" in conflict.reasons)
        val gap = reducer.reduce(state, event(2, 200, Payload.Gap(2)))
        assertNull(gap.speed.current)
        assertEquals(listOf("evidence_gap"), gap.reasons)
    }

    @Test fun invalidContractsFailEarly() {
        for (value in listOf(-0.1, 1.1, Double.NaN)) {
            assertThrows(IllegalArgumentException::class.java) { sign("stop", value) }
        }
        assertThrows(IllegalArgumentException::class.java) { Box(0.5, 0.1, 0.4, 0.3) }
        assertThrows(IllegalArgumentException::class.java) {
            EventCodec.decode(EventCodec.encode(sample).replace("\"schema_version\":1", "\"schema_version\":2"))
        }
    }
}
