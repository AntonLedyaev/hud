package dev.diyhud.ui

import android.graphics.Paint
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.LocalLifecycleOwner
import dev.diyhud.camera.CameraBinding
import dev.diyhud.inference.previewBox
import dev.diyhud.domain.EventCodec
import dev.diyhud.session.*
import java.util.Locale

@Composable internal fun CameraPanel(ui: HudUi, model: HudViewModel) {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val view = LocalView.current
    val labels = remember(context) { runCatching {
        EventCodec.labels(context.assets.open("labels.json").bufferedReader().use { it.readText() })
            .classes.associate { it.id to it.name }
    }.getOrDefault(emptyMap()) }
    var preview by remember { mutableStateOf<PreviewView?>(null) }
    var laidOut by remember { mutableStateOf(false) }
    if (ui.diagnostics && ui.phase == Phase.ACTIVE) {
        Box(Modifier.fillMaxWidth().aspectRatio(3f / 4)) {
            AndroidView(modifier = Modifier.fillMaxSize(), onReset = null,
                onRelease = { preview = null; laidOut = false }, factory = {
                PreviewView(it).apply {
                    implementationMode = PreviewView.ImplementationMode.COMPATIBLE
                    scaleType = PreviewView.ScaleType.FILL_CENTER
                    preview = this
                    addOnLayoutChangeListener { _, l, t, r, b, _, _, _, _ -> laidOut = r > l && b > t }
                }
            })
            Canvas(Modifier.fillMaxSize()) {
                val frame = ui.candidates
                if (frame != null && ui.nowMs - frame.captureMs <= 1500) {
                    frame.detections.forEach { detection ->
                        val rect = previewBox(detection.box, frame.width, frame.height, size.width.toInt(), size.height.toInt())
                        drawRect(Color.Green, Offset(rect[0].toFloat(), rect[1].toFloat()),
                            Size((rect[2] - rect[0]).toFloat(), (rect[3] - rect[1]).toFloat()), style = Stroke(3f))
                        drawContext.canvas.nativeCanvas.drawText("${labels[detection.classId]} ${fmt(detection.confidence * 100)}%",
                            rect[0].toFloat(), (rect[1].toFloat() - 5).coerceAtLeast(22f),
                            Paint().apply { color = android.graphics.Color.GREEN; textSize = 28f })
                    }
                }
            }
        }
    }
    DisposableEffect(ui.phase, ui.diagnostics, laidOut, preview) {
        val binding = if (ui.phase == Phase.ACTIVE && (!ui.diagnostics || (laidOut && preview != null)))
            CameraBinding(context, owner, if (ui.diagnostics) preview else null, view.display?.rotation ?: 0, model) else null
        onDispose { binding?.close() }
    }
    if (ui.diagnostics) {
        val stats = ui.stats
        Text("Frames ${stats.frames} | steady FPS ${fmt(stats.fps)} | queue drops ${stats.dropped}")
        Text("Inference p50/p95 ${fmt(stats.inferenceP50)}/${fmt(stats.inferenceP95)} ms\n" +
            "Capture-result p50/p95 ${fmt(stats.latencyP50)}/${fmt(stats.latencyP95)} ms")
        Text("Duration ${ui.nowMs / 1000}s | warm-up frames ${stats.warmupFrames} | steady samples ${stats.steadySamples}/512\n" +
            "Candidate age ${ui.candidates?.let { "${ui.nowMs - it.captureMs} ms" } ?: "—"} | thermal ${ui.thermal}")
        Text(ui.metadata)
        Text("Preview mapping error ${ui.alignmentErrorPx?.let { "${fmt(it)} px" } ?: "pending"}")
        Text("Queue drops exclude frames discarded inside CameraX. Latencies exclude the first 5 s and include preprocessing. Boxes expire after 1500 ms.")
    }
}
private fun fmt(value: Double) = String.format(Locale.ROOT, "%.1f", value)
