package app.retag.data

import android.media.MediaMetadataRetriever
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Finds music in the chosen folder. Basic tags are read with Android's own reader and
 * cached, together with the date each file was first seen ("date added").
 */
object Library {
    val EXTENSIONS = setOf("mp3", "m4a", "m4b", "flac", "ogg", "opus", "wma", "wav", "aif", "aiff", "dsf")

    /** [name] and [folder] let the last known list be shown at start-up, before the folder has been walked again. */
    private data class Meta(
        val title: String,
        val artist: String,
        val album: String,
        val modified: Long,
        val size: Long,
        val added: Long,
        val name: String = "",
        val folder: String = "",
    ) {
        fun json() = JSONObject()
            .put("t", title).put("a", artist).put("al", album)
            .put("m", modified).put("s", size).put("d", added)
            .put("n", name).put("f", folder)

        companion object {
            fun from(o: JSONObject) = Meta(
                o.optString("t"), o.optString("a"), o.optString("al"),
                o.optLong("m"), o.optLong("s"), o.optLong("d"),
                o.optString("n"), o.optString("f"),
            )
        }
    }

    private val cache = ConcurrentHashMap<String, Meta>()
    @Volatile
    private var cacheLoaded = false
    /** The chosen folder the cache belongs to. */
    @Volatile
    private var cacheRoot: String? = null
    private val cacheFile: File get() = File(Store.app.filesDir, "library.json")

    @Synchronized
    private fun loadCache() {
        if (cacheLoaded) return
        cacheLoaded = true
        try {
            val o = JSONObject(cacheFile.readText())
            val songs = o.optJSONObject("songs")
            if (songs != null) {
                cacheRoot = o.optString("root").ifBlank { null }
                for (k in songs.keys()) cache[k] = Meta.from(songs.getJSONObject(k))
            } else {
                // Older format: just the songs.
                for (k in o.keys()) cache[k] = Meta.from(o.getJSONObject(k))
            }
        } catch (e: Exception) {
            // no cache yet
        }
    }

    @Synchronized
    fun persist() {
        try {
            val songs = JSONObject()
            for ((k, v) in cache) songs.put(k, v.json())
            val o = JSONObject().put("root", cacheRoot.orEmpty()).put("songs", songs)
            val tmp = File(cacheFile.path + ".tmp")
            tmp.writeText(o.toString())
            tmp.renameTo(cacheFile)
        } catch (e: Exception) {
            // cache is only a speed-up
        }
    }

    /** Songs whose cached tags are missing or out of date: (id, modified, size). */
    class Stale(val id: String, val modified: Long, val size: Long)

    /** [changed]: files whose content changed since they were last read, so their cover thumbnails are out of date. */
    class Listing(val songs: List<Song>, val stale: List<Stale>, val changed: List<String>)

    /** The list as it was last time, straight from the cache (no storage access). Empty if there's none for this folder. */
    fun cached(): List<Song> {
        loadCache()
        val root = try {
            Store.rootId()
        } catch (e: Exception) {
            return emptyList()
        }
        if (cacheRoot != root) return emptyList()
        return cache.mapNotNull { (id, m) ->
            if (m.name.isBlank()) return@mapNotNull null
            Song(
                path = id,
                fileName = m.name,
                folder = m.folder,
                title = m.title.ifBlank { m.name.substringBeforeLast('.') },
                artist = m.artist,
                album = m.album,
                dateAdded = m.added,
            )
        }
    }

    /** Walks the chosen folder. Fast: uses cached tags and lists out-of-date files in [Listing.stale]. */
    fun list(): Listing {
        loadCache()
        val root = Store.rootId()
        val songs = ArrayList<Song>()
        val stale = ArrayList<Stale>()
        val changed = ArrayList<String>()
        val now = System.currentTimeMillis()
        // (folder id, folder path relative to the chosen folder)
        val queue = ArrayDeque<Pair<String, String>>()
        queue.add(root to "")
        var depth = 0
        while (queue.isNotEmpty() && depth++ < 100_000) {
            val (dirId, rel) = queue.removeFirst()
            val kids = try {
                Store.children(dirId)
            } catch (e: Exception) {
                // If the chosen folder itself can't be read, stop rather than forget every song.
                if (dirId == root) throw e
                continue
            }
            for (c in kids) {
                if (c.name.startsWith(".")) continue
                if (c.isDir) {
                    queue.add(c.id to (if (rel.isEmpty()) c.name else "$rel/${c.name}"))
                    continue
                }
                if (c.name.substringAfterLast('.', "").lowercase() !in EXTENSIONS) continue
                val m = cache[c.id]
                val added = m?.added?.takeIf { it > 0 } ?: c.modified.takeIf { it > 0 } ?: now
                if (m == null || m.modified != c.modified || m.size != c.size) {
                    stale.add(Stale(c.id, c.modified, c.size))
                    // -1 = never read yet, or just written by Retag (its thumbnail was already refreshed then).
                    if (m != null && m.modified >= 0) changed.add(c.id)
                }
                if (m == null) {
                    cache[c.id] = Meta("", "", "", -1, -1, added, c.name, rel)
                } else if (m.name != c.name || m.folder != rel) {
                    cache[c.id] = m.copy(name = c.name, folder = rel)
                }
                songs.add(
                    Song(
                        path = c.id,
                        fileName = c.name,
                        folder = rel,
                        title = m?.title?.ifBlank { null } ?: c.name.substringBeforeLast('.'),
                        artist = m?.artist.orEmpty(),
                        album = m?.album.orEmpty(),
                        dateAdded = added,
                    )
                )
            }
        }
        // Forget files that are gone.
        val present = songs.mapTo(HashSet()) { it.path }
        cache.keys.retainAll(present)
        cacheRoot = root
        return Listing(songs, stale, changed)
    }

    /** Reads title/artist/album with Android's tag reader and caches them. */
    fun readMeta(song: Song, s: Stale): Song {
        val r = MediaMetadataRetriever()
        var title = ""
        var artist = ""
        var album = ""
        try {
            r.setDataSource(Store.app, Store.uri(s.id))
            title = clean(r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE))
            artist = clean(r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST))
            album = clean(r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM))
        } catch (e: Exception) {
            // unreadable: fall back to the file name
        } finally {
            try {
                r.release()
            } catch (e: Exception) {
                // ignore
            }
        }
        val added = cache[s.id]?.added?.takeIf { it > 0 } ?: song.dateAdded
        cache[s.id] = Meta(title, artist, album, s.modified, s.size, added, song.fileName, song.folder)
        return song.copy(title = title.ifBlank { song.fileName.substringBeforeLast('.') }, artist = artist, album = album)
    }

    private fun clean(s: String?): String = if (s == null || s == "<unknown>") "" else s.trim()

    /** After tags were written (and maybe the file renamed/moved). Keeps the date added. */
    fun updated(oldId: String, newId: String, tags: TagData, name: String, folder: String) {
        loadCache()
        val old = cache.remove(oldId)
        // modified = -1 makes the next scan re-read the file, unless [written] fills in the real values.
        cache[newId] = Meta(tags.title, tags.artist, tags.album, -1, -1, old?.added ?: System.currentTimeMillis(), name, folder)
    }

    /**
     * The file's date and size right after Retag wrote it. The cached tags are the ones just written,
     * so the next start doesn't have to read the file again.
     */
    fun written(id: String, modified: Long, size: Long) {
        cache.computeIfPresent(id) { _, m -> if (m.modified == -1L) m.copy(modified = modified, size = size) else m }
    }

    fun dateAdded(id: String): Long = cache[id]?.added ?: System.currentTimeMillis()

    // Compares without making lower-case copies of every string on every comparison.
    private val ci = String.CASE_INSENSITIVE_ORDER

    fun sort(songs: List<Song>, by: SortBy): List<Song> = when (by) {
        SortBy.AddedNewest -> songs.sortedWith(compareByDescending<Song> { it.dateAdded }.thenBy(ci) { it.fileName })
        SortBy.AddedOldest -> songs.sortedWith(compareBy<Song> { it.dateAdded }.thenBy(ci) { it.fileName })
        SortBy.Title -> songs.sortedWith(compareBy<Song, String>(ci) { it.title })
        SortBy.Artist -> songs.sortedWith(
            compareBy<Song> { it.artist.isBlank() }.thenBy(ci) { it.artist }.thenBy(ci) { it.album }.thenBy(ci) { it.title },
        )
        SortBy.Album -> songs.sortedWith(compareBy<Song> { it.album.isBlank() }.thenBy(ci) { it.album }.thenBy(ci) { it.fileName })
        SortBy.FileName -> songs.sortedWith(compareBy<Song, String>(ci) { it.fileName })
    }
}
