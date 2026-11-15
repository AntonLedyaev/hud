package dev.diyhud

import android.net.Uri
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import dev.diyhud.data.*
import dev.diyhud.domain.*
import dev.diyhud.inference.ModelContract
import dev.diyhud.inference.ModelJson
import dev.diyhud.session.LiveSession
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.io.IOException
import java.util.UUID

/** Only synthetic fixtures in isolated databases/cache folders; never deletes recorded user trips. */
class TripReviewTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val manifest = context.assets.open("model/manifest.json").bufferedReader().use { it.readText() }
    private val model = ModelJson.json.decodeFromString<ModelContract>(manifest)
    private val config = model.config.copy(minimumHits = 1, samplingEnabled = true, sampleBudgetBytes = 100000, sampleIntervalMs = 1000)
    private fun header(id: String) = SessionHeader(id, 1000, "test", model.modelVersion, model.labels, config, manifest, "CPU", "synthetic fixture")
    private fun frame(id: String) = SampleFrame(Observation(1, id, 0, 42, 0, model.modelVersion,
        model.labels.sha256, emptyList()), config, 0, ByteArray(18) { (it * 11).toByte() }, 2, 3, 90)
    private fun fixture(action: (RoomEvidenceStore, File) -> Unit) {
        val folder = File(context.cacheDir, "trip-review-${UUID.randomUUID()}")
        val db = Room.inMemoryDatabaseBuilder(context, EvidenceDatabase::class.java).build()
        try { action(RoomEvidenceStore(db, File(folder, "images")) { 0 }, folder) }
        finally { db.close(); folder.deleteRecursively() }
    }
    private fun seed(store: RoomEvidenceStore, id: String, close: Boolean = true): RestrictionState {
        val header = header(id); store.begin(header)
        val live = LiveSession(id, model.modelVersion, model.labels, config) { store.append(it) }
        val sign = Detection(model.labels.classes.single { it.name == "speed_limit_30" }.id, .99, Box(.1, .1, .4, .4))
        live.result(live.token(), 42, 0, 100, listOf(sign))
        store.saveSample(frame(id), encodeSample(frame(id), 100000))
        live.reset(200); live.tick(300)
        if (close) store.end(id, SessionFooter(2000, "fixture_stop", live.state.sequence,
            EvidenceStatus(attempted = live.state.sequence + 1, stored = live.state.sequence + 1)))
        return live.state
    }
    @Test fun reviewPreservesProvenanceAndExportRoundTripsOnlySelectedImages() = fixture { store, folder ->
        val expected = seed(store, "complete")
        val review = store.review("complete")
        assertTrue(review.replay.complete); assertEquals(expected, review.replay.finalState)
        assertEquals(header("complete"), review.header)
        val transitions = store.reviewEvents("complete").filter { it.event.payload is Payload.Transition }
        assertTrue(transitions.isNotEmpty())
        assertTrue(transitions.all { it.sources.size == 1 && it.sources.single().sequence < it.event.sequence })
        assertFalse(store.reviewEvents("complete").any { it.event.payload is Payload.Tick })
        assertTrue(store.reviewEvents("complete", includeTicks = true).any { it.event.payload is Payload.Tick })
        val none = File(folder, "none.zip")
        none.outputStream().use { store.export("complete", emptySet(), it) }
        val noImages = TripArchive.verify(none)
        assertTrue(noImages.evidenceComplete); assertEquals(0L, noImages.samples.count); assertFalse(noImages.allSamplesIncluded)
        val selected = File(folder, "selected.zip")
        selected.outputStream().use { store.export("complete", setOf(SampleKey(0, 42)), it) }
        val result = TripArchive.verify(selected)
        assertEquals(expected, result.finalState); assertTrue(result.allSamplesIncluded); assertEquals(1L, result.samples.count)
        assertThrows(IllegalArgumentException::class.java) {
            store.export("complete", setOf(SampleKey(0, 99)), java.io.ByteArrayOutputStream())
        }
        val row = review.samples.single()
        assertThrows(IllegalArgumentException::class.java) { store.sampleFile(row.copy(path = "../escape.jpg")) }
    }
    @Test fun interruptedTripIsReviewableButActiveTripIsNot() = fixture { store, _ ->
        seed(store, "active", close = false)
        assertTrue(store.database.evidence().sessionPage(0).isEmpty())
        assertThrows(IllegalStateException::class.java) { store.review("active") }
        assertThrows(IllegalStateException::class.java) { store.export("active", emptySet(), java.io.ByteArrayOutputStream()) }
        assertThrows(IllegalStateException::class.java) { store.deleteTrip("active") }
        // A new store represents process death: no live writer/budget owner remains.
        val restarted = RoomEvidenceStore(store.database, File(context.cacheDir, "unused-${UUID.randomUUID()}")) { 0 }
        restarted.prepareReview()
        val review = restarted.review("active")
        assertEquals("INTERRUPTED", review.replay.session.status); assertFalse(review.replay.complete)
        assertNull(review.replay.session.endedAtEpochMs)
    }
    @Test fun failedDeletionRetainsRetryMetadataAndRetryRemovesOnlyThatTrip() = fixture { store, folder ->
        seed(store, "remove"); seed(store, "keep")
        val removed = store.database.evidence().samples("remove").single()
        val kept = store.database.evidence().samples("keep").single()
        assertFalse(store.deleteTrip("remove") { false }.deleted)
        assertEquals("DELETING", store.database.evidence().session("remove")!!.status)
        assertTrue(store.sampleFile(removed).exists()); assertTrue(store.database.evidence().events("remove").isNotEmpty())
        val reopened = RoomEvidenceStore(store.database, File(folder, "images")) { 0 }
        reopened.prepareReview()
        assertNull(store.database.evidence().session("remove"))
        assertTrue(store.database.evidence().events("remove").isEmpty()); assertTrue(store.database.evidence().samples("remove").isEmpty())
        assertFalse(File(folder, "images/${removed.path}").exists())
        assertTrue(store.sampleFile(kept).exists()); assertTrue(store.review("keep").replay.complete)
        assertTrue(store.deleteTrip("remove").deleted)
    }
    @Test fun deliveryCleansFailedAndCrashLeftExportsAndKeepsSuccessfulCopy() = fixture { store, folder ->
        seed(store, "delivery")
        val preference = "test-trip-export-${UUID.randomUUID()}"
        val preferences = context.getSharedPreferences(preference, 0)
        val delivery = ExportDelivery(context, preference)
        try {
            val failed = File(folder, "failed.zip")
            assertThrows(IOException::class.java) {
                delivery.write(Uri.fromFile(failed)) { output -> output.write(byteArrayOf(1, 2)); throw IOException("injected") }
            }
            assertFalse(failed.exists()); assertFalse(delivery.pending)
            val crash = File(folder, "crash.zip").apply { writeText("partial") }
            assertTrue(preferences.edit().putString("pending", Uri.fromFile(crash).toString()).commit())
            assertTrue(ExportDelivery(context, preference).recover()); assertFalse(crash.exists())
            val success = File(folder, "success.zip")
            delivery.write(Uri.fromFile(success)) { store.export("delivery", setOf(SampleKey(0, 42)), it) }
            assertFalse(delivery.pending); assertTrue(TripArchive.verify(success).evidenceComplete)
            assertTrue(store.deleteTrip("delivery").deleted)
            assertTrue(success.exists()); assertTrue(TripArchive.verify(success).evidenceComplete)
        } finally { preferences.edit().clear().commit() }
    }
}
