package dev.diyhud

import android.app.Application
import android.net.Uri
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import dev.diyhud.data.*
import dev.diyhud.domain.*
import dev.diyhud.inference.ModelContract
import dev.diyhud.inference.ModelJson
import dev.diyhud.session.HudViewModel
import dev.diyhud.session.Phase
import dev.diyhud.ui.MainActivity
import dev.diyhud.ui.TripsViewModel
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit

class TripReviewUiTest {
    @Test fun reviewPreviewSelectionExportAndDeletionUseOnlySyntheticTrip() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val app = instrumentation.targetContext.applicationContext as Application
        val store = RoomEvidenceStore.get(app)
        val manifest = app.assets.open("model/manifest.json").bufferedReader().use { it.readText() }
        val model = ModelJson.json.decodeFromString<ModelContract>(manifest)
        val config = model.config.copy(samplingEnabled = true, sampleBudgetBytes = 100000, sampleIntervalMs = 1000)
        val id = "review-test-${UUID.randomUUID()}"
        val output = File(app.cacheDir, "$id.zip")
        val header = SessionHeader(id, System.currentTimeMillis(), "test", model.modelVersion, model.labels, config, manifest, "CPU", "synthetic fixture")
        val observation = Observation(1, id, 0, 42, 0, model.modelVersion, model.labels.sha256, emptyList())
        val frame = SampleFrame(observation, config, 0, ByteArray(18) { (it * 11).toByte() }, 2, 3, 90)
        store.begin(header)
        store.append(DomainEvent(1, id, 0, 0, 0, model.modelVersion, model.labels.sha256, config, Payload.Tick))
        store.saveSample(frame, encodeSample(frame, 100000))
        store.end(id, SessionFooter(System.currentTimeMillis(), "test", 0, EvidenceStatus(attempted = 1, stored = 1)))
        lateinit var trips: TripsViewModel
        lateinit var hud: HudViewModel
        fun await(condition: () -> Boolean) {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
            while (!condition() && System.nanoTime() < deadline) Thread.sleep(20)
            assertTrue(trips.ui.value.toString(), condition())
        }
        try {
            ActivityScenario.launch(MainActivity::class.java).use { activity ->
                activity.onActivity {
                    trips = it.trips; hud = it.coordinator
                    assertTrue(hud.openReview()); trips.list()
                    hud.start(); assertEquals(Phase.READY, hud.ui.value.phase)
                }
                await { !trips.ui.value.busy }
                activity.onActivity { trips.open(id) }
                await { !trips.ui.value.busy && trips.ui.value.detail?.header?.id == id }
                assertTrue(trips.ui.value.selected.isEmpty()); assertTrue(trips.ui.value.detail!!.replay.complete)
                val sample = trips.ui.value.detail!!.samples.single()
                activity.onActivity { trips.preview(sample) }
                await { !trips.ui.value.busy && trips.ui.value.preview != null }
                assertEquals(3, trips.ui.value.preview!!.width); assertEquals(2, trips.ui.value.preview!!.height)
                activity.onActivity { trips.closePreview(); trips.select(SampleKey(0, 42), true) }
                activity.recreate()
                activity.onActivity {
                    assertSame(trips, it.trips); assertTrue(it.coordinator.ui.value.reviewOpen)
                    assertNotNull(trips.requestExport()); trips.export(Uri.fromFile(output))
                }
                await { !trips.ui.value.busy }
                assertEquals(1L, TripArchive.verify(output).samples.count)
                activity.onActivity { trips.delete(id) }
                await { !trips.ui.value.busy && trips.ui.value.detail == null }
                assertNull(store.database.evidence().session(id)); assertTrue(output.exists())
                assertTrue(trips.ui.value.message!!.contains("Exported copies"))
                activity.onActivity { hud.closeReview() }
                assertFalse(hud.ui.value.reviewOpen)
            }
        } finally { store.deleteTrip(id); output.delete() }
    }
}
