package app.retag.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.retag.AppViewModel
import app.retag.data.Candidate
import app.retag.data.CoverChange
import app.retag.data.CoverOption
import app.retag.data.FileTags
import app.retag.data.Matcher
import app.retag.data.Query
import app.retag.data.Sources
import app.retag.data.TagData
import app.retag.data.TagIO
import app.retag.data.Organizer
import app.retag.data.Store
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditorScreen(vm: AppViewModel, path: String) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val snack = remember { SnackbarHostState() }
    // The file's ID changes if saving renames or moves it.
    var id by remember { mutableStateOf(path) }
    val fileName = remember(id) { Store.name(id) }

    var loaded by remember { mutableStateOf<FileTags?>(null) }
    var loadError by remember { mutableStateOf<String?>(null) }
    var tags by remember { mutableStateOf(TagData()) }
    var cover by remember { mutableStateOf<ByteArray?>(null) }
    var coverChange by remember { mutableStateOf<CoverChange>(CoverChange.Keep) }
    var busy by remember { mutableStateOf<String?>(null) }
    var showMatches by remember { mutableStateOf(false) }
    var showCovers by remember { mutableStateOf(false) }

    LaunchedEffect(path) {
        try {
            val ft = withContext(Dispatchers.IO) { TagIO.read(id) }
            loaded = ft
            tags = ft.tags
            cover = ft.cover
        } catch (e: Exception) {
            loadError = e.message ?: e.javaClass.simpleName
        }
    }

    fun setCover(bytes: ByteArray) {
        cover = bytes
        coverChange = CoverChange.Set(bytes)
    }

    val pickImage = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            scope.launch {
                val bytes = withContext(Dispatchers.IO) {
                    try {
                        ctx.contentResolver.openInputStream(uri)?.use { it.readBytes() }?.let { Sources.normalizeImage(it) }
                    } catch (e: Exception) {
                        null
                    }
                }
                if (bytes != null) setCover(bytes) else snack.showSnackbar("Couldn't read that image")
            }
        }
    }

    fun save() {
        if (busy != null) return
        scope.launch {
            busy = "Saving…"
            try {
                val toWrite = tags
                val oldId = id
                val saved = withContext(Dispatchers.IO) { Organizer.save(oldId, toWrite, coverChange) }
                coverChange = CoverChange.Keep
                id = saved.id
                vm.onSongWritten(oldId, saved.id, toWrite)
                snack.showSnackbar(
                    when {
                        saved.note != null -> "Saved, ${saved.note}"
                        saved.id != oldId -> "Saved as ${Store.name(saved.id)}"
                        else -> "Saved"
                    }
                )
            } catch (e: Exception) {
                snack.showSnackbar("Save failed: ${e.message ?: e.javaClass.simpleName}")
            } finally {
                busy = null
            }
        }
    }

    fun applyCandidate(c: Candidate) {
        showMatches = false
        scope.launch {
            busy = "Applying match…"
            try {
                var t = Matcher.merge(tags, c.tags, overwrite = true)
                if (t.genre.isBlank()) {
                    val g = withContext(Dispatchers.IO) { Sources.genreFor(c) }
                    t = t.copy(genre = g)
                }
                tags = t
                busy = "Downloading cover…"
                val bytes = withContext(Dispatchers.IO) { Sources.findCover(c, t) }
                if (bytes != null) setCover(bytes)
                snack.showSnackbar(
                    if (bytes != null) "Match applied. Review it, then tap Save." else "Match applied (no cover found). Tap Save to keep it."
                )
            } catch (e: Exception) {
                snack.showSnackbar("Something went wrong: ${e.message}")
            } finally {
                busy = null
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(fileName, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    IconButton(onClick = { vm.back() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    TextButton(onClick = { save() }, enabled = loaded != null && busy == null) {
                        Text("Save", fontWeight = FontWeight.Bold)
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snack) },
    ) { pad ->
        val err = loadError
        if (err != null) {
            Box(Modifier.padding(pad).fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                Text("Couldn't read this file's tags:\n$err")
            }
            return@Scaffold
        }
        if (loaded == null) {
            Box(Modifier.padding(pad).fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            return@Scaffold
        }
        Column(
            Modifier
                .padding(pad)
                .fillMaxSize()
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                BytesCover(cover, 132.dp)
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    OutlinedButton(onClick = { showCovers = true }, enabled = busy == null) { Text("Find cover") }
                    OutlinedButton(onClick = { pickImage.launch("image/*") }, enabled = busy == null) { Text("From gallery") }
                    if (cover != null) {
                        TextButton(onClick = {
                            cover = null
                            coverChange = CoverChange.Remove
                        }) { Text("Remove cover") }
                    }
                }
            }
            Button(onClick = { showMatches = true }, enabled = busy == null, modifier = Modifier.fillMaxWidth()) {
                Text("Find tags online")
            }
            busy?.let {
                LinearProgressIndicator(Modifier.fillMaxWidth())
                Text(it, style = MaterialTheme.typography.bodySmall)
            }

            TagField("Title", tags.title) { tags = tags.copy(title = it) }
            TagField("Artist", tags.artist) { tags = tags.copy(artist = it) }
            TagField("Album artist", tags.albumArtist) { tags = tags.copy(albumArtist = it) }
            TagField("Album", tags.album) { tags = tags.copy(album = it) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TagField("Genre", tags.genre, Modifier.weight(2f)) { tags = tags.copy(genre = it) }
                TagField("Year", tags.year, Modifier.weight(1f), number = true) { tags = tags.copy(year = it) }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TagField("Track", tags.track, Modifier.weight(1f), number = true) { tags = tags.copy(track = it) }
                TagField("of", tags.trackTotal, Modifier.weight(1f), number = true) { tags = tags.copy(trackTotal = it) }
                TagField("Disc", tags.disc, Modifier.weight(1f), number = true) { tags = tags.copy(disc = it) }
                TagField("of", tags.discTotal, Modifier.weight(1f), number = true) { tags = tags.copy(discTotal = it) }
            }
            TagField("Comment", tags.comment) { tags = tags.copy(comment = it) }
            OutlinedTextField(
                value = tags.lyrics,
                onValueChange = { tags = tags.copy(lyrics = it) },
                label = { Text("Lyrics") },
                minLines = 4,
                maxLines = 14,
                modifier = Modifier.fillMaxWidth(),
            )
            val dur = loaded?.durationSec ?: 0
            Text(
                "${vm.folderName}/${Store.relFolder(id)}".trimEnd('/') + "\n${Store.extension(id).uppercase()} · ${dur / 60}:${(dur % 60).toString().padStart(2, '0')}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(24.dp))
        }
    }

    if (showMatches) {
        val q = Matcher.queryFor(id, tags, loaded?.durationSec ?: 0)
        MatchesDialog(q, onPick = { applyCandidate(it) }, onClose = { showMatches = false })
    }
    if (showCovers) {
        CoversDialog(
            album = tags.album.ifBlank { tags.title },
            artist = tags.albumArtist.ifBlank { tags.artist },
            onPick = {
                setCover(it)
                showCovers = false
            },
            onClose = { showCovers = false },
        )
    }
}

@Composable
fun TagField(
    label: String,
    value: String,
    modifier: Modifier = Modifier.fillMaxWidth(),
    number: Boolean = false,
    onChange: (String) -> Unit,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        singleLine = true,
        keyboardOptions = if (number) KeyboardOptions(keyboardType = KeyboardType.Number) else KeyboardOptions.Default,
        modifier = modifier,
    )
}

/** Search results for one song; tap one to fill in the fields. */
@Composable
fun MatchesDialog(initial: Query, onPick: (Candidate) -> Unit, onClose: () -> Unit) {
    val scope = rememberCoroutineScope()
    var title by remember { mutableStateOf(initial.title) }
    var artist by remember { mutableStateOf(initial.artist) }
    var results by remember { mutableStateOf<List<Candidate>?>(null) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    fun search() {
        if (title.isBlank()) return
        scope.launch {
            loading = true
            error = null
            try {
                val q = initial.copy(title = title, artist = artist)
                results = withContext(Dispatchers.IO) { Sources.findCandidates(q, alwaysBoth = true) }
            } catch (e: Exception) {
                error = "Search failed: ${e.message ?: e.javaClass.simpleName}. Check your internet connection."
            } finally {
                loading = false
            }
        }
    }
    LaunchedEffect(Unit) { search() }

    FullScreenDialog("Find tags online", onClose) {
        Column(Modifier.fillMaxSize().imePadding()) {
            Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                TagField("Title", title) { title = it }
                TagField("Artist", artist) { artist = it }
                Button(onClick = { search() }, enabled = !loading && title.isNotBlank(), modifier = Modifier.fillMaxWidth()) {
                    Text("Search")
                }
            }
            if (loading) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 8.dp))
            error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(16.dp)) }
            val list = results
            if (list != null && list.isEmpty() && !loading) {
                Text("No matches. Try a shorter title, or remove the artist.", Modifier.padding(16.dp))
            }
            LazyColumn(Modifier.fillMaxSize()) {
                items(list.orEmpty()) { c ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable { onPick(c) }
                            .padding(horizontal = 16.dp, vertical = 10.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RemoteCover(c.coverThumb, 56.dp)
                        Column(Modifier.weight(1f)) {
                            Text(
                                "${c.tags.title} — ${c.tags.artist}",
                                style = MaterialTheme.typography.bodyLarge,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                            val albumLine = listOf(c.tags.album, c.tags.year).filter { it.isNotBlank() }.joinToString(" · ")
                            if (albumLine.isNotBlank()) {
                                Text(albumLine, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            val dur = c.durationSec?.let { " · ${it / 60}:${(it % 60).toString().padStart(2, '0')}" }.orEmpty()
                            Text(
                                "${c.source} · ${(c.score * 100).roundToInt()}% match$dur",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    HorizontalDivider()
                }
            }
        }
    }
}

/** Grid of album covers to choose from. */
@Composable
fun CoversDialog(album: String, artist: String, onPick: (ByteArray) -> Unit, onClose: () -> Unit) {
    val scope = rememberCoroutineScope()
    var albumQ by remember { mutableStateOf(album) }
    var artistQ by remember { mutableStateOf(artist) }
    var results by remember { mutableStateOf<List<CoverOption>?>(null) }
    var loading by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    fun search() {
        if (albumQ.isBlank() && artistQ.isBlank()) return
        scope.launch {
            loading = "Searching…"
            error = null
            try {
                results = withContext(Dispatchers.IO) { Sources.findCovers(albumQ, artistQ) }
            } catch (e: Exception) {
                error = "Search failed: ${e.message ?: e.javaClass.simpleName}"
            } finally {
                loading = null
            }
        }
    }

    fun pick(o: CoverOption) {
        if (loading != null) return
        scope.launch {
            loading = "Downloading full-size cover…"
            error = null
            try {
                val bytes = withContext(Dispatchers.IO) { Sources.downloadCover(o.full) }
                if (bytes != null) onPick(bytes) else error = "That cover couldn't be downloaded. Try another one."
            } finally {
                loading = null
            }
        }
    }
    LaunchedEffect(Unit) { search() }

    FullScreenDialog("Find cover art", onClose) {
        Column(Modifier.fillMaxSize().imePadding()) {
            Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                TagField("Album", albumQ) { albumQ = it }
                TagField("Artist", artistQ) { artistQ = it }
                Button(onClick = { search() }, enabled = loading == null, modifier = Modifier.fillMaxWidth()) { Text("Search") }
            }
            loading?.let {
                LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 8.dp))
                Text(it, Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.bodySmall)
            }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(16.dp)) }
            val list = results
            if (list != null && list.isEmpty() && loading == null) {
                Text("No covers found.", Modifier.padding(16.dp))
            }
            LazyVerticalGrid(
                columns = GridCells.Adaptive(150.dp),
                modifier = Modifier.fillMaxSize().padding(8.dp),
            ) {
                items(list.orEmpty()) { o ->
                    Column(
                        Modifier
                            .padding(6.dp)
                            .clickable { pick(o) },
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        RemoteCover(o.thumb, 150.dp)
                        Text(o.label, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(o.source, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}
