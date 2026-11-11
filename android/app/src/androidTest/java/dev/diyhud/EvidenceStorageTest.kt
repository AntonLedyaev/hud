package dev.diyhud

import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import dev.diyhud.data.*
import dev.diyhud.domain.*
import dev.diyhud.session.LiveSession
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class EvidenceStorageTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val labels = EventCodec.labels(context.assets.open("labels.json").bufferedReader().use { it.readText() })
    private val manifest = context.assets.open("model/manifest.json").bufferedReader().use { it.readText() }
    private val model = dev.diyhud.inference.ModelJson.json.decodeFromString<dev.diyhud.inference.ModelContract>(manifest)
    private val config = model.config.copy(minimumHits = 1, eventQueueCapacity = 64, estimateMaxAgeMs = 1000, stopDisplayMs = 500)
    private fun header(id: String) = SessionHeader(id, 1000, "test", model.modelVersion, labels, config, manifest, "CPU", "Pixel fixture")
    private fun sign(name: String) = Detection(labels.classes.single { it.name == name }.id, .99, Box(.1, .1, .4, .4))
    @Test fun completeTripStoresAllInputAndAuditKindsAndReplaysExactly() {
        val db = Room.inMemoryDatabaseBuilder(context, EvidenceDatabase::class.java).build()
        val store = RoomEvidenceStore(db) { 0 }
        val worker = Executors.newSingleThreadExecutor()
        val header = header("complete"); val writer = EvidenceWriter(header, worker, store)
        val live = LiveSession(header.id, header.modelVersion, labels, config) { writer.offer(it) }
        try {
            live.result(live.token(), 0, 0, 100, listOf(sign("speed_limit_30")))
            live.reset(150)
            live.result(live.token(), 1, 200, 250, listOf(sign("speed_limit_30"), sign("speed_limit_50")))
            live.reset(300)
            live.result(live.token(), 2, 400, 450, listOf(sign("speed_limit_30"), sign("no_overtaking"), sign("stop")))
            live.tick(1500)
            live.error(1600, "fixture_error", "Recoverable diagnostic fixture")
            val expected = live.state
            val status = writer.finish("user_stop", 3000).get(5, TimeUnit.SECONDS)
            assertFalse(status.incomplete)
            val rows = db.evidence().events(header.id)
            assertTrue(rows.map { it.kind }.containsAll(listOf("confirmed_batch", "conflict", "reset", "tick", "transition", "error")))
            assertEquals(rows.indices.map { it.toLong() }, rows.map { it.sequence })
            val bySequence = rows.associateBy { it.sequence }
            for (row in rows) {
                val sources = Json.decodeFromString<List<Long>>(row.sourceSequencesJson)
                assertTrue(sources.all { it < row.sequence && it in bySequence })
                val audit = when (val payload = EventCodec.decode(row.json).payload) {
                    is Payload.Transition -> payload.reason to payload.details
                    is Payload.Conflict -> payload.reason to payload.details
                    else -> null
                }
                if (audit != null) {
                    val snapshot = Json.decodeFromString<RestrictionState>(audit.second)
                    assertEquals(header.modelVersion, snapshot.modelVersion)
                    assertEquals(header.labels.sha256, snapshot.labelsSha256)
                    assertEquals(header.config, snapshot.config)
                    assertTrue(audit.first in snapshot.reasons)
                    assertEquals(1, sources.size)
                    val source = EventCodec.decode(bySequence.getValue(sources.single()).json)
                    assertFalse(source.payload is Payload.Transition || source.payload is Payload.Conflict)
                    android.util.Log.i("EvidenceReplayExample", "${row.sequence} <- ${sources.single()}: ${audit.first}; model=${header.modelVersion}; config=${header.config.version}")
                }
            }
            val replay = store.replay(header.id)
            assertTrue(replay.complete); assertEquals(expected, replay.finalState)
            assertEquals("user_stop", replay.session.endReason)
            val storedHeader = Json.decodeFromString<SessionHeader>(replay.session.headerJson)
            assertEquals(header, storedHeader)
        } finally { writer.finish("cleanup", 3000).get(5, TimeUnit.SECONDS); worker.shutdown(); worker.awaitTermination(5, TimeUnit.SECONDS); db.close() }
    }
    @Test fun committedEventsSurviveReopenAndOpenTripBecomesInterrupted() {
        val name = "test-evidence-${UUID.randomUUID()}.db"
        var db = Room.databaseBuilder(context, EvidenceDatabase::class.java, name).build()
        try {
            val header = header("interrupted")
            RoomEvidenceStore(db) { 0 }.apply {
                begin(header)
                append(DomainEvent(1, header.id, 0, 0, 10, header.modelVersion, labels.sha256, config, Payload.Tick))
            }
            db.close()
            db = Room.databaseBuilder(context, EvidenceDatabase::class.java, name).build()
            val reopened = RoomEvidenceStore(db) { 0 }
            assertEquals(1, reopened.recover(5000))
            assertEquals(0, reopened.recover(6000))
            val replay = reopened.replay(header.id)
            assertEquals("INTERRUPTED", replay.session.status)
            assertEquals(5000L, replay.session.recoveredAtEpochMs)
            assertNull(replay.session.endedAtEpochMs)
            assertEquals(1L, replay.eventCount); assertFalse(replay.complete)
            assertNull(LiveSession("new", header.modelVersion, labels, config).state.speed.current)
        } finally { db.close(); context.deleteDatabase(name) }
    }
    @Test fun budgetStopsNewEventsPreservesExistingEvidenceAndMarksIncomplete() {
        val db = Room.inMemoryDatabaseBuilder(context, EvidenceDatabase::class.java).build()
        var used = 0L
        val store = RoomEvidenceStore(db) { used }
        val header = header("full")
        try {
            store.begin(header)
            val event = DomainEvent(1, header.id, 0, 0, 10, header.modelVersion, labels.sha256, config, Payload.Tick)
            store.append(event)
            used = config.storageBudgetBytes
            assertThrows(EvidenceBudgetExceeded::class.java) { store.append(event.copy(sequence = 1, offsetMs = 20)) }
            store.end(header.id, SessionFooter(3000, "stop", 1,
                EvidenceStatus(attempted = 2, stored = 1, dropped = 1, disabled = true, warning = "budget")))
            val replay = store.replay(header.id)
            assertEquals(1L, replay.eventCount); assertFalse(replay.complete)
            assertEquals(1L, replay.session.dropped)
            assertEquals(1, db.evidence().sessions().size)
        } finally { db.close() }
    }
    @Test fun explicitGapsCannotBeAdvertisedAsCompleteReplay() {
        val db = Room.inMemoryDatabaseBuilder(context, EvidenceDatabase::class.java).build()
        val store = RoomEvidenceStore(db) { 0 }
        val header = header("gap")
        try {
            store.begin(header)
            val event = DomainEvent(1, header.id, 0, 0, 10, header.modelVersion, labels.sha256, config, Payload.Tick)
            store.append(event)
            store.append(event.copy(sequence = 1, offsetMs = 20, payload = Payload.Gap(2)))
            store.append(event.copy(sequence = 3, offsetMs = 30))
            store.end(header.id, SessionFooter(3000, "stop", 3, EvidenceStatus(attempted = 4, stored = 3)))
            val replay = store.replay(header.id)
            assertFalse(replay.complete); assertEquals(3L, replay.eventCount)
            assertEquals("COMPLETED", replay.session.status)
        } finally { db.close() }
    }
}
