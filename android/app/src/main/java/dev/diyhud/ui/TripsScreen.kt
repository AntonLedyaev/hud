package dev.diyhud.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.dp
import dev.diyhud.data.*
import dev.diyhud.domain.*
import java.text.DateFormat
import java.util.Date
import java.util.Locale

@Composable internal fun TripsScreen(ui: TripsUi, model: TripsViewModel, back: () -> Unit, export: () -> Unit) {
    var deleteId by remember { mutableStateOf<String?>(null) }
    var dismissWarning by remember { mutableStateOf(false) }
    LazyColumn(Modifier.fillMaxSize().safeDrawingPadding().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(onClick = { if (ui.preview != null) model.closePreview() else if (ui.detail != null) model.backToList() else back() }, enabled = !ui.busy) { Text("Back") }
                Text("Trips", style = MaterialTheme.typography.headlineMedium)
            }
        }
        if (ui.busy) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
        ui.message?.let { item { Text(it) } }
        if (ui.pendingCleanup) item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { model.retryExportCleanup() }, enabled = !ui.busy) { Text("Retry export cleanup") }
                TextButton(onClick = { dismissWarning = true }, enabled = !ui.busy) { Text("Dismiss warning") }
            }
        }
        if (ui.preview != null) {
            item { Text(ui.previewName.orEmpty()); Image(ui.preview.asImageBitmap(), "Saved unreviewed frame", Modifier.fillMaxWidth()) }
        } else if (ui.detail == null) {
            if (ui.trips.isEmpty()) item { Text("No trips on this page. Start and stop a session to record one.") }
            items(ui.trips, key = { it.id }) { trip ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(DateFormat.getDateTimeInstance().format(Date(trip.startedAtEpochMs)))
                        Text(trip.id, style = MaterialTheme.typography.bodySmall)
                        Text(when {
                            trip.status == "DELETING" -> "Deletion pending"
                            trip.status == "INTERRUPTED" -> "Interrupted · incomplete evidence"
                            trip.incomplete -> "Closed · incomplete evidence"
                            else -> "Closed · open to verify completeness"
                        })
                        if (trip.status == "DELETING") Button(onClick = { model.delete(trip.id) }, enabled = !ui.busy) { Text("Retry deletion") }
                        else Button(onClick = { model.open(trip.id) }, enabled = !ui.busy) { Text("Open trip") }
                    }
                }
            }
            item { Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { model.list(ui.page - 1) }, enabled = !ui.busy && ui.page > 0) { Text("Previous trips") }
                OutlinedButton(onClick = { model.list(ui.page + 1) }, enabled = !ui.busy && ui.trips.size == 50) { Text("Next trips") }
            } }
        } else {
            val trip = ui.detail
            item {
                Text(trip.header.id, style = MaterialTheme.typography.titleMedium)
                Text("${trip.replay.session.status} · " + if (trip.replay.complete) "Complete event trace" else "Incomplete event trace",
                    color = if (trip.replay.complete) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
                Text("Events: ${trip.replay.eventCount} · saved images: ${trip.samples.size}")
                Text("Started: ${DateFormat.getDateTimeInstance().format(Date(trip.header.startedAtEpochMs))}")
                Text("Ended: ${trip.replay.session.endedAtEpochMs?.let { DateFormat.getDateTimeInstance().format(Date(it)) } ?: "unknown"}")
                trip.replay.session.recoveredAtEpochMs?.let { recovered ->
                    Text("Recovered: ${DateFormat.getDateTimeInstance().format(Date(recovered))}")
                }
                Text("End: ${trip.replay.session.endReason ?: "unknown"}")
                Text("Model: ${trip.header.modelVersion} · config v${trip.header.config.version} · ${trip.header.backend}")
                Text("Labels: ${trip.header.labels.sha256}", style = MaterialTheme.typography.bodySmall)
                Text("App ${trip.header.appVersion} · ${trip.header.device}")
                var expanded by remember(trip.header.id) { mutableStateOf(false) }
                TextButton(onClick = { expanded = !expanded }) { Text("Configuration and model metadata") }
                if (expanded) {
                    Text(TripArchive.json.encodeToString(EffectiveConfig.serializer(), trip.header.config), style = MaterialTheme.typography.bodySmall)
                    Text(trip.header.modelManifest, style = MaterialTheme.typography.bodySmall)
                }
            }
            item {
                Text("Images are unreviewed model observations, not training labels.")
                Text("Export includes all events and only checked images (${ui.selected.size} selected).")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = export, enabled = !ui.busy && !ui.pendingCleanup) { Text("Export ZIP") }
                    OutlinedButton(onClick = { deleteId = trip.header.id }, enabled = !ui.busy) { Text("Delete local trip") }
                }
            }
            if (trip.samples.isNotEmpty()) {
                item { Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = { model.selectAll(true) }, enabled = !ui.busy) { Text("Select all images") }
                    TextButton(onClick = { model.selectAll(false) }, enabled = !ui.busy) { Text("Clear selection") }
                } }
                items(trip.samples, key = { "${it.generation}/${it.frameId}" }) { sample ->
                    val key = SampleKey(sample.generation, sample.frameId)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Checkbox(checked = key in ui.selected, onCheckedChange = { model.select(key, it) }, enabled = !ui.busy)
                        Column(Modifier.weight(1f)) {
                            Text("Frame ${sample.frameId} · generation ${sample.generation} · ${sample.captureMs} ms")
                            Text("${sample.byteCount / 1000} kB · source #${sample.sourceSequence} · unreviewed", style = MaterialTheme.typography.bodySmall)
                        }
                        TextButton(onClick = { model.preview(sample) }, enabled = !ui.busy) { Text("View") }
                    }
                }
            }
            item {
                Text("Events and transition sources", style = MaterialTheme.typography.titleMedium)
                Row { Checkbox(checked = ui.includeTicks, onCheckedChange = { model.events(includeTicks = it) }, enabled = !ui.busy); Text("Show clock ticks") }
            }
            if (ui.events.isEmpty()) item { Text("No matching events. Clock ticks may be hidden.") }
            items(ui.events, key = { it.record.sequence }) { event -> EventCard(event, trip.header) }
            item { Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = { model.events() }, enabled = !ui.busy && ui.eventAfter >= 0) { Text("First events") }
                TextButton(onClick = { model.events(ui.events.last().record.sequence) }, enabled = !ui.busy && ui.events.size == 100) { Text("Next events") }
            } }
        }
    }
    deleteId?.let { id -> AlertDialog(onDismissRequest = { deleteId = null }, title = { Text("Delete local trip?") },
        text = { Text("This removes its local events and images. Previously exported copies remain outside the app.") },
        confirmButton = { TextButton(onClick = { deleteId = null; model.delete(id) }) { Text("Delete") } },
        dismissButton = { TextButton(onClick = { deleteId = null }) { Text("Cancel") } }) }
    if (dismissWarning) AlertDialog(onDismissRequest = { dismissWarning = false }, title = { Text("Dismiss export cleanup warning?") },
        text = { Text("An incomplete external file may remain. Remove it in Files; it is not a verified export.") },
        confirmButton = { TextButton(onClick = { dismissWarning = false; model.dismissExportCleanup() }) { Text("Dismiss") } },
        dismissButton = { TextButton(onClick = { dismissWarning = false }) { Text("Cancel") } })
}

@Composable private fun EventCard(row: ReviewedEvent, header: SessionHeader) {
    val names = remember(header.labels) { header.labels.classes.associate { it.id to it.name } }
    fun description(event: DomainEvent): String = when (val payload = event.payload) {
        is Payload.ConfirmedBatch -> "frame ${payload.frameId}: " + payload.signs.joinToString { "${names[it.classId]} ${String.format(Locale.ROOT, "%.0f%%", it.confidence * 100)}" }
        is Payload.Transition -> payload.reason
        is Payload.Conflict -> payload.reason
        is Payload.Error -> "${payload.reason}: ${payload.details}"
        is Payload.Gap -> "${payload.droppedEvents} missing events"
        else -> event.payload.toString()
    }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Text("#${row.record.sequence} · ${row.record.offsetMs} ms · ${row.record.kind}")
            Text(description(row.event))
            Text("Generation ${row.event.resetGeneration} · config v${row.event.config.version}", style = MaterialTheme.typography.bodySmall)
            row.sources.forEach { Text("Source #${it.sequence}: ${description(it)}", style = MaterialTheme.typography.bodySmall) }
            val sourceSequences = when (val payload = row.event.payload) {
                is Payload.Transition -> payload.sourceSequences
                is Payload.Conflict -> payload.sourceSequences
                is Payload.Error -> payload.sourceSequences
                else -> emptyList()
            }
            val missing = sourceSequences.filter { sequence -> row.sources.none { it.sequence == sequence } }
            if (missing.isNotEmpty()) Text("Missing source events: ${missing.joinToString()}", color = MaterialTheme.colorScheme.error)
            var expanded by remember(row.record.sequence) { mutableStateOf(false) }
            TextButton(onClick = { expanded = !expanded }) { Text("Event details") }
            if (expanded) Text(EventCodec.encode(row.event), style = MaterialTheme.typography.bodySmall)
        }
    }
}
