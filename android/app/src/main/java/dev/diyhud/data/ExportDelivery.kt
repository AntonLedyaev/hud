package dev.diyhud.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import java.io.File
import java.io.OutputStream

/** Journals only the newly user-created destination; never resumes copying after a crash. */
class ExportDelivery(private val context: Context, preferenceName: String = "trip-export") {
    private val preferences = context.getSharedPreferences(preferenceName, Context.MODE_PRIVATE)
    val pending get() = preferences.getString("pending", null) != null
    private fun forget(uri: Uri): Boolean {
        val cleared = preferences.edit().remove("pending").commit()
        if (cleared && uri.scheme == "content") try {
            context.contentResolver.releasePersistableUriPermission(uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        } catch (_: SecurityException) { }
        return cleared
    }
    fun recover(): Boolean {
        val value = preferences.getString("pending", null) ?: return true
        val uri = Uri.parse(value)
        val removed = try {
            if (uri.scheme == "file") File(requireNotNull(uri.path)).let { !it.exists() || it.delete() }
            else DocumentsContract.deleteDocument(context.contentResolver, uri)
        } catch (_: java.io.FileNotFoundException) { true } catch (_: Exception) { false }
        return removed && forget(uri)
    }
    fun dismiss() { preferences.getString("pending", null)?.let { check(forget(Uri.parse(it))) } }
    fun write(uri: Uri, action: (OutputStream) -> TripManifest): TripManifest {
        check(recover()) { "Clean up the unfinished export before exporting again" }
        require(uri.scheme in listOf("content", "file"))
        if (uri.scheme == "content") try {
            context.contentResolver.takePersistableUriPermission(uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        } catch (_: SecurityException) { /* Some providers offer only a temporary grant. */ }
        try {
            check(preferences.edit().putString("pending", uri.toString()).commit()) { "Cannot record export destination" }
            val output = requireNotNull(context.contentResolver.openOutputStream(uri, "wt")) { "Cannot open destination" }
            val manifest = output.use(action)
            check(forget(uri)) { "Cannot finalize export status" }
            return manifest
        } catch (error: Exception) {
            recover()
            throw error
        }
    }
}
