package dev.diyhud

import android.graphics.BitmapFactory
import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.test.platform.app.InstrumentationRegistry
import dev.diyhud.data.*
import dev.diyhud.domain.*
import dev.diyhud.inference.ModelContract
import dev.diyhud.inference.ModelJson
import dev.diyhud.session.LiveSession
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class SampleStorageTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    @get:Rule val migrations = MigrationTestHelper(instrumentation, EvidenceDatabase::class.java)
    private val manifest = context.assets.open("model/manifest.json").bufferedReader().use { it.readText() }
    private val model = ModelJson.json.decodeFromString<ModelContract>(manifest)
    private val config = model.config.copy(samplingEnabled = true, sampleBudgetBytes = 100000, sampleIntervalMs = 1000)
    private fun header(id: String, config: EffectiveConfig = this.config) =
        SessionHeader(id, 1000, "test", model.modelVersion, model.labels, config, manifest, "CPU", "synthetic image fixture")
    private fun frame(id: String, time: Long = 0, config: EffectiveConfig = this.config) = SampleFrame(
        Observation(1, id, 0, time, time, model.modelVersion, model.labels.sha256, emptyList()), config,
        time / 1000, ByteArray(18) { (it * 11).toByte() }, 2, 3, 90)
    private fun await(condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (!condition() && System.nanoTime() < deadline) Thread.sleep(10)
        assertTrue(condition())
    }
    @Test fun enabledSamplesAreUprightUnreviewedAndLinkedToTheirObservation() {
        val folder = File(context.cacheDir, "samples-${UUID.randomUUID()}")
        val db = Room.inMemoryDatabaseBuilder(context, EvidenceDatabase::class.java).build()
        val store = RoomEvidenceStore(db, folder) { 0 }
        val header = header("linked"); store.begin(header)
        store.append(DomainEvent(1, header.id, 0, 0, 0, header.modelVersion, model.labels.sha256, config, Payload.Tick))
        store.append(DomainEvent(1, header.id, 0, 1, 1000, header.modelVersion, model.labels.sha256, config, Payload.Tick))
        val worker = Executors.newSingleThreadExecutor()
        val writer = SampleWriter(config, worker, ::encodeSample, object : SampleSink {
            override fun save(frame: SampleFrame, jpeg: ByteArray) = store.saveSample(frame, jpeg)
            override fun report(status: SampleStatus) = store.sampleStatus(header.id, status)
        })
        try {
            writer.offer(0) { frame(header.id) }; await { writer.snapshot().saved == 1L }
            assertFalse(writer.offer(500) { error("Too soon") })
            writer.offer(1000) { frame(header.id, 1000) }; await { writer.snapshot().saved == 2L }
            val status = writer.finish().get(5, TimeUnit.SECONDS)
            store.end(header.id, SessionFooter(3000, "stop", 1, EvidenceStatus(attempted = 2, stored = 2), status))
            val rows = db.evidence().samples(header.id)
            assertEquals(2, rows.size)
            for (row in rows) {
                val observation = Json.decodeFromString<Observation>(row.observationJson)
                assertEquals(row.frameId, observation.frameId); assertEquals(row.captureMs, observation.captureOffsetMs)
                assertEquals(header.id, observation.sessionId); assertEquals(model.labels.sha256, observation.labelsSha256)
                assertEquals(config, Json.decodeFromString<EffectiveConfig>(row.configJson)); assertTrue(row.unreviewed)
                assertTrue(row.sourceSequence in db.evidence().events(header.id).map { it.sequence })
                val bytes = File(folder, row.path).readBytes()
                assertEquals(row.byteCount, bytes.size.toLong()); assertEquals(row.sha256, ModelJson.sha256(bytes))
                val image = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                assertEquals(3, image.width); assertEquals(2, image.height); image.recycle()
            }
            assertFalse(folder.listFiles().orEmpty().any { it.name.endsWith(".partial") })
            assertEquals(status, Json.decodeFromString<SampleStatus>(db.evidence().session(header.id)!!.samplingStatusJson!!))
            assertThrows(SampleByteLimit::class.java) { encodeSample(frame(header.id), 8) }
        } finally {
            writer.finish().get(5, TimeUnit.SECONDS); worker.shutdown(); worker.awaitTermination(5, TimeUnit.SECONDS)
            db.close(); folder.deleteRecursively()
        }
    }
    @Test fun disabledSamplingCreatesNeitherImagesNorSampleRows() {
        val folder = File(context.cacheDir, "disabled-${UUID.randomUUID()}")
        val db = Room.inMemoryDatabaseBuilder(context, EvidenceDatabase::class.java).build()
        val config = config.copy(samplingEnabled = false)
        val writer = SampleWriter(config, { fail("Scheduled disabled sampler") }, ::encodeSample,
            object : SampleSink { override fun save(frame: SampleFrame, jpeg: ByteArray) = fail("Saved disabled sample") })
        try {
            assertFalse(writer.offer(0) { error("Copied disabled frame") })
            writer.finish().get()
            assertTrue(db.evidence().samples("disabled").isEmpty()); assertFalse(folder.exists())
        } finally { db.close(); folder.deleteRecursively() }
    }
    @Test fun restartRemovesPartialAndUncommittedFilesButRetainsCommittedEvidence() {
        val name = "test-samples-${UUID.randomUUID()}.db"
        val folder = File(context.cacheDir, "recover-${UUID.randomUUID()}")
        var db = Room.databaseBuilder(context, EvidenceDatabase::class.java, name).build()
        try {
            val header = header("interrupted")
            val store = RoomEvidenceStore(db, folder) { 0 }
            store.begin(header)
            store.append(DomainEvent(1, header.id, 0, 0, 10, header.modelVersion, model.labels.sha256, config, Payload.Tick))
            store.saveSample(frame(header.id), encodeSample(frame(header.id), 100000))
            val committed = db.evidence().samples(header.id).single().path
            File(folder, "interrupted_0_99.jpg.partial").writeText("partial")
            File(folder, "interrupted_0_100.jpg").writeText("uncommitted")
            db.close()
            db = Room.databaseBuilder(context, EvidenceDatabase::class.java, name).build()
            val reopened = RoomEvidenceStore(db, folder) { 0 }
            assertEquals(1, reopened.recover(5000))
            assertEquals(listOf(committed), folder.listFiles()!!.map { it.name })
            assertEquals(1, db.evidence().samples(header.id).size)
            val replay = reopened.replay(header.id)
            assertEquals("INTERRUPTED", replay.session.status); assertEquals(1L, replay.eventCount); assertFalse(replay.complete)
            assertNull(LiveSession("new", header.modelVersion, model.labels, config).state.speed.current)
        } finally { db.close(); context.deleteDatabase(name); folder.deleteRecursively() }
    }
    @Test fun migrationPreservesVersionOneSessionsAndAddsEmptySamples() {
        val name = "migration-samples-${UUID.randomUUID()}.db"
        try {
            migrations.createDatabase(name, 1).use { db ->
                val legacy = header("legacy", config.copy(samplingEnabled = false, sampleBudgetBytes = 0))
                db.execSQL("INSERT INTO sessions (id, headerJson, startedAtEpochMs, status, incomplete, attempted, stored, dropped, lastAttemptSequence) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    arrayOf<Any>("legacy", Json.encodeToString(legacy), 1000, "OPEN", 0, 0, 0, 0, -1))
                val tick = DomainEvent(1, legacy.id, 0, 0, 10, legacy.modelVersion, model.labels.sha256, legacy.config, Payload.Tick)
                db.execSQL("INSERT INTO events (sessionId, sequence, offsetMs, kind, sourceSequencesJson, json) VALUES (?, ?, ?, ?, ?, ?)",
                    arrayOf<Any>(legacy.id, 0, 10, "tick", "[]", EventCodec.encode(tick)))
            }
            migrations.runMigrationsAndValidate(name, 2, true, RoomEvidenceStore.MIGRATION_1_2).use { db ->
                db.query("SELECT id, samplingStatusJson FROM sessions").use { cursor ->
                    assertTrue(cursor.moveToFirst()); assertEquals("legacy", cursor.getString(0)); assertTrue(cursor.isNull(1))
                }
                db.query("SELECT COUNT(*) FROM samples").use { cursor -> cursor.moveToFirst(); assertEquals(0, cursor.getInt(0)) }
                db.query("SELECT COUNT(*) FROM events WHERE sessionId = 'legacy'").use { cursor -> cursor.moveToFirst(); assertEquals(1, cursor.getInt(0)) }
            }
        } finally { context.deleteDatabase(name) }
    }
}
