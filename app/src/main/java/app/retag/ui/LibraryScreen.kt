package app.retag.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.BottomAppBar
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.activity.compose.BackHandler
import app.retag.AppViewModel
import app.retag.Screen
import app.retag.data.Song
import app.retag.data.Library
import app.retag.data.Thumbs
import androidx.compose.foundation.Image
import androidx.compose.runtime.produceState
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import app.retag.data.Naming
import app.retag.data.Prefs
import app.retag.data.SortBy
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.composed
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.semantics
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import androidx.compose.material3.AssistChip
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun LibraryScreen(vm: AppViewModel) {
    var query by rememberSaveable { mutableStateOf("") }
    var onlyMissing by rememberSaveable { mutableStateOf(false) }
    val selection = vm.selection
    val songs = vm.songs

    val sort = Prefs.sort
    var sortMenu by remember { mutableStateOf(false) }
    var selectMenu by remember { mutableStateOf(false) }
    var confirmOrganize by remember { mutableStateOf<List<String>?>(null) }
    val filtered = remember(songs, query, onlyMissing, sort) {
        Library.sort(
            songs.filter { s ->
                (!onlyMissing || s.missingTags) &&
                    (query.isBlank() || listOf(s.title, s.artist, s.album, s.fileName).any { it.contains(query, ignoreCase = true) })
            },
            sort,
        )
    }

    BackHandler(enabled = selection.isNotEmpty()) { selection.clear() }

    fun toggle(path: String) {
        if (path in selection) selection.remove(path) else selection.add(path)
    }

    val listState = rememberLazyListState()
    /** True from the long-press until the finger lifts, so the row's tap on release is ignored. */
    var dragSelecting by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            if (selection.isEmpty()) {
                TopAppBar(
                    title = { Text("Retag") },
                    actions = {
                        IconButton(onClick = { vm.loadLibrary() }, enabled = !vm.loading) {
                            Icon(Icons.Default.Refresh, contentDescription = "Rescan folder")
                        }
                        IconButton(onClick = { vm.open(Screen.Settings) }) {
                            Icon(Icons.Default.Settings, contentDescription = "Settings")
                        }
                    },
                )
            } else {
                TopAppBar(
                    navigationIcon = {
                        IconButton(onClick = { selection.clear() }) { Icon(Icons.Default.Close, contentDescription = "Clear selection") }
                    },
                    title = { Text("${selection.size} selected") },
                    actions = {
                        TextButton(onClick = {
                            selection.clear()
                            selection.addAll(filtered.map { it.path })
                        }) { Text("Select all") }
                        Box {
                            IconButton(onClick = { selectMenu = true }) {
                                Icon(Icons.Default.MoreVert, contentDescription = "More actions")
                            }
                            DropdownMenu(expanded = selectMenu, onDismissRequest = { selectMenu = false }) {
                                DropdownMenuItem(
                                    text = { Text("Rename & organize") },
                                    onClick = {
                                        selectMenu = false
                                        confirmOrganize = selection.toList()
                                    },
                                )
                            }
                        }
                    },
                )
            }
        },
        bottomBar = {
            if (selection.isNotEmpty()) {
                BottomAppBar {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Button(
                            onClick = {
                                val paths = selection.toList()
                                selection.clear()
                                vm.startBatch(paths, albumMode = true)
                            },
                            modifier = Modifier.weight(1.3f),
                        ) { Text("Find album", maxLines = 1) }
                        OutlinedButton(
                            onClick = {
                                val paths = selection.toList()
                                selection.clear()
                                vm.startBatch(paths, albumMode = false)
                            },
                            modifier = Modifier.weight(1f),
                        ) { Text("Auto-tag", maxLines = 1) }
                        OutlinedButton(
                            onClick = {
                                val paths = selection.toList()
                                selection.clear()
                                vm.open(Screen.BulkEdit(paths))
                            },
                            modifier = Modifier.weight(1f),
                        ) { Text("Edit all") }
                    }
                }
            }
        },
    ) { pad ->
        Column(
            Modifier
                .padding(pad)
                .fillMaxSize()
        ) {
            vm.organize?.let { o ->
                Surface(
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(enabled = !o.running) { vm.dismissOrganize() },
                ) {
                    Text(
                        if (o.running) "Renaming & organizing: ${o.done} of ${o.total}…" else (o.result.orEmpty() + "\nTap to dismiss."),
                        Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                    )
                }
            }
            val b = vm.batch
            if (b != null && b.running) {
                Surface(
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { vm.open(Screen.Batch) },
                ) {
                    Text(
                        "Tagging in progress: ${b.doneCount} of ${b.items.size}. Tap to view.",
                        Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                    )
                }
            }

            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                placeholder = { Text("Search title, artist, album, file") },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                trailingIcon = {
                    if (query.isNotEmpty()) {
                        IconButton(onClick = { query = "" }) { Icon(Icons.Default.Clear, contentDescription = "Clear search") }
                    }
                },
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 6.dp),
            )
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Row(
                    Modifier
                        .weight(1f)
                        .horizontalScroll(rememberScrollState())
                        .padding(start = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    FilterChip(
                        selected = onlyMissing,
                        onClick = { onlyMissing = !onlyMissing },
                        label = { Text("Missing tags") },
                    )
                    Box {
                        AssistChip(
                            onClick = { sortMenu = true },
                            label = { Text(sort.label, maxLines = 1) },
                            trailingIcon = { Icon(Icons.Default.ArrowDropDown, contentDescription = null) },
                        )
                        DropdownMenu(expanded = sortMenu, onDismissRequest = { sortMenu = false }) {
                            for (option in SortBy.entries) {
                                DropdownMenuItem(
                                    text = { Text(option.label) },
                                    leadingIcon = { if (option == sort) Icon(Icons.Default.Check, contentDescription = null) },
                                    onClick = {
                                        Prefs.sort = option
                                        sortMenu = false
                                    },
                                )
                            }
                        }
                    }
                }
                // Outside the scrolling chips, so the count always stays on screen.
                Text(
                    if (filtered.size == 1) "1 song" else "${filtered.size} songs",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    modifier = Modifier.padding(start = 8.dp, end = 16.dp),
                )
            }

            when {
                vm.loading && songs.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        CircularProgressIndicator()
                        Text("Looking for music in ${vm.folderName}…")
                    }
                }
                vm.scannedOnce && songs.isEmpty() -> Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                    Text("No music files found. Supported: MP3, M4A, FLAC, OGG, OPUS, WMA, WAV, AIFF, DSF.")
                }
                else -> LazyColumn(
                    Modifier
                        .fillMaxSize()
                        .dragToSelect(
                            listState = listState,
                            songs = filtered,
                            selection = selection,
                            onActive = { dragSelecting = it },
                        ),
                    state = listState,
                ) {
                    vm.loadingDetail?.let { d ->
                        item(key = "__detail") { Text(d, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) }
                    }
                    // Always there (only the text changes), so the list doesn't jump under your finger when selecting starts.
                    if (filtered.isNotEmpty()) {
                        item(key = "__hint") {
                            Text(
                                if (selection.isEmpty()) {
                                    "Tap a song to edit it. Hold a song to select it, and keep holding while you drag up or down to select more."
                                } else {
                                    "Tap songs to add or remove them, or hold and drag to select a range."
                                },
                                maxLines = 2,
                                minLines = 2,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                            )
                        }
                    }
                    items(filtered, key = { it.path }) { s ->
                        SongRow(
                            song = s,
                            selecting = selection.isNotEmpty(),
                            selected = s.path in selection,
                            onClick = {
                                when {
                                    dragSelecting -> Unit // the finger lifting after a hold-to-select
                                    selection.isNotEmpty() -> toggle(s.path)
                                    else -> vm.open(Screen.Editor(s.path))
                                }
                            },
                            onSelect = { toggle(s.path) },
                        )
                    }
                }
            }
        }
    }

    confirmOrganize?.let { ids ->
        OrganizeDialog(
            count = ids.size,
            onConfirm = { rename, folders ->
                confirmOrganize = null
                selection.clear()
                vm.organizeFiles(ids, rename, folders)
            },
            onDismiss = { confirmOrganize = null },
        )
    }
}

@Composable
private fun SongRow(
    song: Song,
    selecting: Boolean,
    selected: Boolean,
    onClick: () -> Unit,
    /** For accessibility services; touch uses the list's hold-and-drag. */
    onSelect: () -> Unit,
    indent: Boolean = false,
) {
    val subtitle = if (song.missingTags) {
        "Missing tags · ${song.fileName}"
    } else {
        listOf(song.artist, song.album).filter { it.isNotBlank() }.joinToString(" · ")
    }
    ListItem(
        modifier = Modifier
            // Holding is handled by the list (dragToSelect), so holding and dragging can select many songs.
            .semantics {
                onLongClick(label = "Select") {
                    onSelect()
                    true
                }
            }
            .clickable(onClick = onClick)
            .padding(start = if (indent) 16.dp else 0.dp),
        headlineContent = { Text(song.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        supportingContent = {
            Text(
                subtitle,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = if (song.missingTags) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
        leadingContent = {
            val version = Thumbs.versions[song.path] ?: 0
            val thumb by produceState<ImageBitmap?>(null, song.path, version) {
                value = Thumbs.load(song.path)?.asImageBitmap()
            }
            val art = thumb
            if (selecting) {
                Checkbox(checked = selected, onCheckedChange = null)
            } else if (art != null) {
                Image(
                    bitmap = art,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .size(40.dp)
                        .clip(RoundedCornerShape(8.dp)),
                )
            } else {
                Box(
                    Modifier
                        .size(40.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(MaterialTheme.colorScheme.primaryContainer),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        song.title.firstOrNull { it.isLetterOrDigit() }?.uppercase() ?: "♪",
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        style = MaterialTheme.typography.titleMedium,
                    )
                }
            }
        },
        colors = if (selected) {
            ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
        } else {
            ListItemDefaults.colors()
        },
    )
}

@Composable
private fun OrganizeDialog(count: Int, onConfirm: (rename: Boolean, folders: Boolean) -> Unit, onDismiss: () -> Unit) {
    var rename by remember { mutableStateOf(true) }
    var folders by remember { mutableStateOf(true) }
    val example = buildString {
        if (folders) Naming.folders(Naming.SAMPLE).forEach { append(it).append("/") }
        append(if (rename) Naming.fileBase(Naming.SAMPLE) ?: "Song" else "current name")
        append(".mp3")
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Rename & organize $count files") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Uses each file's current tags and the patterns in Settings.")
                SwitchRow("Rename files", rename, true) { rename = it }
                SwitchRow("Move into artist/album folders", folders, true) { folders = it }
                Text(
                    "Example: $example",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(rename, folders) }, enabled = rename || folders) { Text("Go") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/**
 * Hold a song to select it; keep holding and drag up or down to select (or, if you started on a
 * selected song, unselect) every song in between. Near the top or bottom edge the list scrolls by itself.
 */
private fun Modifier.dragToSelect(
    listState: LazyListState,
    songs: List<Song>,
    selection: SnapshotStateList<String>,
    onActive: (Boolean) -> Unit,
): Modifier = composed {
    val haptics = LocalHapticFeedback.current
    val edge = with(LocalDensity.current) { 72.dp.toPx() }
    val maxStep = with(LocalDensity.current) { 14.dp.toPx() }
    val currentSongs by rememberUpdatedState(songs)
    val index = remember(songs) { HashMap<String, Int>(songs.size * 2).apply { songs.forEachIndexed { i, s -> put(s.path, i) } } }
    val currentIndex by rememberUpdatedState(index)
    val currentOnActive by rememberUpdatedState(onActive)

    // Drag state
    var anchor by remember { mutableStateOf(-1) }
    var last by remember { mutableStateOf(-1) }
    var selecting by remember { mutableStateOf(true) }
    var before by remember { mutableStateOf<List<String>>(emptyList()) }
    var pointerY by remember { mutableFloatStateOf(0f) }
    var scrollSpeed by remember { mutableFloatStateOf(0f) }

    fun songAt(y: Float): Int? {
        val item = listState.layoutInfo.visibleItemsInfo.firstOrNull { y >= it.offset && y < it.offset + it.size } ?: return null
        return (item.key as? String)?.let { currentIndex[it] }
    }

    /** Selection = what was selected before the drag, plus (or minus) everything from the anchor to [to]. */
    fun extendTo(to: Int) {
        if (anchor < 0 || to == last) return
        val list = currentSongs
        if (list.isEmpty()) return
        last = to
        val lo = minOf(anchor, to).coerceIn(0, list.lastIndex)
        val hi = maxOf(anchor, to).coerceIn(0, list.lastIndex)
        val range = LinkedHashSet<String>()
        for (i in lo..hi) range += list[i].path
        val result = if (selecting) {
            val kept = LinkedHashSet(before)
            kept.addAll(range)
            kept.toList()
        } else {
            before.filter { it !in range }
        }
        selection.clear()
        selection.addAll(result)
    }

    fun follow() {
        songAt(pointerY)?.let { extendTo(it) }
    }

    LaunchedEffect(scrollSpeed) {
        if (scrollSpeed == 0f) return@LaunchedEffect
        while (isActive) {
            listState.scrollBy(scrollSpeed)
            follow()
            delay(16)
        }
    }

    fun stop() {
        anchor = -1
        last = -1
        scrollSpeed = 0f
        currentOnActive(false)
    }

    pointerInput(Unit) {
        detectDragGesturesAfterLongPress(
            onDragStart = { offset ->
                val start = songAt(offset.y) ?: return@detectDragGesturesAfterLongPress
                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                currentOnActive(true)
                anchor = start
                last = -1
                pointerY = offset.y
                selecting = currentSongs[start].path !in selection
                before = selection.toList()
                extendTo(start)
            },
            onDrag = { change, _ ->
                if (anchor < 0) return@detectDragGesturesAfterLongPress
                pointerY = change.position.y
                val height = listState.layoutInfo.viewportSize.height.toFloat()
                scrollSpeed = when {
                    pointerY < edge -> -maxStep * ((edge - pointerY) / edge).coerceIn(0.1f, 1f)
                    pointerY > height - edge -> maxStep * ((pointerY - (height - edge)) / edge).coerceIn(0.1f, 1f)
                    else -> 0f
                }
                follow()
            },
            onDragEnd = { stop() },
            onDragCancel = { stop() },
        )
    }
}
