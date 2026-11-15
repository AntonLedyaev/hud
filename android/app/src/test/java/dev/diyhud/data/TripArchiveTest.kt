package dev.diyhud.data

import dev.diyhud.domain.*
import dev.diyhud.session.LiveSession
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

class TripArchiveTest {
    @get:Rule val temporary = TemporaryFolder()
    private val root = File(requireNotNull(System.getProperty("hud.repoRoot")))
    private val labels = EventCodec.labels(File(root, "android/app/build/generated/canonical/labels.json").readText())
    private val config = EventCodec.decode(File(root, "shared/fixtures/event.v1.json").readText()).config.copy(minimumHits = 1)
    private val header = SessionHeader("archive-fixture", 1000, "test", "model-fixture", labels, config, "{}", "CPU", "fixture")
    private val events = mutableListOf<DomainEvent>()
    private val live = LiveSession(header.id, header.modelVersion, labels, config) { events += it }
    private fun populate() {
        val sign = Detection(labels.classes.single { it.name == "speed_limit_30" }.id, .99, Box(.1, .1, .3, .3))
        live.result(live.token(), 0, 0, 100, listOf(sign))
        live.reset(200)
        live.result(live.token(), 1, 300, 400, listOf(sign))
        live.tick(500)
    }
    private fun end(status: String = "COMPLETED", last: Long = events.last().sequence) =
        ArchiveEnd(status, if (status == "COMPLETED") 2000 else null, null, "fixture", status != "COMPLETED", last, 0, null)
    private fun write(images: List<ArchiveImage> = emptyList(), available: Int = images.size,
                      source: List<DomainEvent> = events, footer: ArchiveEnd = end()): File {
        val file = temporary.newFile()
        TripArchive.write(file.outputStream(), header, footer, source.asSequence(), images, available)
        return file
    }
    private fun image(): ArchiveImage {
        val file = temporary.newFile().apply { writeBytes(byteArrayOf(1, 2, 3)) }
        return ArchiveImage(ArchiveSample("images/${header.id}_0_0.jpg", TripArchive.fileHash(file), file.length(), 0,
            Observation(1, header.id, 0, 0, 0, header.modelVersion, labels.sha256, emptyList()), config, true), file)
    }
    @Test fun completeExportReplaysProductionReducerWithFullProvenance() {
        populate()
        val result = TripArchive.verify(write())
        assertTrue(result.evidenceComplete); assertTrue(result.allSamplesIncluded)
        assertEquals(header, result.header); assertEquals(live.state, result.finalState)
        assertEquals(events.size.toLong(), result.events.count)
        val exported = write()
        var state = RestrictionState(header.id, header.modelVersion, labels.sha256, config)
        val reducer = RestrictionReducer(labels)
        ZipFile(exported).use { zip ->
            zip.getInputStream(zip.getEntry("events.jsonl")).bufferedReader().useLines { lines ->
                lines.forEach { line ->
                    val event = EventCodec.decode(line)
                    val recorded = when (val payload = event.payload) {
                        is Payload.Transition -> payload.details
                        is Payload.Conflict -> payload.details
                        else -> null
                    }
                    if (recorded != null) assertEquals(state, TripArchive.json.decodeFromString<RestrictionState>(recorded))
                    state = reducer.reduce(state, event)
                }
            }
        }
        assertEquals(live.state, state)
    }
    @Test fun imagesAreIncludedOnlyWhenExplicitlySelected() {
        populate()
        val none = write(available = 2)
        assertFalse(TripArchive.verify(none).allSamplesIncluded)
        ZipFile(none).use { zip -> assertFalse(zip.entries().asSequence().any { it.name.startsWith("images/") }) }
        val selected = image()
        val one = write(listOf(selected), 2)
        val result = TripArchive.verify(one)
        assertTrue(result.evidenceComplete); assertEquals(1L, result.samples.count); assertFalse(result.allSamplesIncluded)
        ZipFile(one).use { zip -> assertArrayEquals(selected.file.readBytes(), zip.getInputStream(zip.getEntry(selected.metadata.image)).readBytes()) }
    }
    @Test fun interruptedGappedAndMissingTailTracesStayIncomplete() {
        populate()
        assertFalse(TripArchive.verify(write(footer = end("INTERRUPTED"))).evidenceComplete)
        assertFalse(TripArchive.verify(write(source = events.filter { it.sequence != 1L })).evidenceComplete)
        assertFalse(TripArchive.verify(write(footer = end(last = events.last().sequence + 1))).evidenceComplete)
        val gap = events.toMutableList().apply { this[0] = first().copy(payload = Payload.Gap(1)) }
        assertFalse(TripArchive.verify(write(source = gap)).evidenceComplete)
    }
    @Test fun missingCorruptOrMismatchedImageCannotProduceACompleteArchive() {
        populate()
        val image = image()
        image.file.writeBytes(byteArrayOf(9))
        val failed = temporary.newFile()
        assertThrows(IllegalArgumentException::class.java) {
            TripArchive.write(failed.outputStream(), header, end(), events.asSequence(), listOf(image), 1)
        }
        assertThrows(Exception::class.java) { TripArchive.verify(failed) }
        val other = image()
        assertThrows(IllegalArgumentException::class.java) { write(listOf(other.copy(metadata = other.metadata.copy(image = "images/another_0_0.jpg")))) }
        assertThrows(IllegalArgumentException::class.java) { write(listOf(other.copy(metadata = other.metadata.copy(sourceSequence = 99999)))) }
    }
    @Test fun incompleteAndTamperedArchivesAreRejected() {
        populate()
        val original = write(listOf(image()))
        fun rewrite(skip: String? = null, corrupt: String? = null): File {
            val changed = temporary.newFile()
            ZipFile(original).use { source -> ZipOutputStream(changed.outputStream()).use { target ->
                source.entries().asSequence().filter { it.name != skip }.forEach { entry ->
                    target.putNextEntry(ZipEntry(entry.name))
                    target.write(if (entry.name == corrupt) byteArrayOf(9) else source.getInputStream(entry).use { it.readBytes() })
                    target.closeEntry()
                }
            } }
            return changed
        }
        assertThrows(Exception::class.java) { TripArchive.verify(rewrite(skip = "complete.json")) }
        assertThrows(Exception::class.java) { TripArchive.verify(rewrite(corrupt = "events.jsonl")) }
        assertThrows(Exception::class.java) { TripArchive.verify(rewrite(corrupt = "manifest.json")) }
        assertThrows(Exception::class.java) { TripArchive.verify(rewrite(skip = "images/${header.id}_0_0.jpg")) }
        val truncated = temporary.newFile().apply { writeBytes(original.readBytes().take(100).toByteArray()) }
        assertThrows(Exception::class.java) { TripArchive.verify(truncated) }
    }
    @Test fun outputFailurePropagatesInsteadOfReturningSuccess() {
        populate()
        val broken = object : OutputStream() { override fun write(value: Int) { throw IOException("disk full") } }
        assertThrows(IOException::class.java) { TripArchive.write(broken, header, end(), events.asSequence(), emptyList(), 0) }
    }
}
