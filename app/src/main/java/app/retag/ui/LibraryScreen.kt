package app.retag.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.material3.AssistChip
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun LibraryScreen(vm: AppViewModel) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var query by rememberSaveable { mutableStateOf("") }
    var onlyMissing by rememberSaveable { mutableStateOf(false) }
    val expanded = remember { mutableStateListOf<String>() }
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
    val folders = remember(filtered) {
        filtered.groupBy { it.folder }.toList().sortedBy { it.first.lowercase() }
    }

    BackHandler(enabled = selection.isNotEmpty()) { selection.clear() }

    fun toggle(path: String) {
        if (path in selection) selection.remove(path) else selection.add(path)
    }

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
                                vm.startBatch(paths, albumMode = false)
                            },
                            modifier = Modifier.weight(1f),
                        ) { Text("Auto-tag") }
                        OutlinedButton(
                            onClick = {
                                val paths = selection.toList()
                                selection.clear()
                                vm.startBatch(paths, albumMode = true)
                            },
                            modifier = Modifier.weight(1f),
                        ) { Text("As album") }
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
            Row(
                Modifier
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 12.dp),
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
                Text(
                    "${filtered.size} songs",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            TabRow(selectedTabIndex = tab) {
                Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("Songs") })
                Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("Folders") })
            }

            when {
                vm.loading && songs.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        CircularProgressIndicator()
                        Text("Looking for music in ${vm.folderName}…")
                    }
                }
                vm.scannedOnce && songs.isEmpty() -> Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                    Text("No music files found. Supported: MP3, M4A, FLAC, OGG, WMA, WAV, AIFF, DSF.")
                }
                tab == 0 -> LazyColumn(Modifier.fillMaxSize()) {
                    vm.loadingDetail?.let { d ->
                        item { Text(d, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) }
                    }
                    if (selection.isEmpty() && filtered.isNotEmpty()) {
                        item {
                            Text(
                                "Tap a song to edit it. Long-press to select several for auto-tagging.",
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
                                if (selection.isNotEmpty()) toggle(s.path) else vm.open(Screen.Editor(s.path))
                            },
                            onLongClick = { toggle(s.path) },
                        )
                    }
                }
                else -> LazyColumn(Modifier.fillMaxSize()) {
                    for ((folder, list) in folders) {
                        item(key = "folder:$folder") {
                            val open = folder in expanded
                            ListItem(
                                modifier = Modifier.clickable {
                                    if (open) expanded.remove(folder) else expanded.add(folder)
                                },
                                headlineContent = { Text(folder.substringAfterLast('/').ifBlank { vm.folderName }, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                                supportingContent = {
                                    val missing = list.count { it.missingTags }
                                    Text(
                                        "${list.size} songs" + (if (missing > 0) " · $missing missing tags" else "") + " · " + (if (folder.isEmpty()) vm.folderName else "${vm.folderName}/$folder"),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                },
                                leadingContent = {
                                    Icon(
                                        if (open) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                                        contentDescription = if (open) "Collapse" else "Expand",
                                    )
                                },
                                trailingContent = {
                                    TextButton(onClick = { vm.startBatch(list.map { it.path }, albumMode = true) }) {
                                        Text("Tag album")
                                    }
                                },
                            )
                        }
                        if (folder in expanded) {
                            items(list, key = { "song:" + it.path }) { s ->
                                SongRow(
                                    song = s,
                                    selecting = selection.isNotEmpty(),
                                    selected = s.path in selection,
                                    onClick = {
                                        if (selection.isNotEmpty()) toggle(s.path) else vm.open(Screen.Editor(s.path))
                                    },
                                    onLongClick = { toggle(s.path) },
                                    indent = true,
                                )
                            }
                        }
                        item(key = "div:$folder") { HorizontalDivider() }
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

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SongRow(
    song: Song,
    selecting: Boolean,
    selected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    indent: Boolean = false,
) {
    val subtitle = if (song.missingTags) {
        "Missing tags · ${song.fileName}"
    } else {
        listOf(song.artist, song.album).filter { it.isNotBlank() }.joinToString(" · ")
    }
    ListItem(
        modifier = Modifier
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
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
            if (selecting) {
                Checkbox(checked = selected, onCheckedChange = null)
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
