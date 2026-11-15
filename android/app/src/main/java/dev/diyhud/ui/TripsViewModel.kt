package dev.diyhud.ui

import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import dev.diyhud.data.*
import dev.diyhud.session.HudViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.Executors

data class TripsUi(
    val busy: Boolean = false, val message: String? = null, val trips: List<SessionEntity> = emptyList(),
    val page: Int = 0, val detail: TripReview? = null, val events: List<ReviewedEvent> = emptyList(),
    val eventAfter: Long = -1, val includeTicks: Boolean = false, val selected: Set<SampleKey> = emptySet(),
    val preview: Bitmap? = null, val previewName: String? = null, val pendingCleanup: Boolean = false,
)
class TripsViewModel(application: Application, private val coordinator: HudViewModel) : AndroidViewModel(application) {
    private val state = MutableStateFlow(TripsUi())
    val ui = state.asStateFlow()
    private val worker = Executors.newSingleThreadExecutor()
    private val lock = Any()
    private val delivery = ExportDelivery(application)
    private var exportRequest: Pair<String, Set<SampleKey>>? = null
    private fun store() = RoomEvidenceStore.get(getApplication())
    private fun job(action: () -> Unit) = synchronized(lock) {
        if (state.value.busy) return
        val lease = try { coordinator.reviewOperation() } catch (_: Exception) { return }
        state.value = state.value.copy(busy = true, message = null)
        try { worker.execute {
            try { lease.use { action() } }
            catch (error: Exception) { state.value = state.value.copy(message = error.message ?: "Evidence operation failed") }
            finally { state.value = state.value.copy(busy = false, pendingCleanup = delivery.pending) }
        } } catch (_: java.util.concurrent.RejectedExecutionException) {
            lease.close(); state.value = state.value.copy(busy = false, message = "Review screen closed")
        }
    }
    fun list(page: Int = 0) = job {
        val cleaned = delivery.recover()
        store().prepareReview()
        state.value = TripsUi(busy = true, trips = store().database.evidence().sessionPage(page * 50), page = page,
            message = if (cleaned) null else "Unfinished export could not be removed. Retry cleanup or remove it in Files.",
            pendingCleanup = delivery.pending)
    }
    fun open(id: String) = job {
        state.value = state.value.copy(detail = store().review(id), events = store().reviewEvents(id),
            eventAfter = -1, includeTicks = false, selected = emptySet(), preview = null, previewName = null)
    }
    fun events(after: Long = -1, includeTicks: Boolean = state.value.includeTicks) = job {
        val id = state.value.detail?.header?.id ?: return@job
        state.value = state.value.copy(events = store().reviewEvents(id, after, includeTicks), eventAfter = after,
            includeTicks = includeTicks)
    }
    fun select(key: SampleKey, selected: Boolean) = synchronized(lock) {
        if (!state.value.busy && coordinator.canReview()) state.value = state.value.copy(selected =
            if (selected) state.value.selected + key else state.value.selected - key)
    }
    fun selectAll(selected: Boolean) = synchronized(lock) {
        if (!state.value.busy && coordinator.canReview()) state.value = state.value.copy(selected =
            if (selected) state.value.detail?.samples.orEmpty().map { SampleKey(it.generation, it.frameId) }.toSet() else emptySet())
    }
    fun preview(sample: SampleEntity) = job {
        val file = store().sampleFile(sample)
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        var scale = 1
        while (bounds.outWidth / scale > 1024 || bounds.outHeight / scale > 1024) scale *= 2
        val image = requireNotNull(BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = scale })) {
            "Cannot decode sample image"
        }
        state.value = state.value.copy(preview = image, previewName = "Frame ${sample.frameId}, generation ${sample.generation} · unreviewed")
    }
    fun closePreview() { if (!state.value.busy) state.value = state.value.copy(preview = null, previewName = null) }
    fun backToList() { if (!state.value.busy) state.value = state.value.copy(detail = null, preview = null, previewName = null) }
    fun requestExport(): String? = synchronized(lock) {
        val detail = state.value.detail ?: return null
        if (state.value.busy || delivery.pending || !coordinator.canReview()) return null
        exportRequest = detail.header.id to state.value.selected.toSet()
        "hud-trip-${detail.header.id}.zip"
    }
    fun export(uri: Uri?) {
        val request = exportRequest; exportRequest = null
        if (uri == null) { state.value = state.value.copy(message = "Export cancelled"); return }
        if (request == null) {
            state.value = state.value.copy(message = "Export was interrupted. Open the trip and export again.")
            worker.execute { try { getApplication<Application>().contentResolver.delete(uri, null, null) } catch (_: Exception) { } }
            return
        }
        job {
            val result = delivery.write(uri) { store().export(request.first, request.second, it) }
            state.value = state.value.copy(message = "Export saved: ${result.events.count} events, ${result.samples.count} selected images. " +
                if (result.evidenceComplete) "Complete event trace." else "Incomplete event trace.")
        }
    }
    fun delete(id: String) = job {
        val result = store().deleteTrip(id)
        state.value = TripsUi(busy = true, trips = store().database.evidence().sessionPage(0),
            message = result.message ?: if (result.deleted) "Local trip deleted. Exported copies remain outside the app." else "Deletion pending; retry cleanup.")
    }
    fun retryExportCleanup() = job {
        state.value = state.value.copy(message = if (delivery.recover()) "Unfinished export removed" else "Cleanup failed. Remove the file in Files, then dismiss this warning.")
    }
    fun dismissExportCleanup() = job { delivery.dismiss(); state.value = state.value.copy(message = "Warning dismissed. Any external incomplete file remains your responsibility.") }
    override fun onCleared() { worker.shutdown() }
    companion object {
        fun factory(application: Application, coordinator: HudViewModel) = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = TripsViewModel(application, coordinator) as T
        }
    }
}
