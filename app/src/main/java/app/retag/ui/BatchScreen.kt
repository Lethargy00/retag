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
import app.retag.data.Naming
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
        AlertDialog(
            onDismissRequest = { pickingFor = null },
            title = {
                Column {
                    Text(Store.name(target.path), maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleMedium)
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
                    al.tracks.forEach { t ->
                        val dur = t.durationSec?.let { " (${it / 60}:${(it % 60).toString().padStart(2, '0')})" }.orEmpty()
                        val owner = used[t]
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .clickable {
                                    b.reassign(target.path, t)
                                    pickingFor = null
                                }
                                .padding(vertical = 8.dp),
                        ) {
                            Text(
                                trackLabel(t.tags) + dur,
                                fontWeight = if (target.track == t) FontWeight.Bold else FontWeight.Normal,
                                color = if (owner != null) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                            )
                            if (owner != null) {
                                Text(
                                    "Now on ${Store.name(owner)}; picking it moves it here",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { pickingFor = null }) { Text("Cancel") } },
        )
    }
}

private const val UNSURE = 0.6

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
                "Before: " + (before?.let(::describe) ?: "…"),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (a.track != null && after != null) {
                Text(
                    "After: " + trackLabel(after),
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

/** Shows (and lets you switch) renaming and Artist/Album folders, with the resulting folder tree. */
@Composable
private fun LayoutPreview(vm: AppViewModel, b: BatchController) {
    val rename = Prefs.renameOn
    val folders = Prefs.organizeOn
    val plan = remember(b.assignments, b.items.toList(), b.files, rename, folders, Prefs.renamePattern, Prefs.folderPattern, b.overwrite) {
        val out = b.assignments.map { a ->
            val item = b.items.firstOrNull { it.key == a.path }
            val cur = item?.path ?: a.path
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
        // Same name in the same folder: the app adds " (2)", " (3)"…
        val seen = HashMap<String, Int>()
        out.sortedWith(compareBy({ it.folders.joinToString("/").lowercase() }, { it.order }, { it.name.lowercase() })).map { p ->
            val k = (p.folders + p.name).joinToString("/").lowercase()
            val count = (seen[k] ?: 0) + 1
            seen[k] = count
            if (count == 1) {
                p
            } else {
                val dot = p.name.lastIndexOf('.')
                val nn = if (dot > 0) p.name.substring(0, dot) + " ($count)" + p.name.substring(dot) else p.name + " ($count)"
                Planned(p.folders, nn, true, p.order)
            }
        }.groupBy { it.folders }
    }

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text("Files & folders", style = MaterialTheme.typography.titleSmall)
            SwitchRow("Rename files  (${Prefs.renamePattern})", rename, !b.running) { Prefs.renameOn = it }
            SwitchRow("Put in folders  (${Prefs.folderPattern})", folders, !b.running) { Prefs.organizeOn = it }
            Text(
                "These are the same switches as in Settings. Patterns can be changed there.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(6.dp))
            Text("Result", style = MaterialTheme.typography.labelLarge)
            for ((path, files) in plan) {
                val levels = listOf(vm.folderName.ifBlank { "Music" }) + path
                levels.forEachIndexed { i, name ->
                    Text(
                        "    ".repeat(i) + "📁 " + name,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = if (i == levels.lastIndex) FontWeight.Medium else FontWeight.Normal,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                val indent = "    ".repeat(levels.size)
                for (f in files) {
                    Text(
                        indent + "♪ " + f.name + if (f.changed) "" else "  (unchanged)",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (f.changed) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Spacer(Modifier.height(4.dp))
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
