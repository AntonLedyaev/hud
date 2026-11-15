package dev.diyhud.data

import dev.diyhud.domain.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.*
import java.security.DigestOutputStream
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

@Serializable data class SampleKey(val generation: Long, val frameId: Long)
@Serializable data class ArchiveEnd(
    val status: String, val endedAtEpochMs: Long?, val recoveredAtEpochMs: Long?,
    val reason: String?, val incomplete: Boolean, val lastAttemptSequence: Long,
    val droppedEvents: Long, val sampling: SampleStatus?,
)
@Serializable data class ArchiveFile(val path: String, val sha256: String, val count: Long)
@Serializable data class ArchiveSample(
    val image: String, val sha256: String, val byteCount: Long, val sourceSequence: Long,
    val observation: Observation, val config: EffectiveConfig, val unreviewed: Boolean,
)
data class ArchiveImage(val metadata: ArchiveSample, val file: File)
@Serializable data class TripManifest(
    val schemaVersion: Int = 1, val format: String = "diyhud-trip",
    val exportedAtEpochMs: Long, val header: SessionHeader, val end: ArchiveEnd,
    val events: ArchiveFile, val samples: ArchiveFile, val availableSamples: Int,
    val allSamplesIncluded: Boolean, val evidenceComplete: Boolean, val finalState: RestrictionState,
)
@Serializable private data class ArchiveCompletion(val schemaVersion: Int = 1, val manifestSha256: String)

/** Streaming ZIP: metadata/commit marker are written only after every selected file succeeds. */
object TripArchive {
    val json = Json { encodeDefaults = true }
    fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).hex()
    private fun ByteArray.hex() = joinToString("") { "%02x".format(it) }
    fun fileHash(file: File): String = file.inputStream().use { hash(it) }
    private fun hash(input: InputStream): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(32768)
        while (true) { val size = input.read(buffer); if (size < 0) break; digest.update(buffer, 0, size) }
        return digest.digest().hex()
    }
    private class Fold(val header: SessionHeader, val end: ArchiveEnd) {
        var state = RestrictionState(header.id, header.modelVersion, header.labels.sha256, header.config)
        val reducer = RestrictionReducer(header.labels)
        var last = -1L; var count = 0L
        var complete = end.status == "COMPLETED" && !end.incomplete
        fun accept(event: DomainEvent) {
            require(event.sessionId == header.id && event.modelVersion == header.modelVersion &&
                event.labelsSha256 == header.labels.sha256 && event.config == header.config) { "Event provenance mismatch" }
            require(event.sequence > last) { "Events are not ordered" }
            if (event.sequence != last + 1 || event.payload is Payload.Gap) {
                complete = false
                state = state.copy(speed = Estimate(), overtaking = Estimate(), stopUntilMs = null, reasons = listOf("evidence_gap"))
            }
            if (event.offsetMs < state.offsetMs) complete = false
            state = reducer.reduce(state, event); last = event.sequence; count++
        }
        fun complete() = complete && last == end.lastAttemptSequence
    }
    fun write(output: OutputStream, header: SessionHeader, end: ArchiveEnd, events: Sequence<DomainEvent>,
              images: List<ArchiveImage>, availableSamples: Int, now: Long = System.currentTimeMillis()): TripManifest {
        require(end.status in listOf("COMPLETED", "INTERRUPTED"))
        require(availableSamples >= images.size)
        require(images.map { it.metadata.image }.distinct().size == images.size)
        val fold = Fold(header, end)
        ZipOutputStream(output.buffered()).use { zip ->
            fun lines(name: String, produce: (Writer) -> Long): ArchiveFile {
                zip.putNextEntry(ZipEntry(name))
                val digest = MessageDigest.getInstance("SHA-256")
                val nonClosing = object : OutputStream() {
                    override fun write(value: Int) = zip.write(value)
                    override fun write(bytes: ByteArray, offset: Int, count: Int) = zip.write(bytes, offset, count)
                    override fun close() = zip.flush()
                }
                val writer = OutputStreamWriter(DigestOutputStream(nonClosing, digest), Charsets.UTF_8)
                val count = writer.use { produce(it) }
                zip.closeEntry()
                return ArchiveFile(name, digest.digest().hex(), count)
            }
            val eventFile = lines("events.jsonl") { writer ->
                for (event in events) { fold.accept(event); writer.write(EventCodec.encode(event)); writer.write("\n") }
                fold.count
            }
            for ((metadata, file) in images) {
                require(metadata.image.matches(Regex("images/[A-Za-z0-9_-]+_[0-9]+_[0-9]+\\.jpg")))
                require(metadata.observation.sessionId == header.id && metadata.config == header.config &&
                    metadata.observation.modelVersion == header.modelVersion && metadata.observation.labelsSha256 == header.labels.sha256)
                require(metadata.image == "images/${header.id}_${metadata.observation.resetGeneration}_${metadata.observation.frameId}.jpg")
                require(metadata.sourceSequence >= 0 && (!fold.complete() || metadata.sourceSequence <= fold.last))
                require(file.length() == metadata.byteCount && fileHash(file) == metadata.sha256) { "Sample file is missing or corrupt" }
                zip.putNextEntry(ZipEntry(metadata.image))
                val digest = MessageDigest.getInstance("SHA-256")
                file.inputStream().use { input ->
                    val buffer = ByteArray(32768)
                    while (true) {
                        val count = input.read(buffer); if (count < 0) break
                        digest.update(buffer, 0, count); zip.write(buffer, 0, count)
                    }
                }
                require(digest.digest().hex() == metadata.sha256) { "Sample changed during export" }
                zip.closeEntry()
            }
            val sampleFile = lines("samples.jsonl") { writer ->
                for (image in images) { writer.write(json.encodeToString(image.metadata)); writer.write("\n") }
                images.size.toLong()
            }
            val manifest = TripManifest(exportedAtEpochMs = now, header = header, end = end,
                events = eventFile, samples = sampleFile, availableSamples = availableSamples,
                allSamplesIncluded = images.size == availableSamples, evidenceComplete = fold.complete(), finalState = fold.state)
            val bytes = json.encodeToString(manifest).toByteArray(Charsets.UTF_8)
            zip.putNextEntry(ZipEntry("manifest.json")); zip.write(bytes); zip.closeEntry()
            zip.putNextEntry(ZipEntry("complete.json"))
            zip.write(json.encodeToString(ArchiveCompletion(manifestSha256 = sha256(bytes))).toByteArray(Charsets.UTF_8))
            zip.closeEntry()
            return manifest
        }
    }
    /** Replays the exported event stream and verifies all selected image references/checksums. */
    fun verify(file: File): TripManifest = ZipFile(file).use { zip ->
        fun bytes(name: String) = zip.getInputStream(requireNotNull(zip.getEntry(name)) { "Incomplete archive: $name missing" }).use { it.readBytes() }
        val manifestBytes = bytes("manifest.json")
        val completion = json.decodeFromString<ArchiveCompletion>(bytes("complete.json").toString(Charsets.UTF_8))
        require(completion.schemaVersion == 1 && completion.manifestSha256 == sha256(manifestBytes))
        val manifest = json.decodeFromString<TripManifest>(manifestBytes.toString(Charsets.UTF_8))
        require(manifest.schemaVersion == 1 && manifest.format == "diyhud-trip")
        require(manifest.end.status in listOf("COMPLETED", "INTERRUPTED"))
        require(manifest.events.path == "events.jsonl" && manifest.samples.path == "samples.jsonl")
        for (entry in listOf(manifest.events, manifest.samples))
            require(zip.getInputStream(requireNotNull(zip.getEntry(entry.path))).use { hash(it) } == entry.sha256)
        val fold = Fold(manifest.header, manifest.end)
        zip.getInputStream(zip.getEntry("events.jsonl")).bufferedReader().useLines { lines ->
            lines.forEach { fold.accept(EventCodec.decode(it)) }
        }
        require(fold.count == manifest.events.count && fold.complete() == manifest.evidenceComplete && fold.state == manifest.finalState)
        val expected = mutableSetOf("events.jsonl", "samples.jsonl", "manifest.json", "complete.json")
        var selected = 0L
        zip.getInputStream(zip.getEntry("samples.jsonl")).bufferedReader().useLines { lines ->
            lines.forEach { line ->
                val sample = json.decodeFromString<ArchiveSample>(line)
                require(sample.image.matches(Regex("images/[A-Za-z0-9_-]+_[0-9]+_[0-9]+\\.jpg")) && expected.add(sample.image))
                require(sample.observation.sessionId == manifest.header.id && sample.config == manifest.header.config &&
                    sample.observation.modelVersion == manifest.header.modelVersion && sample.observation.labelsSha256 == manifest.header.labels.sha256)
                require(sample.image == "images/${manifest.header.id}_${sample.observation.resetGeneration}_${sample.observation.frameId}.jpg")
                require(sample.sourceSequence >= 0 && (!manifest.evidenceComplete || sample.sourceSequence <= fold.last))
                val image = requireNotNull(zip.getEntry(sample.image))
                require(image.size == sample.byteCount && zip.getInputStream(image).use { hash(it) } == sample.sha256)
                selected++
            }
        }
        val entries = zip.entries().asSequence().map { it.name }.toList()
        require(entries.size == expected.size && entries.toSet() == expected)
        require(selected == manifest.samples.count && selected <= manifest.availableSamples &&
            manifest.allSamplesIncluded == (selected.toInt() == manifest.availableSamples))
        manifest
    }
}
