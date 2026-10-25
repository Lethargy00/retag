package app.retag.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import app.retag.AppViewModel
import app.retag.data.CoverChange
import app.retag.data.Sources
import app.retag.data.TagData
import app.retag.data.TagIO
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** Set the same values (album, artist, cover…) on many files at once. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BulkEditScreen(vm: AppViewModel, paths: List<String>) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var form by remember { mutableStateOf(TagData()) }
    var cover by remember { mutableStateOf<ByteArray?>(null) }
    var coverChange by remember { mutableStateOf<CoverChange>(CoverChange.Keep) }
    var numberTracks by remember { mutableStateOf(false) }
    var running by remember { mutableStateOf(false) }
    var progress by remember { mutableIntStateOf(0) }
    var result by remember { mutableStateOf<String?>(null) }
    var showCovers by remember { mutableStateOf(false) }

    val sorted = remember(paths) { paths.sortedBy { File(it).name.lowercase() } }

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
                if (bytes != null) {
                    cover = bytes
                    coverChange = CoverChange.Set(bytes)
                }
            }
        }
    }

    fun apply() {
        if (running) return
        running = true
        progress = 0
        result = null
        scope.launch {
            var ok = 0
            val errors = ArrayList<String>()
            for ((index, path) in sorted.withIndex()) {
                try {
                    val written = withContext(Dispatchers.IO) {
                        val current = TagIO.read(path).tags
                        fun v(new: String, old: String) = new.trim().ifBlank { old }
                        val t = current.copy(
                            artist = v(form.artist, current.artist),
                            albumArtist = v(form.albumArtist, current.albumArtist),
                            album = v(form.album, current.album),
                            genre = v(form.genre, current.genre),
                            year = v(form.year, current.year),
                            disc = v(form.disc, current.disc),
                            discTotal = v(form.discTotal, current.discTotal),
                            track = if (numberTracks) (index + 1).toString() else current.track,
                            trackTotal = if (numberTracks) sorted.size.toString() else current.trackTotal,
                        )
                        TagIO.write(path, t, coverChange)
                        t
                    }
                    vm.onSongWritten(path, written)
                    ok++
                } catch (e: Exception) {
                    errors += "${File(path).name}: ${e.message ?: e.javaClass.simpleName}"
                }
                progress = index + 1
            }
            running = false
            result = buildString {
                append("Updated $ok of ${sorted.size} files.")
                if (errors.isNotEmpty()) append("\n\nProblems:\n").append(errors.joinToString("\n"))
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Edit ${paths.size} files") },
                navigationIcon = {
                    IconButton(onClick = { vm.back() }, enabled = !running) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { pad ->
        Column(
            Modifier
                .padding(pad)
                .fillMaxSize()
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                "Fields you leave empty stay as they are in each file.",
                style = MaterialTheme.typography.bodyMedium,
            )
            TagField("Artist", form.artist) { form = form.copy(artist = it) }
            TagField("Album artist", form.albumArtist) { form = form.copy(albumArtist = it) }
            TagField("Album", form.album) { form = form.copy(album = it) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TagField("Genre", form.genre, Modifier.weight(2f)) { form = form.copy(genre = it) }
                TagField("Year", form.year, Modifier.weight(1f), number = true) { form = form.copy(year = it) }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TagField("Disc", form.disc, Modifier.weight(1f), number = true) { form = form.copy(disc = it) }
                TagField("of", form.discTotal, Modifier.weight(1f), number = true) { form = form.copy(discTotal = it) }
            }
            SwitchRow("Number tracks 1–${paths.size} in file-name order", numberTracks, !running) { numberTracks = it }

            SectionLabel("Cover for all files")
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                BytesCover(cover, 110.dp)
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    OutlinedButton(onClick = { showCovers = true }, enabled = !running) { Text("Find cover") }
                    OutlinedButton(onClick = { pickImage.launch("image/*") }, enabled = !running) { Text("From gallery") }
                    when (coverChange) {
                        CoverChange.Keep -> TextButton(onClick = { coverChange = CoverChange.Remove }, enabled = !running) {
                            Text("Remove covers")
                        }
                        else -> TextButton(onClick = {
                            cover = null
                            coverChange = CoverChange.Keep
                        }, enabled = !running) { Text("Keep existing covers") }
                    }
                }
            }
            if (coverChange == CoverChange.Remove) {
                Text("Covers will be removed from all files.", color = MaterialTheme.colorScheme.error)
            }

            Button(onClick = { apply() }, enabled = !running, modifier = Modifier.fillMaxWidth()) {
                Text("Apply to ${paths.size} files")
            }
            if (running) {
                LinearProgressIndicator(progress = { progress.toFloat() / sorted.size.coerceAtLeast(1) }, modifier = Modifier.fillMaxWidth())
                Text("$progress of ${sorted.size}", style = MaterialTheme.typography.bodySmall)
            }
            result?.let { Text(it) }
            Spacer(Modifier.height(24.dp))
        }
    }

    if (showCovers) {
        CoversDialog(
            album = form.album,
            artist = form.albumArtist.ifBlank { form.artist },
            onPick = {
                cover = it
                coverChange = CoverChange.Set(it)
                showCovers = false
            },
            onClose = { showCovers = false },
        )
    }
}
