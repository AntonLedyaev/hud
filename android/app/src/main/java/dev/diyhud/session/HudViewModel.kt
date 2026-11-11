package dev.diyhud.session

import android.app.Application
import android.app.AppOpsManager
import android.Manifest
import android.content.pm.PackageManager
import android.os.Process
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import androidx.lifecycle.AndroidViewModel
import dev.diyhud.camera.RgbFrame
import dev.diyhud.domain.*
import dev.diyhud.data.*
import dev.diyhud.inference.LiteRtDetector
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

enum class Phase { READY, STARTING, ACTIVE, STOPPING, ERROR }
data class CandidateFrame(val detections: List<Detection>, val width: Int, val height: Int, val captureMs: Long)
data class HudUi(
    val phase: Phase = Phase.READY, val diagnostics: Boolean = false,
    val message: String = "Press Start to check the local model and open the camera.",
    val restriction: RestrictionState? = null, val candidates: CandidateFrame? = null,
    val stats: PipelineStats = PipelineStats(), val nowMs: Long = 0,
    val metadata: String = "", val thermal: Int = 0,
    val alignmentErrorPx: Double? = null,
    val confirmation: ConfirmationDebug? = null,
    val evidence: EvidenceStatus? = null,
    val samplingEnabled: Boolean = false, val samples: SampleStatus? = null,
)

/** Serializes domain updates separately from slow native inference. No camera buffer crosses workers. */
class HudViewModel(application: Application) : AndroidViewModel(application) {
    private val lock = Any()
    private val mutableUi = MutableStateFlow(HudUi())
    val ui = mutableUi.asStateFlow()
    private val inferenceWorker = Executors.newSingleThreadExecutor()
    val cameraWorker = Executors.newSingleThreadExecutor()
    private val clockWorker = Executors.newSingleThreadScheduledExecutor()
    private var detector: LiteRtDetector? = null // accessed only on inferenceWorker
    private var session: LiveSession? = null
    private var processor: LatestFrameProcessor<RgbFrame>? = null
    private var evidence: EvidenceWriter? = null
    private var sampler: SampleWriter? = null
    private var metrics = PipelineMetrics()
    private var startNs = 0L
    private var lastResultMs = 0L
    private var request = 0L
    private var cleared = false
    init {
        try {
            RoomEvidenceStore.worker.execute {
                try { RoomEvidenceStore.get(application).recover() }
                catch (error: Exception) {
                    synchronized(lock) { mutableUi.value = mutableUi.value.copy(evidence =
                        EvidenceStatus(disabled = true, warning = "Previous evidence unavailable")) }
                }
            }
        } catch (error: java.util.concurrent.RejectedExecutionException) {
            mutableUi.value = mutableUi.value.copy(evidence = EvidenceStatus(disabled = true,
                warning = "Evidence worker unavailable"))
        }
        clockWorker.scheduleAtFixedRate({
            synchronized(lock) {
                session?.let {
                    val now = elapsedMs()
                    if (!cameraAccessAllowed()) {
                        fail("Camera permission was revoked. Allow access in App settings and press Start.")
                        return@synchronized
                    }
                    if (now - lastResultMs >= 10_000) {
                        fail("Camera or engine produced no results for 10 s. Check camera access and press Start.")
                        return@synchronized
                    }
                    it.tick(now)
                    publish(now)
                }
            }
        }, 250, 250, TimeUnit.MILLISECONDS)
    }
    fun diagnosticMode(enabled: Boolean) = synchronized(lock) {
        if (mutableUi.value.phase in listOf(Phase.READY, Phase.ERROR))
            mutableUi.value = mutableUi.value.copy(diagnostics = enabled)
    }
    fun sampleMode(enabled: Boolean) = synchronized(lock) {
        if (mutableUi.value.phase in listOf(Phase.READY, Phase.ERROR))
            mutableUi.value = mutableUi.value.copy(samplingEnabled = enabled)
    }
    fun permissionDenied() = synchronized(lock) {
        mutableUi.value = mutableUi.value.copy(message = "Camera permission is required. Allow it in App settings, then press Start.")
    }
    @Suppress("DEPRECATION")
    private fun cameraAccessAllowed(): Boolean {
        val app = getApplication<Application>()
        val operations = app.getSystemService(AppOpsManager::class.java)
        // Raw mode preserves MODE_FOREGROUND during Activity/UID importance propagation.
        // The evaluated check can transiently report IGNORED just after onResume.
        val access = if (Build.VERSION.SDK_INT >= 29) operations.unsafeCheckOpRawNoThrow(
            AppOpsManager.OPSTR_CAMERA, Process.myUid(), app.packageName)
        else operations.checkOpNoThrow(AppOpsManager.OPSTR_CAMERA, Process.myUid(), app.packageName)
        return app.checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED &&
            access != AppOpsManager.MODE_IGNORED && access != AppOpsManager.MODE_ERRORED
    }
    fun start() = synchronized(lock) {
        if (cleared || mutableUi.value.phase !in listOf(Phase.READY, Phase.ERROR)) return
        if (!cameraAccessAllowed()) {
            mutableUi.value = mutableUi.value.copy(phase = Phase.ERROR,
                message = "Camera access is blocked. Allow camera access in App settings, then press Start.")
            return
        }
        val attempt = ++request
        val sampleOptIn = mutableUi.value.samplingEnabled
        mutableUi.value = mutableUi.value.copy(phase = Phase.STARTING, restriction = null,
            candidates = null, confirmation = null, evidence = null, samples = null, stats = PipelineStats(), alignmentErrorPx = null, message = "Validating bundled model...")
        inferenceWorker.execute {
            try {
                val engine = LiteRtDetector.open(getApplication<Application>().assets)
                detector = engine
                val app = getApplication<Application>()
                val manifest = app.assets.open("model/manifest.json").bufferedReader().use { it.readText() }
                val appVersion = app.packageManager.getPackageInfo(app.packageName, 0).versionName ?: "unknown"
                synchronized(lock) {
                    if (cleared || attempt != request) { engine.close(); detector = null; return@execute }
                    startNs = SystemClock.elapsedRealtimeNanos()
                    lastResultMs = 0L
                    val id = UUID.randomUUID().toString()
                    val config = engine.contract.config.copy(
                        version = engine.contract.config.version + if (sampleOptIn) 1 else 0,
                        samplingEnabled = sampleOptIn, sampleBudgetBytes = if (sampleOptIn) 10_000_000 else 0,
                        sampleIntervalMs = if (sampleOptIn) 1000 else engine.contract.config.sampleIntervalMs)
                    val writer = RoomEvidenceStore.writer(app, SessionHeader(id, System.currentTimeMillis(),
                        appVersion, engine.contract.modelVersion, engine.contract.labels, config,
                        manifest, engine.backend, "${Build.MODEL} / Android ${Build.VERSION.RELEASE}"))
                    evidence = writer
                    sampler = RoomEvidenceStore.sampler(app, writer)
                    session = LiveSession(id, engine.contract.modelVersion,
                        engine.contract.labels, config) { writer.offer(it) }
                    metrics = PipelineMetrics()
                    processor = LatestFrameProcessor(inferenceWorker, ::process) { fail("Inference failed. Stop and restart; reinstall the validated bundle if it repeats.") }
                    mutableUi.value = mutableUi.value.copy(phase = Phase.ACTIVE,
                        message = "Opening camera...", restriction = session!!.state,
                        metadata = "CPU | app $appVersion | model ${engine.contract.modelVersion}\n" +
                            "labels v${engine.contract.labels.version} ${engine.contract.labels.sha256.take(12)} | effective config v${config.version}\n" +
                            "input ${engine.contract.input.shape[2]}x${engine.contract.input.shape[1]} | warm-up [0, 5000) ms\n" +
                            "device ${Build.MODEL} | Android ${Build.VERSION.RELEASE} / API ${Build.VERSION.SDK_INT}")
                }
            } catch (error: Exception) {
                fail("Model unavailable. Reinstall a build with the validated bundle, then press Start.")
            }
        }
    }
    fun cameraReady(clock: String) = synchronized(lock) {
        if (session != null) mutableUi.value = mutableUi.value.copy(message = "Recognition active | experimental model",
            metadata = mutableUi.value.metadata + "\ncapture clock: $clock")
    }
    fun alignmentMeasured(errorPx: Double) = synchronized(lock) {
        if (session != null) mutableUi.value = mutableUi.value.copy(alignmentErrorPx = errorPx)
    }
    fun elapsedMs(): Long = (SystemClock.elapsedRealtimeNanos() - startNs) / 1_000_000
    fun token(): FrameToken? = synchronized(lock) { session?.token() }
    fun offer(frame: RgbFrame) = synchronized(lock) {
        if (session?.accepts(frame.token) == true) processor?.offer(frame) else frame.close()
    }
    private fun process(frame: RgbFrame) {
        if (synchronized(lock) { session?.accepts(frame.token) != true }) return
        val begin = SystemClock.elapsedRealtimeNanos()
        val detections = requireNotNull(detector).detect(frame.rgb, frame.width, frame.height, frame.rotation)
        val inferenceMs = (SystemClock.elapsedRealtimeNanos() - begin) / 1_000_000.0
        synchronized(lock) {
            val live = session ?: return
            val now = elapsedMs()
            if (!live.result(frame.token, frame.id, frame.captureMs, now, detections)) return
            sampler?.offer(frame.captureMs) {
                SampleFrame(Observation(1, live.id, frame.token.generation, frame.id, frame.captureMs,
                    live.model, live.labels.sha256, detections.toList()), live.config, live.state.sequence,
                    frame.rgb.copyOf(), frame.width, frame.height, frame.rotation)
            }
            lastResultMs = now
            metrics.record(now, inferenceMs, (now - frame.captureMs).coerceAtLeast(0).toDouble())
            val rotated = frame.rotation % 180 != 0
            mutableUi.value = mutableUi.value.copy(candidates = if (mutableUi.value.diagnostics)
                CandidateFrame(detections.toList(), if (rotated) frame.height else frame.width,
                    if (rotated) frame.width else frame.height, frame.captureMs) else null)
            publish(now)
        }
    }
    private fun publish(now: Long) {
        val power = getApplication<Application>().getSystemService(PowerManager::class.java)
        mutableUi.value = mutableUi.value.copy(restriction = session?.state, nowMs = now,
            evidence = evidence?.snapshot(),
            samples = sampler?.snapshot(),
            confirmation = if (mutableUi.value.diagnostics) session?.confirmationDebug else null,
            stats = metrics.snapshot(now, processor?.dropped ?: 0),
            thermal = if (Build.VERSION.SDK_INT >= 29) power.currentThermalStatus else -1)
    }
    fun reset() = synchronized(lock) {
        session?.reset(elapsedMs())
        mutableUi.value = mutableUi.value.copy(restriction = session?.state, candidates = null,
            confirmation = if (mutableUi.value.diagnostics) session?.confirmationDebug else null)
    }
    fun fail(message: String) {
        android.util.Log.w("HudSession", message)
        synchronized(lock) { session?.error(elapsedMs(), "recognition_failure", message) }
        stop(message, true)
    }
    fun stop(message: String = "Stopped. Press Start for a new session.", error: Boolean = false) = synchronized(lock) {
        if (mutableUi.value.phase == Phase.STOPPING) return
        if (session == null && mutableUi.value.phase != Phase.STARTING) {
            if (error) mutableUi.value = mutableUi.value.copy(phase = Phase.ERROR, message = message)
            return
        }
        ++request
        val writer = evidence
        val images = sampler
        val endedAt = System.currentTimeMillis()
        session?.tick(elapsedMs())
        session?.stop(); session = null
        processor?.close(); processor = null
        mutableUi.value = mutableUi.value.copy(phase = Phase.STOPPING, restriction = null,
            candidates = null, confirmation = null, message = message)
        inferenceWorker.execute {
            detector?.close(); detector = null
            fun complete() = synchronized(lock) {
                evidence = null; sampler = null
                mutableUi.value = mutableUi.value.copy(phase = if (error) Phase.ERROR else Phase.READY,
                    evidence = writer?.snapshot(), samples = images?.snapshot(), samplingEnabled = false)
            }
            fun finishEvents() {
                val saved = writer?.finish(message, endedAt, images?.snapshot())
                if (saved == null) complete() else saved.whenComplete { _, _ -> complete() }
            }
            val savedImages = images?.finish()
            if (savedImages == null) finishEvents() else savedImages.whenComplete { _, _ -> finishEvents() }
        }
    }
    override fun onCleared() {
        stop()
        synchronized(lock) { cleared = true }
        clockWorker.shutdownNow(); cameraWorker.shutdown(); inferenceWorker.shutdown()
    }
}
