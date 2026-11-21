package app.retag.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.retag.AppViewModel
import app.retag.BatchController
import app.retag.BatchItem
import app.retag.Screen
import app.retag.Status
import app.retag.data.Assignment
import app.retag.data.Store
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BatchScreen(vm: AppViewModel) {
    val b = vm.batch
    val view = LocalView.current
    val running = b?.running == true
    DisposableEffect(running) {
        // Keep the screen awake while tagging so Android doesn't pause the app.
        view.keepScreenOn = running
        onDispose { view.keepScreenOn = false }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        when {
                            b == null -> "Auto-tag"
                            b.albumMode -> "Tag as album (${b.paths.size} files)"
                            else -> "Auto-tag ${b.paths.size} songs"
                        }
                    )
                },
                navigationIcon = {
                    IconButton(onClick = { vm.back() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { pad ->
        Box(Modifier.padding(pad).fillMaxSize()) {
            when {
                b == null -> Text("Nothing to do.", Modifier.padding(24.dp))
                b.albumMode -> AlbumMode(vm, b)
                else -> PerSongMode(vm, b)
            }
        }
    }
}

@Composable
private fun Options(b: BatchController) {
    val enabled = !b.running
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
            SwitchRow("Replace existing tags", b.overwrite, enabled) { b.overwrite = it }
            SwitchRow("Add cover art", b.fetchCover, enabled) { b.fetchCover = it }
            if (!b.overwrite) {
                Text(
                    "Only empty fields will be filled in.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun Progress(b: BatchController) {
    val total = b.items.size.coerceAtLeast(1)
    val done = b.items.count { it.status == Status.Done }
    val skipped = b.items.count { it.status == Status.Skipped }
    val failed = b.items.count { it.status == Status.Failed }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        LinearProgressIndicator(progress = { b.doneCount.toFloat() / total }, modifier = Modifier.fillMaxWidth())
        Text(
            "$done tagged · $skipped skipped · $failed failed · ${b.items.size - b.doneCount} left",
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

@Composable
private fun StatusIcon(status: Status) {
    Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) {
        when (status) {
            Status.Pending -> Text("•", color = MaterialTheme.colorScheme.onSurfaceVariant)
            Status.Working -> CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
            Status.Done -> Icon(Icons.Default.CheckCircle, contentDescription = "Done", tint = MaterialTheme.colorScheme.primary)
            Status.Skipped -> Icon(Icons.Default.Info, contentDescription = "Skipped", tint = MaterialTheme.colorScheme.tertiary)
            Status.Failed -> Icon(Icons.Default.Warning, contentDescription = "Failed", tint = MaterialTheme.colorScheme.error)
        }
    }
}

@Composable
private fun ItemRow(item: BatchItem, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        StatusIcon(item.status)
        Column(Modifier.weight(1f)) {
            Text(item.fileName, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (item.message.isNotBlank()) {
                Text(
                    item.message,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (item.status == Status.Failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun PerSongMode(vm: AppViewModel, b: BatchController) {
    val started = b.running || b.items.any { it.status != Status.Pending }
    LazyColumn(Modifier.fillMaxSize()) {
        item {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    "Each song is looked up on MusicBrainz and iTunes. Confident matches are saved straight away; " +
                        "uncertain ones are skipped so you can pick by hand (tap them).",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Options(b)
                when {
                    b.running -> OutlinedButton(onClick = { b.cancel() }, modifier = Modifier.fillMaxWidth()) { Text("Stop") }
                    !started -> Button(onClick = { b.startPerSong() }, modifier = Modifier.fillMaxWidth()) { Text("Start") }
                    else -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (b.items.any { it.status == Status.Pending }) {
                            Button(onClick = { b.startPerSong() }, modifier = Modifier.weight(1f)) { Text("Continue") }
                        }
                        if (b.items.any { it.status == Status.Failed || it.status == Status.Skipped }) {
                            OutlinedButton(onClick = { b.startPerSong(onlyRetry = true) }, modifier = Modifier.weight(1f)) {
                                Text("Retry skipped/failed")
                            }
                        }
                    }
                }
                if (started) Progress(b)
                if (b.running) {
                    Text(
                        "MusicBrainz allows about one search per second, so this takes a few seconds per song. " +
                            "You can leave this screen; it keeps going while the app is open.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            HorizontalDivider()
        }
        items(b.items, key = { it.key }) { item ->
            ItemRow(item) { if (!b.running || item.status != Status.Working) vm.open(Screen.Editor(item.path)) }
        }
    }
}

@Composable
private fun AlbumMode(vm: AppViewModel, b: BatchController) {
    val al = b.album
    var pickingFor by remember { mutableStateOf<Assignment?>(null) }

    LazyColumn(Modifier.fillMaxSize()) {
        if (al == null) {
            item {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "Find the album these files belong to. Every file is then matched to a track and tagged consistently.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    TagField("Album", b.albumQuery) { b.albumQuery = it }
                    TagField("Artist", b.artistQuery) { b.artistQuery = it }
                    Button(
                        onClick = { b.searchAlbums() },
                        enabled = b.busy == null && b.albumQuery.isNotBlank(),
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Search albums") }
                    BusyAndError(b)
                    if (b.albumCandidates.isNotEmpty()) {
                        Text(
                            "You have ${b.paths.size} files. Pick the matching release:",
                            style = MaterialTheme.typography.titleSmall,
                        )
                    }
                }
            }
            items(b.albumCandidates) { c ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable(enabled = b.busy == null) { b.chooseAlbum(c) }
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RemoteCover(c.coverThumb, 64.dp)
                    Column(Modifier.weight(1f)) {
                        Text(c.title, style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(
                            listOf(c.artist, c.year).filter { it.isNotBlank() }.joinToString(" · "),
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        val countText = if (c.trackCount > 0) "${c.trackCount} tracks" else ""
                        Text(
                            listOf(countText, c.detail, c.source).filter { it.isNotBlank() }.joinToString(" · "),
                            style = MaterialTheme.typography.bodySmall,
                            color = if (c.trackCount == b.paths.size) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                            fontWeight = if (c.trackCount == b.paths.size) FontWeight.Bold else FontWeight.Normal,
                        )
                    }
                }
                HorizontalDivider()
            }
        } else {
            item {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        RemoteCover(al.coverThumb, 72.dp)
                        Column(Modifier.weight(1f)) {
                            Text(al.title, style = MaterialTheme.typography.titleMedium)
                            Text(listOf(al.artist, al.year).filter { it.isNotBlank() }.joinToString(" · "))
                            Text(
                                "${al.tracks.size} tracks · ${al.source}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        TextButton(onClick = { b.clearAlbum() }, enabled = !b.running) { Text("Change") }
                    }
                    Options(b)
                    val matched = b.assignments.count { it.track != null }
                    Text(
                        "$matched of ${b.assignments.size} files matched to a track. Tap a file to change its track.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    when {
                        b.running -> OutlinedButton(onClick = { b.cancel() }, modifier = Modifier.fillMaxWidth()) { Text("Stop") }
                        else -> Button(
                            onClick = { b.applyAlbum() },
                            enabled = matched > 0,
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text(if (b.finished) "Write tags again" else "Write tags to $matched files") }
                    }
                    BusyAndError(b)
                    if (b.running || b.items.any { it.status != Status.Pending }) Progress(b)
                }
                HorizontalDivider()
            }
            items(b.assignments, key = { it.path }) { a ->
                val item = b.items.firstOrNull { it.key == a.path }
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable(enabled = !b.running) { pickingFor = a }
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    StatusIcon(item?.status ?: Status.Pending)
                    Column(Modifier.weight(1f)) {
                        Text(Store.name(a.path), maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)
                        val t = a.track
                        if (t != null) {
                            val disc = if (t.tags.disc.isNotBlank()) "${t.tags.disc}-" else ""
                            Text(
                                "→ $disc${t.tags.track}. ${t.tags.title}",
                                style = MaterialTheme.typography.bodyLarge,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        } else {
                            Text("→ not matched (won't be changed)", color = MaterialTheme.colorScheme.error)
                        }
                        if (item != null && item.message.isNotBlank() && item.status != Status.Done) {
                            Text(item.message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                        }
                    }
                    if (a.track != null) {
                        Text(
                            "${(a.confidence * 100).roundToInt()}%",
                            style = MaterialTheme.typography.labelMedium,
                            color = if (a.confidence < 0.6) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.width(40.dp),
                        )
                    }
                }
                HorizontalDivider()
            }
        }
    }

    val target = pickingFor
    if (target != null && al != null) {
        AlertDialog(
            onDismissRequest = { pickingFor = null },
            title = { Text(Store.name(target.path), maxLines = 2, overflow = TextOverflow.Ellipsis) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    run {
                        Text(
                            "Don't tag this file",
                            Modifier
                                .fillMaxWidth()
                                .clickable {
                                    b.reassign(target.path, null)
                                    pickingFor = null
                                }
                                .padding(vertical = 10.dp),
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                    al.tracks.forEach { t ->
                        val disc = if (t.tags.disc.isNotBlank()) "${t.tags.disc}-" else ""
                        val dur = t.durationSec?.let { " (${it / 60}:${(it % 60).toString().padStart(2, '0')})" }.orEmpty()
                        Text(
                            "$disc${t.tags.track}. ${t.tags.title}$dur",
                            Modifier
                                .fillMaxWidth()
                                .clickable {
                                    b.reassign(target.path, t)
                                    pickingFor = null
                                }
                                .padding(vertical = 10.dp),
                            fontWeight = if (target.track == t) FontWeight.Bold else FontWeight.Normal,
                        )
                    }
                }
            },
            confirmButton = { TextButton(onClick = { pickingFor = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun BusyAndError(b: BatchController) {
    b.busy?.let {
        LinearProgressIndicator(Modifier.fillMaxWidth())
        Text(it, style = MaterialTheme.typography.bodySmall)
    }
    b.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
}
