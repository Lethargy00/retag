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

    private class Meta(
        val title: String,
        val artist: String,
        val album: String,
        val modified: Long,
        val size: Long,
        val added: Long,
    ) {
        fun json() = JSONObject()
            .put("t", title).put("a", artist).put("al", album)
            .put("m", modified).put("s", size).put("d", added)

        companion object {
            fun from(o: JSONObject) = Meta(
                o.optString("t"), o.optString("a"), o.optString("al"),
                o.optLong("m"), o.optLong("s"), o.optLong("d"),
            )
        }
    }

    private val cache = ConcurrentHashMap<String, Meta>()
    private var cacheLoaded = false
    private val cacheFile: File get() = File(Store.app.filesDir, "library.json")

    private fun loadCache() {
        if (cacheLoaded) return
        cacheLoaded = true
        try {
            val o = JSONObject(cacheFile.readText())
            for (k in o.keys()) cache[k] = Meta.from(o.getJSONObject(k))
        } catch (e: Exception) {
            // no cache yet
        }
    }

    @Synchronized
    fun persist() {
        try {
            val o = JSONObject()
            for ((k, v) in cache) o.put(k, v.json())
            val tmp = File(cacheFile.path + ".tmp")
            tmp.writeText(o.toString())
            tmp.renameTo(cacheFile)
        } catch (e: Exception) {
            // cache is only a speed-up
        }
    }

    /** Songs whose cached tags are missing or out of date: (id, modified, size). */
    class Stale(val id: String, val modified: Long, val size: Long)

    class Listing(val songs: List<Song>, val stale: List<Stale>)

    /** Walks the chosen folder. Fast: uses cached tags and lists out-of-date files in [Listing.stale]. */
    fun list(): Listing {
        loadCache()
        val root = Store.rootId()
        val songs = ArrayList<Song>()
        val stale = ArrayList<Stale>()
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
                    if (m == null) cache[c.id] = Meta("", "", "", -1, -1, added)
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
        return Listing(songs, stale)
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
        cache[s.id] = Meta(title, artist, album, s.modified, s.size, added)
        return song.copy(title = title.ifBlank { song.fileName.substringBeforeLast('.') }, artist = artist, album = album)
    }

    private fun clean(s: String?): String = if (s == null || s == "<unknown>") "" else s.trim()

    /** After tags were written (and maybe the file renamed/moved). Keeps the date added. */
    fun updated(oldId: String, newId: String, tags: TagData) {
        loadCache()
        val old = cache.remove(oldId)
        // modified = -1 makes the next scan re-read the file.
        cache[newId] = Meta(tags.title, tags.artist, tags.album, -1, -1, old?.added ?: System.currentTimeMillis())
    }

    fun dateAdded(id: String): Long = cache[id]?.added ?: System.currentTimeMillis()

    fun sort(songs: List<Song>, by: SortBy): List<Song> = when (by) {
        SortBy.AddedNewest -> songs.sortedWith(compareByDescending<Song> { it.dateAdded }.thenBy { it.fileName.lowercase() })
        SortBy.AddedOldest -> songs.sortedWith(compareBy<Song> { it.dateAdded }.thenBy { it.fileName.lowercase() })
        SortBy.Title -> songs.sortedBy { it.title.lowercase() }
        SortBy.Artist -> songs.sortedWith(compareBy({ it.artist.isBlank() }, { it.artist.lowercase() }, { it.album.lowercase() }, { it.title.lowercase() }))
        SortBy.Album -> songs.sortedWith(compareBy({ it.album.isBlank() }, { it.album.lowercase() }, { it.fileName.lowercase() }))
        SortBy.FileName -> songs.sortedBy { it.fileName.lowercase() }
    }
}
