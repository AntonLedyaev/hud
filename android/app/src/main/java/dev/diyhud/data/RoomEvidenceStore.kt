package dev.diyhud.data

import android.content.Context
import androidx.room.Room
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import dev.diyhud.domain.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

data class RecordedReplay(val session: SessionEntity, val finalState: RestrictionState,
                          val eventCount: Long, val complete: Boolean)

/** Disk operations run on the writer; never called under the session coordinator lock. */
class RoomEvidenceStore(val database: EvidenceDatabase, private val sampleDirectory: File? = null,
                        private val usedBytes: () -> Long) : EvidenceSink {
    private val dao get() = database.evidence()
    private val json = Json { encodeDefaults = true }
    private var recovered = false
    private val budgets = mutableMapOf<String, Long>()
    @Synchronized fun recover(now: Long = System.currentTimeMillis()): Int {
        if (recovered) return 0
        val count = dao.recover(now)
        sampleDirectory?.takeIf { it.exists() }?.let { directory ->
            val referenced = dao.samplePaths().toHashSet()
            val managed = Regex("[A-Za-z0-9_-]+_[0-9]+_[0-9]+\\.jpg(\\.partial)?")
            for (file in directory.listFiles().orEmpty()) {
                if (managed.matches(file.name) && (file.name.endsWith(".partial") || file.name !in referenced))
                    check(file.delete()) { "Cannot clean interrupted sample" }
            }
        }
        recovered = true
        return count
    }
    private fun reserve(limit: Long, bytes: Long) {
        // Reserve room for SQLite pages/WAL overhead and a final incomplete footer.
        if (usedBytes() + bytes + 131_072 > limit) throw EvidenceBudgetExceeded()
    }
    @Synchronized override fun begin(header: SessionHeader) {
        recover()
        val metadata = json.encodeToString(header)
        reserve(header.config.storageBudgetBytes, metadata.toByteArray().size.toLong())
        dao.insertSession(SessionEntity(header.id, metadata, header.startedAtEpochMs))
        budgets[header.id] = header.config.storageBudgetBytes
    }
    @Synchronized override fun append(event: DomainEvent) {
        val encoded = EventCodec.encode(event)
        reserve(requireNotNull(budgets[event.sessionId]), encoded.toByteArray().size.toLong())
        val payload = event.payload
        val sources = when (payload) {
            is Payload.Transition -> payload.sourceSequences
            is Payload.Conflict -> payload.sourceSequences
            is Payload.Error -> payload.sourceSequences
            else -> emptyList()
        }
        val kind = when (payload) {
            is Payload.ConfirmedBatch -> "confirmed_batch"
            is Payload.Transition -> "transition"
            is Payload.Conflict -> "conflict"
            is Payload.Error -> "error"
            is Payload.Gap -> "gap"
            Payload.Reset -> "reset"
            Payload.Tick -> "tick"
        }
        dao.insertEvent(EventEntity(event.sessionId, event.sequence, event.offsetMs,
            (payload as? Payload.ConfirmedBatch)?.frameId, kind, json.encodeToString(sources), encoded))
    }
    @Synchronized override fun end(id: String, footer: SessionFooter) {
        try {
            val row = requireNotNull(dao.session(id))
            dao.updateSession(row.copy(status = "COMPLETED", endedAtEpochMs = footer.endedAtEpochMs,
                endReason = footer.reason, incomplete = footer.status.incomplete,
                attempted = footer.status.attempted, stored = footer.status.stored,
                dropped = footer.status.dropped, lastAttemptSequence = footer.lastAttemptSequence,
                warning = footer.status.warning, samplingStatusJson = footer.sampling?.let { json.encodeToString(it) }
                    ?: row.samplingStatusJson))
        } finally { budgets.remove(id) }
    }
    @Synchronized fun sampleStatus(id: String, status: SampleStatus) {
        dao.samplingStatus(id, json.encodeToString(status))
    }
    @Synchronized fun saveSample(frame: SampleFrame, jpeg: ByteArray) {
        val observation = frame.observation
        val session = requireNotNull(dao.session(observation.sessionId))
        val header = json.decodeFromString<SessionHeader>(session.headerJson)
        require(session.status == "OPEN" && header.config.samplingEnabled && frame.config == header.config)
        require(observation.modelVersion == header.modelVersion && observation.labelsSha256 == header.labels.sha256)
        require(frame.sourceSequence >= 0)
        val existing = dao.samples(session.id)
        if (jpeg.size.toLong() > header.config.sampleBudgetBytes - existing.sumOf { it.byteCount }) throw SampleByteLimit()
        require(jpeg.isNotEmpty() && observation.sessionId.matches(Regex("[A-Za-z0-9_-]+")))
        require(existing.lastOrNull()?.let { observation.captureOffsetMs - it.captureMs >= header.config.sampleIntervalMs } != false)
        reserve(header.config.storageBudgetBytes, jpeg.size.toLong() + 8192)
        val directory = requireNotNull(sampleDirectory)
        check(directory.isDirectory || directory.mkdirs())
        val name = "${session.id}_${observation.resetGeneration}_${observation.frameId}.jpg"
        val complete = File(directory, name)
        val partial = File(directory, "$name.partial")
        check(!complete.exists() && !partial.exists()) { "Sample already exists" }
        var committed = false
        try {
            FileOutputStream(partial).use { it.write(jpeg); it.fd.sync() }
            check(partial.renameTo(complete)) { "Cannot finalize sample" }
            val hash = MessageDigest.getInstance("SHA-256").digest(jpeg).joinToString("") { "%02x".format(it) }
            dao.insertSample(SampleEntity(session.id, observation.resetGeneration, observation.frameId,
                observation.captureOffsetMs, frame.sourceSequence, name, jpeg.size.toLong(), hash,
                json.encodeToString(observation), json.encodeToString(frame.config)))
            committed = true
        } finally {
            if (!committed) { partial.delete(); complete.delete() }
        }
    }
    /** Paginated fold: incomplete traces never become advertised as complete replays. */
    fun replay(id: String): RecordedReplay {
        val row = requireNotNull(dao.session(id))
        val header = json.decodeFromString<SessionHeader>(row.headerJson)
        var state = RestrictionState(id, header.modelVersion, header.labels.sha256, header.config)
        val reducer = RestrictionReducer(header.labels)
        var count = 0L; var last = -1L
        var complete = row.status == "COMPLETED" && !row.incomplete
        while (true) {
            val page = dao.events(id, last)
            if (page.isEmpty()) break
            for (record in page) {
                val event = EventCodec.decode(record.json)
                if (record.sequence != last + 1 || event.payload is Payload.Gap)
                    state = state.copy(speed = Estimate(), overtaking = Estimate(), stopUntilMs = null,
                        reasons = listOf("evidence_gap"))
                if (record.sequence != last + 1 || event.sequence != record.sequence ||
                    event.offsetMs != record.offsetMs || event.payload is Payload.Gap ||
                    event.sessionId != id || event.modelVersion != header.modelVersion ||
                    event.labelsSha256 != header.labels.sha256 || event.config != header.config) complete = false
                state = reducer.reduce(state, event)
                last = record.sequence; count++
            }
        }
        if (last != row.lastAttemptSequence) complete = false
        return RecordedReplay(row, state, count, complete)
    }
    companion object {
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE sessions ADD COLUMN samplingStatusJson TEXT")
                db.execSQL("CREATE TABLE IF NOT EXISTS samples (sessionId TEXT NOT NULL, generation INTEGER NOT NULL, frameId INTEGER NOT NULL, captureMs INTEGER NOT NULL, sourceSequence INTEGER NOT NULL, path TEXT NOT NULL, byteCount INTEGER NOT NULL, sha256 TEXT NOT NULL, observationJson TEXT NOT NULL, configJson TEXT NOT NULL, unreviewed INTEGER NOT NULL, PRIMARY KEY(sessionId, generation, frameId), FOREIGN KEY(sessionId) REFERENCES sessions(id) ON UPDATE NO ACTION ON DELETE CASCADE)")
            }
        }
        val imageWorker = ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS, ArrayBlockingQueue(2))
        // At most one current and two queued session drains across Activity instances.
        val worker = ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS, ArrayBlockingQueue(2))
        @Volatile private var instance: RoomEvidenceStore? = null
        fun writer(context: Context, header: SessionHeader) = EvidenceWriter(header, worker, object : EvidenceSink {
            override fun begin(header: SessionHeader) = get(context).begin(header)
            override fun append(event: DomainEvent) = get(context).append(event)
            override fun end(id: String, footer: SessionFooter) = get(context).end(id, footer)
        })
        fun sampler(context: Context, writer: EvidenceWriter) = SampleWriter(writer.header.config, imageWorker, ::encodeSample,
            object : SampleSink {
                private fun store(): RoomEvidenceStore {
                    check(writer.started.get()) { "Session metadata unavailable; image saving disabled" }
                    return get(context)
                }
                override fun save(frame: SampleFrame, jpeg: ByteArray) = store().saveSample(frame, jpeg)
                override fun report(status: SampleStatus) = store().sampleStatus(writer.header.id, status)
            })
        fun get(context: Context): RoomEvidenceStore = instance ?: synchronized(this) {
            instance ?: run {
                val app = context.applicationContext
                val name = "hud-evidence.db"
                val db = Room.databaseBuilder(app, EvidenceDatabase::class.java, name).addMigrations(MIGRATION_1_2).build()
                val path = app.getDatabasePath(name)
                val samples = File(app.filesDir, "evidence-samples")
                RoomEvidenceStore(db, samples) {
                    listOf(path, File(path.path + "-wal"), File(path.path + "-shm")).sumOf { it.length() } +
                        samples.listFiles().orEmpty().sumOf { it.length() }
                }.also { instance = it }
            }
        }
    }
}
