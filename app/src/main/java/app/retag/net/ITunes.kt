package app.retag.net

import app.retag.data.AlbumCandidate
import app.retag.data.AlbumTracks
import app.retag.data.Candidate
import app.retag.data.Prefs
import app.retag.data.TagData
import app.retag.data.TrackInfo
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.Locale

/** Apple's free iTunes Search API: great for mainstream music and large covers. ~20 requests/min. */
object ITunes {
    private val limiter = RateLimiter(3000)

    private fun country(): String = Locale.getDefault().country.let { if (it.length == 2) it else "US" }

    private fun fetch(params: String, country: String): JSONArray {
        limiter.acquire()
        val lang = if (Prefs.preferEnglish) "&lang=en_us" else ""
        return Http.getJson("https://itunes.apple.com/$params&country=$country$lang")?.optJSONArray("results")
            ?: JSONArray()
    }

    /** Uses the phone's store country, falling back to the US store. */
    private fun query(params: String): List<JSONObject> {
        val c = country()
        var r = fetch(params, c)
        if (r.length() == 0 && c != "US") r = fetch(params, "US")
        return r.objects()
    }

    fun art(url: String, size: Int): String? =
        url.takeIf { it.isNotBlank() }?.replace(Regex("/\\d+x\\d+bb\\."), "/${size}x${size}bb.")

    private fun cleanAlbum(s: String) = s.replace(Regex("\\s+-\\s+(Single|EP)$"), "")

    private fun toTags(o: JSONObject) = TagData(
        title = o.str("trackName"),
        artist = o.str("artistName"),
        albumArtist = o.str("collectionArtistName").ifBlank { o.str("artistName") },
        album = cleanAlbum(o.str("collectionName")),
        genre = o.str("primaryGenreName"),
        year = o.str("releaseDate").take(4),
        track = o.posInt("trackNumber"),
        trackTotal = o.posInt("trackCount"),
        disc = o.posInt("discNumber").takeIf { o.optInt("discCount", 1) > 1 }.orEmpty(),
        discTotal = o.posInt("discCount").takeIf { o.optInt("discCount", 1) > 1 }.orEmpty(),
    )

    private fun seconds(o: JSONObject): Int? = o.optLong("trackTimeMillis", 0L).let { if (it > 0) (it / 1000).toInt() else null }

    fun searchSongs(title: String, artist: String): List<Candidate> {
        val term = "$artist $title".trim()
        if (term.isBlank()) return emptyList()
        return query("search?media=music&entity=song&limit=15&term=" + Http.enc(term))
            .filter { it.str("wrapperType") == "track" }
            .map { o ->
                val artUrl = o.str("artworkUrl100")
                Candidate(
                    source = "iTunes",
                    tags = toTags(o),
                    durationSec = seconds(o),
                    coverThumb = art(artUrl, 300),
                    coverFull = listOfNotNull(art(artUrl, 1200)),
                )
            }
    }

    fun searchAlbums(album: String, artist: String): List<AlbumCandidate> {
        val term = "$artist $album".trim()
        if (term.isBlank()) return emptyList()
        return query("search?media=music&entity=album&limit=15&term=" + Http.enc(term))
            .filter { it.str("wrapperType") == "collection" }
            .map { o ->
                val artUrl = o.str("artworkUrl100")
                AlbumCandidate(
                    source = "iTunes",
                    id = o.optLong("collectionId").toString(),
                    title = cleanAlbum(o.str("collectionName")),
                    artist = o.str("artistName"),
                    year = o.str("releaseDate").take(4),
                    trackCount = o.optInt("trackCount", 0),
                    detail = o.str("primaryGenreName"),
                    coverThumb = art(artUrl, 300),
                    coverFull = listOfNotNull(art(artUrl, 1200)),
                )
            }
    }

    fun albumTracks(collectionId: String): AlbumTracks {
        val all = query("lookup?entity=song&limit=200&id=" + Http.enc(collectionId))
        val coll = all.firstOrNull { it.str("wrapperType") == "collection" }
            ?: throw IOException("Album not found in the iTunes store")
        val tracks = all.filter { it.str("wrapperType") == "track" }
            .map { o ->
                val t = toTags(o)
                TrackInfo(t.copy(albumArtist = coll.str("artistName").ifBlank { t.albumArtist }), seconds(o))
            }
        val artUrl = coll.str("artworkUrl100")
        return AlbumTracks(
            source = "iTunes",
            title = cleanAlbum(coll.str("collectionName")),
            artist = coll.str("artistName"),
            year = coll.str("releaseDate").take(4),
            coverThumb = art(artUrl, 300),
            coverFull = listOfNotNull(art(artUrl, 1200)),
            tracks = tracks,
        )
    }
}
