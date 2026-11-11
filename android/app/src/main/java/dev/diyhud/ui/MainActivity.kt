package dev.diyhud.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.diyhud.domain.Freshness
import dev.diyhud.session.*

class MainActivity : ComponentActivity() {
    internal val coordinator: HudViewModel by lazy { ViewModelProvider(this)[HudViewModel::class.java] }
    private val cameraPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted && lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) coordinator.start()
        else coordinator.permissionDenied()
    }
    internal fun startRecognition() {
        if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) coordinator.start()
        else cameraPermission.launch(Manifest.permission.CAMERA)
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val ui by coordinator.ui.collectAsState()
            DisposableEffect(ui.phase == Phase.ACTIVE) {
                if (ui.phase == Phase.ACTIVE) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                onDispose { window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
            }
            MaterialTheme(colorScheme = darkColorScheme()) {
                Surface(Modifier.fillMaxSize()) {
                    HudScreen(ui, coordinator, ::startRecognition) {
                        startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                            Uri.fromParts("package", packageName, null)))
                    }
                }
            }
        }
    }
    override fun onStop() {
        coordinator.stop("App left foreground. Press Start for a new session.")
        super.onStop()
    }
}

@Composable private fun HudScreen(ui: HudUi, model: HudViewModel, start: () -> Unit, settings: () -> Unit) {
    val idle = ui.phase == Phase.READY || ui.phase == Phase.ERROR
    Column(Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("DIY HUD", style = MaterialTheme.typography.headlineMedium)
        Text("Observed speed limit", style = MaterialTheme.typography.titleMedium)
        Text(ui.restriction?.speed?.current?.toString() ?: "—", style = MaterialTheme.typography.displayLarge)
        Text("km/h")
        val speed = ui.restriction?.speed
        Text(when (speed?.freshness) {
            Freshness.KNOWN -> "Confirmed ${(ui.nowMs - (speed.confirmedAtMs ?: ui.nowMs)).coerceAtLeast(0) / 1000}s ago"
            Freshness.STALE -> "Stale — waiting for a new sign"
            else -> "Unknown — waiting for a confirmed sign"
        })
        Text(when (ui.restriction?.overtaking?.freshness) {
            Freshness.KNOWN -> if (ui.restriction.overtaking.current == true) "No overtaking" else "Overtaking: unknown"
            Freshness.STALE -> "Overtaking: stale"
            else -> "Overtaking: unknown"
        })
        if (ui.restriction?.stopUntilMs != null) Text("STOP sign observed", color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.headlineMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = start, enabled = idle) { Text("Start") }
            Button(onClick = { model.stop() }, enabled = ui.phase in listOf(Phase.STARTING, Phase.ACTIVE)) { Text("Stop") }
            OutlinedButton(onClick = { model.reset() }, enabled = ui.phase == Phase.ACTIVE) { Text("Reset") }
        }
        if (idle) Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Diagnostics before Start")
            Switch(checked = ui.diagnostics, onCheckedChange = { model.diagnosticMode(it) })
        }
        if (idle) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Save sample images")
                Switch(checked = ui.samplingEnabled, onCheckedChange = { model.sampleMode(it) })
            }
            Text("Off by default · up to 1 image/s and 10 MB/session · private, unreviewed samples",
                style = MaterialTheme.typography.bodySmall)
        }
        Text(ui.message)
        ui.evidence?.let { evidence ->
            Text(if (evidence.incomplete) "Evidence incomplete: ${evidence.dropped} events lost. ${evidence.warning ?: ""}"
                else "Local events saved: ${evidence.stored}/${evidence.attempted}",
                color = if (evidence.incomplete) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
        }
        ui.samples?.takeIf { it.enabled }?.let { samples ->
            Text((if (samples.failed) "Sample evidence incomplete: " else "Sample images: ") +
                "${samples.saved} · ${samples.bytes / 1000} / 10000 kB" +
                (if (samples.limited || samples.failed) " · ${samples.warning}" else "") +
                (if (samples.dropped > 0) " · dropped ${samples.dropped}" else ""),
                color = if (samples.failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
        }
        if (ui.phase == Phase.STOPPING) Text("Closing session and saving local events...")
        if (idle) TextButton(onClick = settings) { Text("App settings") }
        CameraPanel(ui, model)
        Text("Experimental driver assistant · Always follow road signs.", style = MaterialTheme.typography.bodySmall)
    }
}
