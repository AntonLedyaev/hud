package dev.diyhud

import android.Manifest
import android.app.Application
import androidx.lifecycle.ViewModelStore
import androidx.test.platform.app.InstrumentationRegistry
import dev.diyhud.camera.RgbFrame
import dev.diyhud.data.RoomEvidenceStore
import dev.diyhud.domain.Observation
import dev.diyhud.session.HudViewModel
import dev.diyhud.session.Phase
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.concurrent.TimeUnit

/** Exercise the full model/session path with synthetic RGB, without sampling the user's surroundings. */
class SampleSessionTest {
    @Test fun explicitOptInPersistsFrameAndDoesNotCarryIntoNextSession() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.uiAutomation.grantRuntimePermission("dev.diyhud", Manifest.permission.CAMERA)
        val app = instrumentation.targetContext.applicationContext as Application
        val model = HudViewModel(app)
        val models = ViewModelStore().apply { put("sampling", model) }
        val ids = mutableListOf<String>()
        fun await(condition: () -> Boolean) {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15)
            while (!condition() && System.nanoTime() < deadline) Thread.sleep(20)
            assertTrue("${model.ui.value}", condition())
        }
        try {
            assertFalse(model.ui.value.samplingEnabled)
            model.sampleMode(true); model.start()
            await { model.ui.value.phase == Phase.ACTIVE }
            val token = model.token()!!; ids += token.sessionId
            model.offer(RgbFrame(token, 42, 0, ByteArray(18) { (it * 11).toByte() }, 2, 3, 90))
            await { model.ui.value.samples?.saved == 1L }
            model.stop(); await { model.ui.value.phase == Phase.READY }
            assertFalse(model.ui.value.samplingEnabled)
            val store = RoomEvidenceStore.get(app)
            val sample = store.database.evidence().samples(token.sessionId).single()
            assertEquals(42L, sample.frameId); assertTrue(sample.unreviewed)
            assertEquals(token.sessionId, Json.decodeFromString<Observation>(sample.observationJson).sessionId)
            assertTrue(store.replay(token.sessionId).complete)
            assertTrue(sample.sourceSequence in store.database.evidence().events(token.sessionId).map { it.sequence })
            model.start(); await { model.ui.value.phase == Phase.ACTIVE }
            val next = model.token()!!; ids += next.sessionId
            model.offer(RgbFrame(next, 42, 0, ByteArray(18), 2, 3, 0))
            await { model.ui.value.stats.frames >= 1 }
            model.stop(); await { model.ui.value.phase == Phase.READY }
            assertFalse(model.ui.value.samples!!.enabled)
            assertTrue(store.database.evidence().samples(next.sessionId).isEmpty())
        } finally {
            model.stop(); await { model.ui.value.phase in listOf(Phase.READY, Phase.ERROR) }; models.clear()
            val store = RoomEvidenceStore.get(app)
            for (id in ids) {
                for (row in store.database.evidence().samples(id)) File(File(app.filesDir, "evidence-samples"), row.path).delete()
                store.database.openHelper.writableDatabase.execSQL("DELETE FROM sessions WHERE id = ?", arrayOf(id))
            }
        }
    }
}
