package dev.diyhud

import android.Manifest
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.diyhud.session.*
import dev.diyhud.ui.MainActivity
import org.junit.Assert.*
import org.junit.Test
import org.junit.Before
import org.junit.runner.RunWith
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class ForegroundSessionTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    @Before fun requireUnlockedDevice() {
        val context = instrumentation.targetContext
        assertTrue("Unlock the reference phone before camera tests", context.getSystemService(android.os.PowerManager::class.java).isInteractive &&
            !context.getSystemService(android.app.KeyguardManager::class.java).isKeyguardLocked)
    }
    private var snapshot: () -> String = { "No coordinator" }
    private fun await(message: String, condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(25)
        while (!condition() && System.nanoTime() < deadline) Thread.sleep(100)
        assertTrue("$message\nFinal state: ${snapshot()}", condition())
    }
    @Test fun missingSelectedCameraStopsAndClearsSession() {
        instrumentation.uiAutomation.grantRuntimePermission("dev.diyhud", Manifest.permission.CAMERA)
        ActivityScenario.launch(MainActivity::class.java).use { activity ->
            lateinit var model: HudViewModel
            activity.onActivity { model = it.coordinator; snapshot = { model.ui.value.toString() }; model.start() }
            await("Engine ready") { model.ui.value.phase == Phase.ACTIVE }
            lateinit var binding: dev.diyhud.camera.CameraBinding
            activity.onActivity {
                val missing = androidx.camera.core.CameraSelector.Builder().addCameraFilter { emptyList() }.build()
                binding = dev.diyhud.camera.CameraBinding(it, it, null, 0, model, missing)
            }
            try {
                await("Unavailable camera must close session") { model.ui.value.phase == Phase.ERROR }
                assertNull(model.token()); assertNull(model.ui.value.restriction)
            } finally { activity.onActivity { binding.close() } }
        }
    }
    @Test fun cameraResetBackgroundRestartAndFailureRecovery() {
        instrumentation.uiAutomation.grantRuntimePermission("dev.diyhud", Manifest.permission.CAMERA)
        ActivityScenario.launch(MainActivity::class.java).use { activity ->
            lateinit var model: HudViewModel
            activity.onActivity { model = it.coordinator; snapshot = { model.ui.value.toString() } }
            repeat(3) { index ->
                activity.onActivity { model.diagnosticMode(index == 1); model.start() }
                await("Camera must produce real inference frames: ${model.ui.value}") { model.ui.value.stats.frames >= 3 }
                val token = model.token()!!
                assertNull(model.ui.value.restriction!!.speed.current)
                if (index == 1) {
                    assertNotNull(model.ui.value.candidates)
                    assertTrue(model.ui.value.metadata.contains("capture clock:"))
                    await("Preview alignment probe must run") { model.ui.value.alignmentErrorPx != null }
                    assertTrue("Preview must align within 2 pixels: ${model.ui.value.alignmentErrorPx}", model.ui.value.alignmentErrorPx!! <= 2.0)
                    await("Steady state metrics must exclude warm-up") { model.ui.value.stats.steadySamples >= 8 }
                    android.util.Log.i("HudSessionTest", "Diagnostics: ${model.ui.value.stats}; alignment=${model.ui.value.alignmentErrorPx}px; thermal=${model.ui.value.thermal}")
                } else assertNull(model.ui.value.candidates)
                activity.onActivity { model.reset() }
                assertEquals(token.generation + 1, model.token()!!.generation)
                assertNull(model.ui.value.restriction!!.speed.current)
                if (index == 0) {
                    activity.moveToState(Lifecycle.State.CREATED)
                    await("Background must stop") { model.ui.value.phase == Phase.READY }
                    activity.moveToState(Lifecycle.State.RESUMED)
                    assertEquals(Phase.READY, model.ui.value.phase)
                    assertNull(model.token())
                } else {
                    activity.onActivity { model.stop() }
                    await("Stop must close runtime") { model.ui.value.phase == Phase.READY }
                }
                assertNull(model.ui.value.restriction)
            }
            activity.onActivity { model.start() }
            await("Restart must produce frames") { model.ui.value.stats.frames >= 2 }
            activity.onActivity { model.fail("Injected fatal engine failure; explicit restart required.") }
            await("Fatal error must release runtime") { model.ui.value.phase == Phase.ERROR }
            assertNull(model.token()); assertNull(model.ui.value.restriction)
            activity.onActivity { model.start() }
            await("Explicit recovery must produce frames") { model.ui.value.stats.frames >= 2 }
            assertNull(model.ui.value.restriction!!.speed.current)
            android.util.Log.i("HudSessionTest", "Recovered: ${model.ui.value.stats}; ${model.ui.value.metadata}")
        }
    }
}
