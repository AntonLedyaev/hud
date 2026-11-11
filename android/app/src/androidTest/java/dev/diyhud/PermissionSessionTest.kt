package dev.diyhud

import android.Manifest
import android.content.pm.PackageManager
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.diyhud.session.Phase
import dev.diyhud.ui.MainActivity
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Run separately after adb shell pm revoke dev.diyhud android.permission.CAMERA.
 * Revoking from inside the target process would kill the instrumentation itself. */
@RunWith(AndroidJUnit4::class)
class PermissionSessionTest {
    @Test fun denyingPermissionNeverStartsSession() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        assumeTrue("Revoke camera permission before running this test", instrumentation.targetContext.checkSelfPermission(
            Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED)
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { it.startRecognition() }
            var denied = false
            repeat(50) {
                val root = instrumentation.uiAutomation.rootInActiveWindow
                val button = root?.let { findDeny(it) }
                if (!denied && button != null) denied = button.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                if (!denied) Thread.sleep(100)
            }
            assertTrue("System permission dialog must be denied", denied)
            instrumentation.waitForIdleSync()
            scenario.onActivity {
                assertEquals(Phase.READY, it.coordinator.ui.value.phase)
                assertNull(it.coordinator.token())
                assertTrue(it.coordinator.ui.value.message.contains("permission is required"))
            }
        }
    }
    private fun findDeny(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (node.viewIdResourceName?.endsWith(":id/permission_deny_button") == true) return node
        for (index in 0 until node.childCount) node.getChild(index)?.let { findDeny(it)?.let { found -> return found } }
        return null
    }
}
