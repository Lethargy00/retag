package app.retag

import android.app.Application
import android.media.MediaScannerConnection
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.retag.data.Library
import app.retag.data.Song
import app.retag.data.TagData
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

sealed interface Screen {
    data object Library : Screen
    data class Editor(val path: String) : Screen
    data object Batch : Screen
    data class BulkEdit(val paths: List<String>) : Screen
}

class AppViewModel(app: Application) : AndroidViewModel(app) {
    private val ctx: Application get() = getApplication()

    var hasPermission by mutableStateOf(Library.hasPermission(app))
        private set
    var songs by mutableStateOf<List<Song>>(emptyList())
        private set
    var loading by mutableStateOf(false)
        private set
    var scannedOnce by mutableStateOf(false)
        private set

    val backStack = mutableStateListOf<Screen>(Screen.Library)
    val selection = mutableStateListOf<String>()

    var batch by mutableStateOf<BatchController?>(null)
        private set

    fun refreshPermission() {
        val had = hasPermission
        hasPermission = Library.hasPermission(ctx)
        if (!had && hasPermission) loadLibrary()
    }

    fun loadLibrary() {
        if (loading) return
        loading = true
        viewModelScope.launch {
            try {
                songs = withContext(Dispatchers.IO) { Library.scan(ctx) }
            } finally {
                loading = false
                scannedOnce = true
            }
        }
    }

    fun open(s: Screen) {
        backStack.add(s)
    }

    fun back() {
        if (backStack.size > 1) backStack.removeAt(backStack.lastIndex)
    }

    /** Call on the main thread after a file has been rewritten. */
    fun onSongWritten(path: String, tags: TagData) {
        songs = songs.map {
            if (it.path == path) {
                it.copy(
                    title = tags.title.ifBlank { File(path).nameWithoutExtension },
                    artist = tags.artist,
                    album = tags.album,
                )
            } else {
                it
            }
        }
        // Let other music apps see the new tags.
        MediaScannerConnection.scanFile(ctx, arrayOf(path), null, null)
    }

    fun startBatch(paths: List<String>, albumMode: Boolean) {
        val current = batch
        if (current != null && current.running) {
            open(Screen.Batch)
            return
        }
        batch = BatchController(paths, albumMode, viewModelScope, ::onSongWritten)
        open(Screen.Batch)
    }
}
