package app.retag.data

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import app.retag.net.Http
import app.retag.net.ITunes
import app.retag.net.MusicBrainz
import java.io.ByteArrayOutputStream
import kotlin.math.max

/** Combines the online sources. All functions block: call them from Dispatchers.IO. */
object Sources {
    /** Minimum score for batch mode to apply a match without asking. */
    const val AUTO_THRESHOLD = 0.75
    const val AUTO_THRESHOLD_NO_ARTIST = 0.9

    fun findCandidates(q: Query, alwaysBoth: Boolean = false): List<Candidate> {
        var error: Exception? = null
        val found = ArrayList<Candidate>()
        try {
            found += MusicBrainz.searchRecordings(q.title, q.artist).map { it.copy(score = Matcher.score(it, q)) }
        } catch (e: Exception) {
            error = e
        }
        val bestSoFar = found.maxOfOrNull { it.score } ?: 0.0
        if (alwaysBoth || bestSoFar < 0.85) {
            try {
                found += ITunes.searchSongs(q.title, q.artist).map { it.copy(score = Matcher.score(it, q)) }
            } catch (e: Exception) {
                if (error == null) error = e
            }
        }
        if (found.isEmpty()) error?.let { throw it }
        return found
            .sortedByDescending { it.score }
            .distinctBy { c ->
                listOf(Matcher.norm(c.tags.title), Matcher.norm(c.tags.artist), Matcher.norm(c.tags.album), c.tags.year, c.source)
            }
    }

    fun findAlbums(album: String, artist: String, fileCount: Int): List<AlbumCandidate> {
        var error: Exception? = null
        val found = ArrayList<AlbumCandidate>()
        try {
            found += MusicBrainz.searchReleases(album, artist)
        } catch (e: Exception) {
            error = e
        }
        try {
            found += ITunes.searchAlbums(album, artist)
        } catch (e: Exception) {
            if (error == null) error = e
        }
        if (found.isEmpty()) error?.let { throw it }
        return found
            .map { it.copy(score = Matcher.albumScore(it, album, artist, fileCount)) }
            .sortedByDescending { it.score }
    }

    fun loadAlbum(c: AlbumCandidate): AlbumTracks =
        if (c.source == "iTunes") ITunes.albumTracks(c.id) else MusicBrainz.releaseTracks(c.id)

    fun findCovers(album: String, artist: String): List<CoverOption> {
        val out = ArrayList<CoverOption>()
        var error: Exception? = null
        try {
            ITunes.searchAlbums(album, artist).forEach { a ->
                if (a.coverThumb != null) out += CoverOption("${a.title} · ${a.artist}", "iTunes", a.coverThumb, a.coverFull)
            }
        } catch (e: Exception) {
            error = e
        }
        try {
            MusicBrainz.searchReleases(album, artist).take(12).forEach { a ->
                if (a.coverThumb != null) {
                    out += CoverOption(
                        listOf(a.title, a.artist, a.year).filter { it.isNotBlank() }.joinToString(" · "),
                        "MusicBrainz", a.coverThumb, a.coverFull,
                    )
                }
            }
        } catch (e: Exception) {
            if (error == null) error = e
        }
        if (out.isEmpty()) error?.let { throw it }
        return out
    }

    fun genreFor(c: Candidate): String {
        val rg = c.releaseGroupId ?: return ""
        return try {
            MusicBrainz.genreFor(rg)
        } catch (e: Exception) {
            ""
        }
    }

    /** Downloads the first URL that returns a real image, sized for embedding. */
    fun downloadCover(urls: List<String>): ByteArray? {
        for (u in urls) {
            try {
                val bytes = Http.getBytes(u) ?: continue
                normalizeImage(bytes)?.let { return it }
            } catch (e: Exception) {
                // try the next one
            }
        }
        return null
    }

    /** When the match itself has no cover, look the album up on iTunes. */
    fun fallbackCover(tags: TagData): ByteArray? {
        if (tags.album.isBlank()) return null
        val artist = tags.albumArtist.ifBlank { tags.artist }
        return try {
            val hit = ITunes.searchAlbums(tags.album, artist).firstOrNull {
                Matcher.sim(it.title, tags.album) >= 0.8 && (artist.isBlank() || Matcher.sim(it.artist, artist) >= 0.6)
            } ?: return null
            downloadCover(hit.coverFull)
        } catch (e: Exception) {
            null
        }
    }

    fun findCover(c: Candidate, merged: TagData): ByteArray? =
        downloadCover(c.coverFull) ?: fallbackCover(merged)

    /** Rejects non-images; shrinks anything huge to 1200px JPEG so players handle it well. */
    fun normalizeImage(bytes: ByteArray): ByteArray? {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, o)
        if (o.outWidth <= 0 || o.outHeight <= 0) return null
        val biggest = max(o.outWidth, o.outHeight)
        if (o.outMimeType == "image/jpeg" && biggest <= 1600 && bytes.size <= 1_500_000) return bytes
        var sample = 1
        while (biggest / (sample * 2) >= 1200) sample *= 2
        val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
            ?: return null
        val scale = 1200f / max(bmp.width, bmp.height)
        val scaled = if (scale < 1f) {
            Bitmap.createScaledBitmap(bmp, (bmp.width * scale).toInt().coerceAtLeast(1), (bmp.height * scale).toInt().coerceAtLeast(1), true)
        } else {
            bmp
        }
        val out = ByteArrayOutputStream()
        scaled.compress(Bitmap.CompressFormat.JPEG, 90, out)
        return out.toByteArray()
    }
}
