package dev.diyhud.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.mutableStateOf
import dev.diyhud.domain.RestrictionState
import dev.diyhud.inference.LiteRtDetector
import java.util.UUID
import java.util.concurrent.Executors

class MainActivity : ComponentActivity() {
    private val modelStatus = mutableStateOf("Checking local model…")
    private val modelWorker = Executors.newSingleThreadExecutor()
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    Column(
                        modifier = Modifier.safeDrawingPadding().padding(24.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text("DIY HUD", style = MaterialTheme.typography.headlineLarge)
                        Text("—", style = MaterialTheme.typography.displayLarge)
                        Text(modelStatus.value)
                        Text("Camera connection is the next implementation step.")
                        Text("Driver assistant · Always follow road signs.")
                    }
                }
            }
        }
        modelWorker.execute {
            val message = try {
                LiteRtDetector.open(assets).use { detector ->
                    val initial = RestrictionState(UUID.randomUUID().toString(), detector.contract.modelVersion,
                        detector.contract.labels.sha256, detector.contract.config)
                    check(initial.speed.current == null)
                    "Experimental model loaded · ${detector.backend}"
                }
            } catch (failure: Exception) {
                "Model unavailable. Reinstall a build with a validated model bundle."
            }
            runOnUiThread { if (!isDestroyed) modelStatus.value = message }
        }
    }

    override fun onDestroy() {
        modelWorker.shutdown()
        super.onDestroy()
    }
}
