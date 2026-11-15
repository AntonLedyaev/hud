package dev.diyhud.data

import androidx.room.*

@Entity(tableName = "sessions")
data class SessionEntity(
    @PrimaryKey val id: String, val headerJson: String, val startedAtEpochMs: Long,
    val status: String = "OPEN", val endedAtEpochMs: Long? = null, val recoveredAtEpochMs: Long? = null,
    val endReason: String? = null, val incomplete: Boolean = false,
    val attempted: Long = 0, val stored: Long = 0, val dropped: Long = 0,
    val lastAttemptSequence: Long = -1, val warning: String? = null,
    val samplingStatusJson: String? = null,
)
@Entity(tableName = "events", primaryKeys = ["sessionId", "sequence"],
    foreignKeys = [ForeignKey(entity = SessionEntity::class, parentColumns = ["id"],
        childColumns = ["sessionId"], onDelete = ForeignKey.CASCADE)])
data class EventEntity(
    val sessionId: String, val sequence: Long, val offsetMs: Long, val frameId: Long?,
    val kind: String, val sourceSequencesJson: String, val json: String,
)
@Entity(tableName = "samples", primaryKeys = ["sessionId", "generation", "frameId"],
    foreignKeys = [ForeignKey(entity = SessionEntity::class, parentColumns = ["id"],
        childColumns = ["sessionId"], onDelete = ForeignKey.CASCADE)])
data class SampleEntity(
    val sessionId: String, val generation: Long, val frameId: Long, val captureMs: Long,
    val sourceSequence: Long, val path: String, val byteCount: Long, val sha256: String,
    val observationJson: String, val configJson: String, val unreviewed: Boolean = true,
)
@Dao interface EvidenceDao {
    @Insert fun insertSession(row: SessionEntity)
    @Insert fun insertEvent(row: EventEntity)
    @Insert fun insertSample(row: SampleEntity)
    @Query("SELECT * FROM samples WHERE sessionId = :id ORDER BY captureMs, generation, frameId")
    fun samples(id: String): List<SampleEntity>
    @Query("SELECT path FROM samples") fun samplePaths(): List<String>
    @Query("UPDATE sessions SET samplingStatusJson = :status WHERE id = :id")
    fun samplingStatus(id: String, status: String)
    @Update fun updateSession(row: SessionEntity)
    @Query("SELECT * FROM sessions WHERE id = :id") fun session(id: String): SessionEntity?
    @Query("SELECT * FROM sessions ORDER BY startedAtEpochMs DESC") fun sessions(): List<SessionEntity>
    @Query("SELECT * FROM sessions WHERE status != 'OPEN' ORDER BY startedAtEpochMs DESC, id LIMIT :limit OFFSET :offset")
    fun sessionPage(offset: Int, limit: Int = 50): List<SessionEntity>
    @Query("DELETE FROM sessions WHERE id = :id") fun deleteSession(id: String)
    @Query("SELECT * FROM events WHERE sessionId = :id AND sequence > :after ORDER BY sequence LIMIT :limit")
    fun events(id: String, after: Long = -1, limit: Int = 256): List<EventEntity>
    @Query("SELECT * FROM events WHERE sessionId = :id AND sequence = :sequence")
    fun event(id: String, sequence: Long): EventEntity?
    @Query("SELECT * FROM events WHERE sessionId = :id AND sequence > :after AND (:includeTicks OR kind != 'tick') ORDER BY sequence LIMIT 100")
    fun reviewEvents(id: String, after: Long, includeTicks: Boolean): List<EventEntity>
    @Query("UPDATE sessions SET status = 'INTERRUPTED', endReason = 'process_interrupted', incomplete = 1, recoveredAtEpochMs = :now WHERE status = 'OPEN'")
    fun recover(now: Long): Int
}
@Database(entities = [SessionEntity::class, EventEntity::class, SampleEntity::class], version = 2, exportSchema = true)
abstract class EvidenceDatabase : RoomDatabase() { abstract fun evidence(): EvidenceDao }
