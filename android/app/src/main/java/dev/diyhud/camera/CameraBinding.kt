package dev.diyhud.camera

import android.content.Context
import android.util.Size
import android.hardware.camera2.CameraCharacteristics
import android.os.SystemClock
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.camera.view.transform.ImageProxyTransformFactory
import androidx.camera.view.transform.CoordinateTransform
import android.graphics.RectF
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.Observer
import dev.diyhud.session.CameraClock
import dev.diyhud.session.HudViewModel
import dev.diyhud.domain.Box
import dev.diyhud.inference.previewBox
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs

/** Owns only this session's use cases. Main-thread close also cancels a pending bind. */
@androidx.annotation.OptIn(ExperimentalCamera2Interop::class, androidx.camera.view.TransformExperimental::class)
class CameraBinding(context: Context, owner: LifecycleOwner, preview: PreviewView?,
                    rotation: Int, private val viewModel: HudViewModel,
                    selector: CameraSelector = CameraSelector.DEFAULT_BACK_CAMERA) : AutoCloseable {
    private var disposed = false
    private var provider: ProcessCameraProvider? = null
    private var cases = emptyArray<UseCase>()
    private val clock = CameraClock()
    private var frameId = 0L
    @Volatile private var realtimeTimestamps = false
    private val sessionId = viewModel.token()?.sessionId
    private val probePending = AtomicBoolean(false)
    @Volatile private var alignmentChecked = false
    private var cameraState: androidx.lifecycle.LiveData<CameraState>? = null
    private val observer = Observer<CameraState> { state ->
        if (!disposed && state.error != null && viewModel.token()?.sessionId == sessionId)
            viewModel.fail("Camera unavailable. Close other camera apps and press Start.")
    }
    init {
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            if (!disposed && viewModel.token()?.sessionId == sessionId) try {
                val cameraProvider = future.get()
                provider = cameraProvider
                @Suppress("DEPRECATION")
                val analysis = ImageAnalysis.Builder().setTargetResolution(Size(1280, 960))
                    .setTargetRotation(rotation)
                    .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST).build()
                val group = UseCaseGroup.Builder().addUseCase(analysis)
                cases = arrayOf(analysis)
                if (preview != null) {
                    val surface = Preview.Builder().setTargetRotation(rotation).build()
                    surface.setSurfaceProvider(preview.surfaceProvider)
                    group.addUseCase(surface)
                    preview.viewPort?.let { group.setViewPort(it) }
                    cases = arrayOf(analysis, surface)
                }
                val camera = cameraProvider.bindToLifecycle(owner, selector, group.build())
                realtimeTimestamps = Camera2CameraInfo.from(camera.cameraInfo).getCameraCharacteristic(
                    CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE) == CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE_REALTIME
                analysis.setAnalyzer(viewModel.cameraWorker) { image ->
                    try {
                        val token = viewModel.token()
                        if (token != null && token.sessionId == sessionId) {
                            val time = clock.offsetMs(image.imageInfo.timestamp, viewModel.elapsedMs(),
                                if (realtimeTimestamps) SystemClock.elapsedRealtimeNanos() else null)
                            val frame = copyFrame(image, token, frameId++, time)
                            if (preview != null && !alignmentChecked && probePending.compareAndSet(false, true)) {
                                val transform = ImageProxyTransformFactory().apply {
                                    isUsingCropRect = true; isUsingRotationDegrees = true
                                }.getOutputTransform(image)
                                val width = if (frame.rotation % 180 == 0) frame.width else frame.height
                                val height = if (frame.rotation % 180 == 0) frame.height else frame.width
                                ContextCompat.getMainExecutor(context).execute {
                                    try {
                                        val target = preview.outputTransform
                                        if (!disposed && target != null) {
                                            val reference = CoordinateTransform(transform, target)
                                            val probes = listOf(Box(.0, .0, 1.0, 1.0), Box(.1, .2, .4, .7), Box(.6, .1, .9, .4))
                                            val error = probes.maxOf { box ->
                                                val rect = RectF((box.left * width).toFloat(), (box.top * height).toFloat(),
                                                    (box.right * width).toFloat(), (box.bottom * height).toFloat())
                                                reference.mapRect(rect)
                                                val mapped = previewBox(box, width, height, preview.width, preview.height)
                                                mapped.zip(listOf(rect.left, rect.top, rect.right, rect.bottom)).maxOf { (a, b) -> abs(a - b) }
                                            }
                                            viewModel.alignmentMeasured(error)
                                            alignmentChecked = true
                                        }
                                    } catch (error: Exception) {
                                        if (!disposed) viewModel.fail("Preview geometry unavailable. Stop and restart diagnostics.")
                                    } finally { probePending.set(false) }
                                }
                            }
                            viewModel.offer(frame)
                        }
                    } catch (error: Exception) {
                        if (viewModel.token()?.sessionId == sessionId)
                            viewModel.fail("Camera frame unavailable. Check camera access, then press Start.")
                    } finally { image.close() }
                }
                cameraState = camera.cameraInfo.cameraState
                cameraState!!.observe(owner, observer)
                viewModel.cameraReady(if (realtimeTimestamps) "sensor realtime" else "first-frame anchor (latency estimate)")
            } catch (error: Exception) {
                viewModel.fail("Cannot open rear camera. Check permission and camera privacy, then press Start.")
            }
        }, ContextCompat.getMainExecutor(context))
    }
    override fun close() {
        disposed = true
        cameraState?.removeObserver(observer)
        cases.filterIsInstance<ImageAnalysis>().forEach { it.clearAnalyzer() }
        provider?.unbind(*cases)
    }
}
