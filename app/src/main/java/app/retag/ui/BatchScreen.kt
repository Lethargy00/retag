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
import app.retag.data.TagData
import app.retag.data.TrackInfo
import app.retag.data.Naming
import androidx.compose.runtime.produceState
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import app.retag.data.Prefs
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
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
                            b.albumMode -> "Find album (${b.paths.size} songs)"
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
            SwitchRow("Replace all existing tags", b.overwrite, enabled) { b.overwrite = it }
            SwitchRow("Add cover art", b.fetchCover, enabled) { b.fetchCover = it }
            Text(
                if (b.overwrite) {
                    "Old tag data is wiped first (comments, lyrics and any other fields), so files only keep what the match has. The old cover stays only if no new one is found."
                } else {
                    "Only empty fields are filled in; everything already in the files is kept."
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
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
            if (item.warning.isNotBlank()) {
                Text(item.warning, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
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
    var showAll by remember { mutableStateOf(false) }

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
            val need = b.assignments.filter { it.track == null }
            val unsure = b.assignments.filter { it.track != null && !it.manual && it.confidence < UNSURE }
            val byYou = b.assignments.filter { it.track != null && it.manual }
            val sure = b.assignments.filter { it.track != null && !it.manual && it.confidence >= UNSURE }
            val toWrite = b.assignments.size - need.size
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
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        CountPill("${sure.size + unsure.size} found", MaterialTheme.colorScheme.primaryContainer, Modifier.weight(1f))
                        CountPill("${byYou.size} set by you", MaterialTheme.colorScheme.secondaryContainer, Modifier.weight(1f))
                        CountPill(
                            "${need.size} need a track",
                            if (need.isEmpty()) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.errorContainer,
                            Modifier.weight(1f),
                        )
                    }
                    Options(b)
                    LayoutPreview(vm, b)
                    SwitchRow("Show every field that changes", showAll, true) { showAll = it }
                    when {
                        b.running -> OutlinedButton(onClick = { b.cancel() }, modifier = Modifier.fillMaxWidth()) { Text("Stop") }
                        else -> Button(
                            onClick = { b.applyAlbum() },
                            enabled = toWrite > 0,
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text(if (b.finished) "Write tags again" else "Write tags to $toWrite files") }
                    }
                    if (need.isNotEmpty() && !b.running) {
                        Text(
                            "Files without a track are left unchanged. Tap one to pick its track.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    BusyAndError(b)
                    if (b.running || b.items.any { it.status != Status.Pending }) Progress(b)
                }
                HorizontalDivider()
            }
            fun section(title: String, list: List<Assignment>, color: @Composable () -> androidx.compose.ui.graphics.Color) {
                if (list.isEmpty()) return
                item(key = "h:$title") {
                    Text(
                        "$title (${list.size})",
                        style = MaterialTheme.typography.titleSmall,
                        color = color(),
                        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp),
                    )
                }
                items(list, key = { it.path }) { a ->
                    MatchRow(b, a, showAll) { pickingFor = a }
                    HorizontalDivider()
                }
            }
            section("Need a track", need) { MaterialTheme.colorScheme.error }
            section("Check these: unsure matches", unsure) { MaterialTheme.colorScheme.tertiary }
            section("Set by you", byYou) { MaterialTheme.colorScheme.secondary }
            section("Matched automatically", sure) { MaterialTheme.colorScheme.primary }
        }
    }

    val target = pickingFor
    if (target != null && al != null) {
        val used = b.assignments.filter { it.track != null && it.path != target.path }.associateBy({ it.track }, { it.path })
        val before = b.fileInfo(target.path)?.tags
        val fileSec = b.fileInfo(target.path)?.durationSec?.takeIf { it > 0 }
        AlertDialog(
            onDismissRequest = { pickingFor = null },
            title = {
                Column {
                    Text(Store.name(target.path), maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Length of this file: " + (length(fileSec) ?: "unknown"),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    if (before != null) {
                        Text(
                            "Now: " + describe(before),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
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
                    // Split the tracks so the ones still free are easy to find.
                    val current = target.track
                    val free = al.tracks.filter { it != current && used[it] == null }
                    val taken = al.tracks.filter { it != current && used[it] != null }
                    fun pick(t: TrackInfo) {
                        b.reassign(target.path, t)
                        pickingFor = null
                    }
                    if (current != null) {
                        PickerHeader("This file now")
                        TrackOption(current, owner = null, isCurrent = true, fileSec = fileSec) { pickingFor = null }
                    }
                    PickerHeader(if (free.isEmpty()) "No free tracks left" else "Free tracks (${free.size})")
                    free.forEach { t -> TrackOption(t, owner = null, isCurrent = false, fileSec = fileSec) { pick(t) } }
                    if (taken.isNotEmpty()) {
                        PickerHeader("Already matched to another file (${taken.size})")
                        taken.forEach { t -> TrackOption(t, owner = used[t], isCurrent = false, fileSec = fileSec) { pick(t) } }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { pickingFor = null }) { Text("Cancel") } },
        )
    }
}

private const val UNSURE = 0.6

@Composable
private fun PickerHeader(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 14.dp, bottom = 2.dp),
    )
}

/** "3:05", or null when unknown. */
private fun length(sec: Int?): String? = sec?.takeIf { it > 0 }?.let { "${it / 60}:${(it % 60).toString().padStart(2, '0')}" }

/**
 * One track in the "pick a track" dialog. [owner] = the file it's matched to now, if another one.
 * [fileSec] = the file's own length, to point out tracks that are about as long.
 */
@Composable
private fun TrackOption(t: TrackInfo, owner: String?, isCurrent: Boolean, fileSec: Int?, onClick: () -> Unit) {
    val dur = length(t.durationSec)?.let { " ($it)" }.orEmpty()
    val diff = if (fileSec != null && t.durationSec != null && t.durationSec > 0) kotlin.math.abs(t.durationSec - fileSec) else null
    Column(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp),
    ) {
        Text(
            trackLabel(t.tags) + dur,
            fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Normal,
            color = if (owner != null) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
        )
        when {
            diff == null -> Unit
            diff <= 3 -> Text("Same length as this file", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
            diff <= 10 -> Text("Close in length (${diff} s off)", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.tertiary)
        }
        if (owner != null) {
            Text(
                "On ${Store.name(owner)}. Picking it moves it here.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

private fun trackLabel(t: TagData): String {
    fun n(s: String) = s.substringBefore('/').trim().toIntOrNull() ?: 0
    // Only show the disc when the album has more than one.
    val disc = if (n(t.disc) > 1 || n(t.discTotal) > 1) "${n(t.disc)}-" else ""
    val num = t.track.ifBlank { "?" }
    return "$disc$num. ${t.title}"
}

private fun describe(t: TagData): String =
    listOf(t.title.ifBlank { "(no title)" }, t.artist, t.track.takeIf { it.isNotBlank() }?.let { "track $it" }.orEmpty())
        .filter { it.isNotBlank() }
        .joinToString(" · ")

/** Fields that differ between [before] and [after], as (label, old, new). */
private fun changes(before: TagData, after: TagData): List<Triple<String, String, String>> {
    fun num(n: String, total: String) = if (total.isBlank()) n else "$n/$total"
    return listOf(
        Triple("Title", before.title, after.title),
        Triple("Artist", before.artist, after.artist),
        Triple("Album artist", before.albumArtist, after.albumArtist),
        Triple("Album", before.album, after.album),
        Triple("Year", before.year, after.year),
        Triple("Track", num(before.track, before.trackTotal), num(after.track, after.trackTotal)),
        Triple("Disc", num(before.disc, before.discTotal), num(after.disc, after.discTotal)),
        Triple("Genre", before.genre, after.genre),
        Triple("Comment", before.comment, after.comment),
        Triple("Lyrics", if (before.lyrics.isBlank()) "" else "(lyrics)", if (after.lyrics.isBlank()) "" else "(lyrics)"),
    ).filter { it.second.trim() != it.third.trim() }
}

@Composable
private fun CountPill(text: String, color: androidx.compose.ui.graphics.Color, modifier: Modifier = Modifier) {
    Surface(color = color, shape = RoundedCornerShape(8.dp), modifier = modifier) {
        Text(
            text,
            style = MaterialTheme.typography.labelMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
        )
    }
}

@Composable
private fun MatchRow(b: BatchController, a: Assignment, showAll: Boolean, onClick: () -> Unit) {
    val item = b.items.firstOrNull { it.key == a.path }
    val before = b.fileInfo(a.path)?.tags
    val after = b.preview(a)
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(enabled = !b.running, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.Top,
    ) {
        StatusIcon(item?.status ?: Status.Pending)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                item?.fileName ?: Store.name(a.path),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                "Before: " + (before?.let(::describe) ?: "…") +
                    (length(b.fileInfo(a.path)?.durationSec)?.let { " · $it" }.orEmpty()),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (a.track != null && after != null) {
                Text(
                    "After: " + trackLabel(after) + (length(a.track.durationSec)?.let { " · $it" }.orEmpty()),
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (showAll && before != null) {
                    val diff = changes(before, after)
                    val cover = b.coverEffect(a)
                    if (diff.isEmpty() && cover == null) {
                        Text("No changes", style = MaterialTheme.typography.bodySmall)
                    }
                    for ((label, old, new) in diff) {
                        Text(
                            "$label: ${old.ifBlank { "–" }} → ${new.ifBlank { "–" }}",
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    if (cover != null) Text("Cover: $cover", style = MaterialTheme.typography.bodySmall)
                    if (b.overwrite) {
                        Text(
                            "Any other old fields: removed",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            } else {
                Text("Tap to pick a track", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
            }
            if (item != null && item.message.isNotBlank() && item.status != Status.Done) {
                Text(item.message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
            if (item != null && item.warning.isNotBlank()) {
                Text(item.warning, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
        }
        when {
            a.track == null -> Unit
            a.manual -> Text("you", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.secondary, modifier = Modifier.width(40.dp))
            else -> Text(
                "${(a.confidence * 100).roundToInt()}%",
                style = MaterialTheme.typography.labelMedium,
                color = if (a.confidence < UNSURE) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.width(40.dp),
            )
        }
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

/** One file's place after writing: folder levels below the music folder, and its file name. */
private class Planned(val folders: List<String>, val name: String, val changed: Boolean, val order: Int)

/** What's already on storage at a planned folder. */
private class Existing(val exists: Boolean, val otherNames: Set<String>, val otherCount: Int)

/** Shows (and lets you switch) renaming and Artist/Album folders, with the resulting folder tree. */
@Composable
private fun LayoutPreview(vm: AppViewModel, b: BatchController) {
    val rename = Prefs.renameOn
    val folders = Prefs.organizeOn
    val currentIds = b.items.map { it.path }
    val raw = remember(b.assignments, currentIds, b.files, rename, folders, Prefs.renamePattern, Prefs.folderPattern, b.overwrite) {
        b.assignments.map { a ->
            val cur = b.items.firstOrNull { it.key == a.path }?.path ?: a.path
            val curName = Store.name(cur)
            val ext = curName.substringAfterLast('.', "")
            val curFolders = Store.relFolder(cur).split('/').filter { it.isNotBlank() }
            val after = b.preview(a)
            if (after == null) {
                Planned(curFolders, curName, false, Int.MAX_VALUE)
            } else {
                val f = if (folders) Naming.folders(after) else curFolders
                val base = if (rename) Naming.fileBase(after) else null
                val n = base?.let { if (ext.isEmpty()) it else "$it.$ext" } ?: curName
                val disc = after.disc.substringBefore('/').trim().toIntOrNull() ?: 0
                val track = after.track.substringBefore('/').trim().toIntOrNull() ?: 999
                Planned(f, n, f != curFolders || n != curName, disc * 1000 + track)
            }
        }
    }
    val folderKeys = remember(raw) { raw.map { it.folders }.distinct() }

    // Look at what's already in the target folders (other files are kept; names that clash get " (2)").
    val existing by produceState<Map<List<String>, Existing>>(emptyMap(), folderKeys, currentIds) {
        value = withContext(Dispatchers.IO) {
            val mine = currentIds.toSet()
            folderKeys.associateWith { levels ->
                try {
                    var id = Store.rootId()
                    var found = true
                    for (level in levels) {
                        val next = Store.children(id).firstOrNull { it.isDir && it.name.equals(level, ignoreCase = true) }
                        if (next == null) {
                            found = false
                            break
                        }
                        id = next.id
                    }
                    if (!found) {
                        Existing(false, emptySet(), 0)
                    } else {
                        val others = Store.children(id).filter { !it.isDir && it.id !in mine && !it.name.startsWith(".") }
                        Existing(true, others.mapTo(HashSet()) { it.name.lowercase() }, others.size)
                    }
                } catch (e: Exception) {
                    Existing(false, emptySet(), 0)
                }
            }
        }
    }

    val plan = remember(raw, existing) {
        val taken = HashMap<List<String>, MutableSet<String>>()
        raw.sortedWith(compareBy({ it.folders.joinToString("/").lowercase() }, { it.order }, { it.name.lowercase() })).map { p ->
            val used = taken.getOrPut(p.folders) { HashSet(existing[p.folders]?.otherNames ?: emptySet()) }
            var name = p.name
            if (name.lowercase() in used) {
                val dot = name.lastIndexOf('.')
                val base = if (dot > 0) name.substring(0, dot) else name
                val ext = if (dot > 0) name.substring(dot) else ""
                var n = 2
                while ("$base ($n)$ext".lowercase() in used) n++
                name = "$base ($n)$ext"
            }
            used.add(name.lowercase())
            if (name == p.name) p else Planned(p.folders, name, true, p.order)
        }.groupBy { it.folders }
    }

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text("Files & folders", style = MaterialTheme.typography.titleSmall)
            SwitchRow("Rename files  (${Prefs.renamePattern})", rename, !b.running) { Prefs.renameOn = it }
            SwitchRow("Put in folders  (${Prefs.folderPattern})", folders, !b.running) { Prefs.organizeOn = it }
            Text(
                "Same switches as in Settings, where the patterns can be changed. Existing folders are reused and " +
                    "files already in them are never overwritten.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            Text("Result", style = MaterialTheme.typography.labelLarge)
            for ((path, files) in plan) {
                val levels = listOf(vm.folderName.ifBlank { "Music" }) + path
                val ex = existing[path]
                levels.forEachIndexed { i, name ->
                    val last = i == levels.lastIndex
                    TreeRow(
                        depth = i,
                        icon = RetagIcons.Folder,
                        iconTint = MaterialTheme.colorScheme.primary,
                        text = name,
                        bold = last,
                        badge = when {
                            !last || i == 0 || ex == null -> null
                            !ex.exists -> "new"
                            else -> "exists"
                        },
                    )
                }
                for (f in files) {
                    TreeRow(
                        depth = levels.size,
                        icon = RetagIcons.MusicNote,
                        iconTint = if (f.changed) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                        text = f.name,
                        dim = !f.changed,
                        badge = if (f.changed) null else "unchanged",
                    )
                }
                if (ex != null && ex.otherCount > 0) {
                    TreeRow(
                        depth = levels.size,
                        icon = null,
                        iconTint = MaterialTheme.colorScheme.onSurfaceVariant,
                        text = "+ ${ex.otherCount} file${if (ex.otherCount == 1) "" else "s"} already here, kept as they are",
                        dim = true,
                    )
                }
                Spacer(Modifier.height(6.dp))
            }
            if (!rename && !folders) {
                Text(
                    "Files keep their names and stay where they are; only the tags inside change.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun TreeRow(
    depth: Int,
    icon: ImageVector?,
    iconTint: Color,
    text: String,
    bold: Boolean = false,
    dim: Boolean = false,
    badge: String? = null,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(start = (depth * 16).dp, top = 2.dp, bottom = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = iconTint, modifier = Modifier.size(18.dp))
        } else {
            Spacer(Modifier.width(18.dp))
        }
        Text(
            text,
            style = if (bold) MaterialTheme.typography.bodyMedium else MaterialTheme.typography.bodySmall,
            fontWeight = if (bold) FontWeight.Medium else FontWeight.Normal,
            color = if (dim) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
        if (badge != null) {
            Surface(
                color = if (badge == "new") MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
                shape = RoundedCornerShape(4.dp),
            ) {
                Text(badge, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp))
            }
        }
    }
}
