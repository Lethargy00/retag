package app.retag

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.retag.data.AlbumCandidate
import app.retag.data.AlbumTracks
import app.retag.data.Assignment
import app.retag.data.CoverChange
import app.retag.data.FileInfo
import app.retag.data.Matcher
import app.retag.data.Sources
import app.retag.data.TagData
import app.retag.data.TagIO
import app.retag.data.TrackInfo
import app.retag.net.Lrclib
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.roundToInt

enum class Status { Pending, Working, Done, Skipped, Failed }

data class BatchItem(val path: String, val status: Status = Status.Pending, val message: String = "") {
    val fileName: String get() = File(path).name
}

/**
 * Runs auto-tagging over many files, either song by song or as one album.
 * Lives in the ViewModel, so it keeps going if you leave the screen.
 * Every file is saved as soon as it's done, so stopping half-way loses nothing.
 */
class BatchController(
    val paths: List<String>,
    val albumMode: Boolean,
    private val scope: CoroutineScope,
    private val onWritten: (String, TagData) -> Unit,
) {
    val items = mutableStateListOf<BatchItem>().apply { addAll(paths.map { BatchItem(it) }) }
    var running by mutableStateOf(false)
        private set
    var finished by mutableStateOf(false)
        private set

    var overwrite by mutableStateOf(true)
    var fetchCover by mutableStateOf(true)
    var fetchLyrics by mutableStateOf(true)
    var preferSynced by mutableStateOf(true)

    // Album mode
    var albumQuery by mutableStateOf("")
    var artistQuery by mutableStateOf("")
    var albumCandidates by mutableStateOf<List<AlbumCandidate>>(emptyList())
        private set
    var busy by mutableStateOf<String?>(null)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    var album by mutableStateOf<AlbumTracks?>(null)
        private set
    var assignments by mutableStateOf<List<Assignment>>(emptyList())
        private set

    private var files: List<FileInfo> = emptyList()
    private var job: Job? = null

    val doneCount: Int get() = items.count { it.status != Status.Pending && it.status != Status.Working }

    init {
        if (albumMode) prefillAlbumQuery()
    }

    private fun update(path: String, status: Status, message: String) {
        val i = items.indexOfFirst { it.path == path }
        if (i >= 0) items[i] = items[i].copy(status = status, message = message)
    }

    fun cancel() {
        job?.cancel()
    }

    private fun describe(e: Throwable): String = e.message?.takeIf { it.isNotBlank() } ?: e.javaClass.simpleName

    // ---------------- Song-by-song ----------------

    fun startPerSong(onlyRetry: Boolean = false) {
        if (running) return
        if (onlyRetry) {
            for (i in items.indices) {
                if (items[i].status == Status.Failed || items[i].status == Status.Skipped) {
                    items[i] = items[i].copy(status = Status.Pending, message = "")
                }
            }
        }
        running = true
        finished = false
        job = scope.launch {
            try {
                for (path in paths) {
                    if (!isActive) break
                    val item = items.firstOrNull { it.path == path } ?: continue
                    if (item.status != Status.Pending) continue
                    update(path, Status.Working, "Searching…")
                    try {
                        val result = withContext(Dispatchers.IO) { processOne(path) }
                        update(path, result.status, result.message)
                        result.written?.let { onWritten(path, it) }
                    } catch (e: CancellationException) {
                        update(path, Status.Pending, "")
                        throw e
                    } catch (e: Exception) {
                        update(path, Status.Failed, describe(e))
                    }
                }
            } finally {
                running = false
                finished = items.none { it.status == Status.Pending }
            }
        }
    }

    private class Result(val status: Status, val message: String, val written: TagData? = null)

    private fun processOne(path: String): Result {
        val ft = TagIO.read(path)
        val q = Matcher.queryFor(path, ft.tags, ft.durationSec)
        if (q.title.isBlank()) return Result(Status.Skipped, "Nothing to search with: no title tag and no usable file name")
        val candidates = Sources.findCandidates(q)
        val best = candidates.firstOrNull() ?: return Result(Status.Skipped, "No match found online")
        val needed = if (q.artist.isBlank()) Sources.AUTO_THRESHOLD_NO_ARTIST else Sources.AUTO_THRESHOLD
        if (best.score < needed) {
            val pct = (best.score * 100).roundToInt()
            return Result(
                Status.Skipped,
                "Not sure enough ($pct%): best guess was ${best.tags.artist} – ${best.tags.title}. Tap to pick by hand.",
            )
        }
        var tags = Matcher.merge(ft.tags, best.tags, overwrite)
        if (tags.genre.isBlank()) tags = tags.copy(genre = Sources.genreFor(best))
        val notes = ArrayList<String>()
        var cover: CoverChange = CoverChange.Keep
        if (fetchCover && (overwrite || ft.cover == null)) {
            val bytes = Sources.findCover(best, tags)
            if (bytes != null) cover = CoverChange.Set(bytes) else notes += "no cover found"
        }
        if (fetchLyrics && (overwrite || ft.tags.lyrics.isBlank())) {
            val lyrics = try {
                Lrclib.find(tags.title, tags.artist, tags.album, ft.durationSec)
            } catch (e: Exception) {
                null
            }
            if (lyrics != null) tags = tags.copy(lyrics = lyrics.pick(preferSynced)) else notes += "no lyrics found"
        }
        TagIO.write(path, tags, cover)
        val msg = "${tags.artist} – ${tags.title}" + (if (notes.isEmpty()) "" else " (${notes.joinToString(", ")})")
        return Result(Status.Done, msg, tags)
    }

    // ---------------- Album mode ----------------

    private fun prefillAlbumQuery() {
        scope.launch {
            busy = "Reading files…"
            try {
                files = withContext(Dispatchers.IO) { readFiles() }
                val albumTag = files.map { it.tags.album }.filter { it.isNotBlank() }
                    .groupingBy { it }.eachCount().maxByOrNull { it.value }?.key.orEmpty()
                val artistTag = files.map { it.tags.albumArtist.ifBlank { it.tags.artist } }.filter { it.isNotBlank() }
                    .groupingBy { it }.eachCount().maxByOrNull { it.value }?.key.orEmpty()
                val folderName = File(paths.first()).parentFile?.name.orEmpty()
                val (folderArtist, folderAlbum) = Matcher.parseFileName(folderName)
                albumQuery = albumTag.ifBlank { folderAlbum }
                artistQuery = artistTag.ifBlank { folderArtist }
            } catch (e: Exception) {
                error = describe(e)
            } finally {
                busy = null
            }
            if (albumQuery.isNotBlank()) searchAlbums()
        }
    }

    private fun readFiles(): List<FileInfo> = paths.map { p ->
        try {
            val ft = TagIO.read(p)
            FileInfo(p, ft.tags, ft.durationSec, ft.cover != null)
        } catch (e: Exception) {
            FileInfo(p, TagData(), 0, false)
        }
    }

    fun searchAlbums() {
        if (busy != null || running) return
        scope.launch {
            busy = "Searching albums…"
            error = null
            try {
                albumCandidates = withContext(Dispatchers.IO) { Sources.findAlbums(albumQuery, artistQuery, paths.size) }
                if (albumCandidates.isEmpty()) error = "No albums found. Try fewer words, or check the spelling."
            } catch (e: Exception) {
                error = "Search failed: ${describe(e)}"
            } finally {
                busy = null
            }
        }
    }

    fun chooseAlbum(c: AlbumCandidate) {
        if (busy != null || running) return
        scope.launch {
            busy = "Loading tracklist…"
            error = null
            try {
                val tracks = withContext(Dispatchers.IO) { Sources.loadAlbum(c) }
                if (files.isEmpty()) files = withContext(Dispatchers.IO) { readFiles() }
                album = tracks
                assignments = Matcher.assign(files, tracks.tracks)
                for (i in items.indices) items[i] = items[i].copy(status = Status.Pending, message = "")
                finished = false
            } catch (e: Exception) {
                error = "Couldn't load that album: ${describe(e)}"
            } finally {
                busy = null
            }
        }
    }

    fun clearAlbum() {
        if (running) return
        album = null
        assignments = emptyList()
    }

    fun reassign(path: String, track: TrackInfo?) {
        assignments = assignments.map {
            when {
                it.path == path -> it.copy(track = track, confidence = if (track == null) 0.0 else 1.0)
                // A track can only belong to one file.
                track != null && it.track == track -> it.copy(track = null, confidence = 0.0)
                else -> it
            }
        }
    }

    fun applyAlbum() {
        val al = album ?: return
        if (running) return
        running = true
        finished = false
        job = scope.launch {
            try {
                val coverBytes = if (fetchCover) {
                    busy = "Downloading cover…"
                    withContext(Dispatchers.IO) { Sources.downloadCover(al.coverFull) }
                } else {
                    null
                }
                busy = null
                for (a in assignments) {
                    if (!isActive) break
                    val track = a.track
                    if (track == null) {
                        update(a.path, Status.Skipped, "Not matched to a track on this album")
                        continue
                    }
                    update(a.path, Status.Working, "Writing…")
                    try {
                        val written = withContext(Dispatchers.IO) {
                            val ft = TagIO.read(a.path)
                            var tags = Matcher.merge(ft.tags, track.tags, overwrite)
                            if (fetchLyrics && (overwrite || ft.tags.lyrics.isBlank())) {
                                val l = try {
                                    Lrclib.find(tags.title, tags.artist, tags.album, ft.durationSec)
                                } catch (e: Exception) {
                                    null
                                }
                                if (l != null) tags = tags.copy(lyrics = l.pick(preferSynced))
                            }
                            val cover = if (coverBytes != null && (overwrite || ft.cover == null)) {
                                CoverChange.Set(coverBytes)
                            } else {
                                CoverChange.Keep
                            }
                            TagIO.write(a.path, tags, cover)
                            tags
                        }
                        val extra = if (fetchCover && coverBytes == null) " (no cover found)" else ""
                        update(a.path, Status.Done, "${written.track}. ${written.title}$extra")
                        onWritten(a.path, written)
                    } catch (e: CancellationException) {
                        update(a.path, Status.Pending, "")
                        throw e
                    } catch (e: Exception) {
                        update(a.path, Status.Failed, describe(e))
                    }
                }
            } finally {
                busy = null
                running = false
                finished = items.none { it.status == Status.Pending }
            }
        }
    }
}
