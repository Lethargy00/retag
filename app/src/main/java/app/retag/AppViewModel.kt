package app.retag

import android.app.Application
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.retag.data.Library
import app.retag.data.Organizer
import app.retag.data.Song
import app.retag.data.Store
import app.retag.data.TagData
import app.retag.data.TagIO
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

sealed interface Screen {
    data object Library : Screen
    data class Editor(val path: String) : Screen
    data object Batch : Screen
    data class BulkEdit(val paths: List<String>) : Screen
    data object Settings : Screen
}

/** Progress of "Rename & organize" on a selection. */
class OrganizeState(val done: Int, val total: Int, val running: Boolean, val result: String? = null)

class AppViewModel(app: Application) : AndroidViewModel(app) {
    var hasFolder by mutableStateOf(Store.hasAccess())
        private set
    var folderName by mutableStateOf(if (hasFolder) Store.rootName() else "")
        private set
    var songs by mutableStateOf<List<Song>>(emptyList())
        private set
    var loading by mutableStateOf(false)
        private set
    /** "Reading tags 120 of 800" while the first scan fills in tags. */
    var loadingDetail by mutableStateOf<String?>(null)
        private set
    var scannedOnce by mutableStateOf(false)
        private set

    val backStack = mutableStateListOf<Screen>(Screen.Library)
    val selection = mutableStateListOf<String>()

    var batch by mutableStateOf<BatchController?>(null)
        private set

    var organize by mutableStateOf<OrganizeState?>(null)
        private set

    private var loadJob: Job? = null
    private var persistJob: Job? = null
    private var loadGeneration = 0

    fun refreshAccess() {
        val had = hasFolder
        hasFolder = Store.hasAccess()
        if (!had && hasFolder) loadLibrary()
    }

    fun setFolder(uri: Uri) {
        try {
            Store.setTree(uri)
        } catch (e: Exception) {
            return
        }
        loadJob?.cancel()
        loadGeneration++
        loading = false
        songs = emptyList()
        selection.clear()
        scannedOnce = false
        hasFolder = Store.hasAccess()
        folderName = Store.rootName()
        loadLibrary()
    }

    fun loadLibrary() {
        if (loading || !hasFolder) return
        loading = true
        val generation = ++loadGeneration
        loadJob = viewModelScope.launch {
            try {
                val listing = withContext(Dispatchers.IO) { Library.list() }
                songs = listing.songs
                scannedOnce = true
                // Fill in tags for new or changed files, a chunk at a time so the list updates.
                val stale = listing.stale
                for ((n, chunk) in stale.chunked(25).withIndex()) {
                    if (!isActive) break
                    loadingDetail = "Reading tags: ${n * 25} of ${stale.size}"
                    val fresh = withContext(Dispatchers.IO) {
                        val byId = songs.associateBy { it.path }
                        chunk.mapNotNull { s -> byId[s.id]?.let { Library.readMeta(it, s) } }.associateBy { it.path }
                    }
                    songs = songs.map { fresh[it.path] ?: it }
                }
                withContext(Dispatchers.IO) { Library.persist() }
            } catch (e: Exception) {
                scannedOnce = true
                hasFolder = Store.hasAccess()
            } finally {
                if (generation == loadGeneration) {
                    loading = false
                    loadingDetail = null
                }
            }
        }
    }

    fun open(s: Screen) {
        backStack.add(s)
    }

    fun back() {
        if (backStack.size > 1) backStack.removeAt(backStack.lastIndex)
    }

    /** Call on the main thread after a file has been rewritten (and maybe renamed or moved to [newId]). */
    fun onSongWritten(oldId: String, newId: String, tags: TagData) {
        Library.updated(oldId, newId, tags)
        val name = Store.name(newId)
        val updated = Song(
            path = newId,
            fileName = name,
            folder = Store.relFolder(newId),
            title = tags.title.ifBlank { name.substringBeforeLast('.') },
            artist = tags.artist,
            album = tags.album,
            dateAdded = Library.dateAdded(newId),
        )
        songs = if (songs.any { it.path == oldId }) songs.map { if (it.path == oldId) updated else it } else songs + updated
        if (oldId != newId) {
            val i = selection.indexOf(oldId)
            if (i >= 0) selection[i] = newId
        } else {
            // Let other music apps see the new tags.
            Store.mediaScan(newId)
        }
        schedulePersist()
    }

    private fun schedulePersist() {
        persistJob?.cancel()
        persistJob = viewModelScope.launch {
            delay(2000)
            withContext(Dispatchers.IO) { Library.persist() }
        }
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

    /** Renames/moves the given files using their current tags and the patterns in Settings. */
    fun organizeFiles(ids: List<String>, rename: Boolean, folders: Boolean) {
        if (organize?.running == true || ids.isEmpty()) return
        organize = OrganizeState(0, ids.size, true)
        viewModelScope.launch {
            var changed = 0
            val errors = ArrayList<String>()
            for ((i, id) in ids.withIndex()) {
                try {
                    val (tags, newId) = withContext(Dispatchers.IO) {
                        val t = TagIO.read(id).tags
                        t to Organizer.organize(id, t, rename, folders)
                    }
                    if (newId != id) {
                        changed++
                        onSongWritten(id, newId, tags)
                    }
                } catch (e: Exception) {
                    errors += "${Store.name(id)}: ${e.message ?: e.javaClass.simpleName}"
                }
                organize = OrganizeState(i + 1, ids.size, true)
            }
            organize = OrganizeState(
                ids.size, ids.size, false,
                buildString {
                    append("Renamed/moved $changed of ${ids.size} files")
                    if (ids.size - changed - errors.size > 0) append(" (${ids.size - changed - errors.size} already had the right name)")
                    append(".")
                    if (errors.isNotEmpty()) append("\n\nProblems:\n").append(errors.take(20).joinToString("\n"))
                },
            )
        }
    }

    fun dismissOrganize() {
        if (organize?.running != true) organize = null
    }
}
