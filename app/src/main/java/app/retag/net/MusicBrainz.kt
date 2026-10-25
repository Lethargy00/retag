package app.retag.net

import app.retag.data.AlbumCandidate
import app.retag.data.AlbumTracks
import app.retag.data.Candidate
import app.retag.data.TagData
import app.retag.data.TrackInfo
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException

/** MusicBrainz: free, open music database. Limit: 1 request per second. */
object MusicBrainz {
    private const val BASE = "https://musicbrainz.org/ws/2"
    private const val CAA = "https://coverartarchive.org"
    private val limiter = RateLimiter(1100)

    private fun get(pathAndQuery: String): JSONObject? {
        limiter.acquire()
        return Http.getJson(BASE + pathAndQuery)
    }

    private fun phrase(s: String) = s.replace("\"", " ").replace("\\", " ").trim()
    private fun words(s: String) = s.replace(Regex("[^\\p{L}\\p{N}' ]"), " ").replace(Regex("\\s+"), " ").trim()

    fun artistCredit(arr: JSONArray?): String {
        if (arr == null) return ""
        val sb = StringBuilder()
        for (o in arr.objects()) {
            sb.append(o.str("name").ifBlank { o.optJSONObject("artist")?.str("name").orEmpty() })
            sb.append(o.optString("joinphrase", ""))
        }
        return sb.toString().trim()
    }

    private fun titleCase(s: String) =
        s.split(' ').joinToString(" ") { w -> w.replaceFirstChar { it.titlecase() } }

    private fun topGenre(arr: JSONArray?): String {
        val best = arr?.objects()?.maxByOrNull { it.optInt("count", 0) } ?: return ""
        return titleCase(best.str("name"))
    }

    fun genreFor(releaseGroupId: String): String =
        topGenre(get("/release-group/$releaseGroupId?fmt=json&inc=genres")?.optJSONArray("genres"))

    private fun releaseRank(rel: JSONObject): Int {
        var r = 0
        if (rel.str("status") == "Official") r += 4
        val rg = rel.optJSONObject("release-group")
        if (rg?.str("primary-type") == "Album") r += 2
        if ((rg?.optJSONArray("secondary-types")?.length() ?: 0) == 0) r += 1
        return r
    }

    private fun coverUrls(releaseId: String, releaseGroupId: String?): List<String> =
        listOfNotNull(
            "$CAA/release/$releaseId/front-1200",
            releaseGroupId?.let { "$CAA/release-group/$it/front-1200" },
        )

    fun searchRecordings(title: String, artist: String): List<Candidate> {
        if (title.isBlank()) return emptyList()
        val strict = buildString {
            append("recording:\"").append(phrase(title)).append('"')
            if (artist.isNotBlank()) append(" AND artist:\"").append(phrase(artist)).append('"')
        }
        var recs = get("/recording?fmt=json&limit=15&query=" + Http.enc(strict))?.optJSONArray("recordings")
        if ((recs == null || recs.length() == 0) && words(title).isNotEmpty()) {
            val loose = buildString {
                append("recording:(").append(words(title)).append(')')
                if (words(artist).isNotEmpty()) append(" AND artist:(").append(words(artist)).append(')')
            }
            recs = get("/recording?fmt=json&limit=15&query=" + Http.enc(loose))?.optJSONArray("recordings")
        }
        if (recs == null) return emptyList()

        val out = ArrayList<Candidate>()
        for (r in recs.objects()) {
            val rTitle = r.str("title")
            val rArtist = artistCredit(r.optJSONArray("artist-credit"))
            val len = r.optLong("length", 0L).let { if (it > 0) (it / 1000).toInt() else null }
            val releases = r.optJSONArray("releases")?.objects().orEmpty()
            if (releases.isEmpty()) {
                out += Candidate("MusicBrainz", TagData(title = rTitle, artist = rArtist), len, null, emptyList())
                continue
            }
            val sorted = releases.sortedWith(
                compareByDescending<JSONObject> { releaseRank(it) }
                    .thenBy { it.str("date").ifBlank { "9999" } }
            )
            for (rel in sorted.take(2)) {
                val relId = rel.str("id")
                val rgId = rel.optJSONObject("release-group")?.str("id")?.ifBlank { null }
                val medium = rel.optJSONArray("media")?.optJSONObject(0)
                val trackObj = medium?.optJSONArray("track")?.optJSONObject(0)
                val trackNo = when {
                    medium != null && medium.has("track-offset") -> (medium.optInt("track-offset") + 1).toString()
                    else -> trackObj?.str("number")?.filter { it.isDigit() }.orEmpty()
                }
                val albumArtist = artistCredit(rel.optJSONArray("artist-credit")).ifBlank { rArtist }
                out += Candidate(
                    source = "MusicBrainz",
                    tags = TagData(
                        title = rTitle,
                        artist = rArtist,
                        albumArtist = albumArtist,
                        album = rel.str("title"),
                        year = rel.str("date").take(4),
                        track = trackNo,
                        trackTotal = medium?.posInt("track-count").orEmpty(),
                        disc = medium?.posInt("position").orEmpty(),
                    ),
                    durationSec = len,
                    coverThumb = "$CAA/release/$relId/front-250",
                    coverFull = coverUrls(relId, rgId),
                    releaseGroupId = rgId,
                )
            }
        }
        return out
    }

    fun searchReleases(album: String, artist: String): List<AlbumCandidate> {
        if (album.isBlank()) return emptyList()
        val q = buildString {
            append("release:\"").append(phrase(album)).append('"')
            if (artist.isNotBlank()) append(" AND artist:\"").append(phrase(artist)).append('"')
        }
        val arr = get("/release?fmt=json&limit=20&query=" + Http.enc(q))?.optJSONArray("releases")
            ?: return emptyList()
        return arr.objects().map { rel ->
            val id = rel.str("id")
            val rgId = rel.optJSONObject("release-group")?.str("id")?.ifBlank { null }
            val media = rel.optJSONArray("media")?.objects().orEmpty()
            val formats = media.map { it.str("format") }.filter { it.isNotBlank() }
            val formatText = formats.groupingBy { it }.eachCount()
                .entries.joinToString(" + ") { (f, n) -> if (n > 1) "$n×$f" else f }
            AlbumCandidate(
                source = "MusicBrainz",
                id = id,
                title = rel.str("title"),
                artist = artistCredit(rel.optJSONArray("artist-credit")),
                year = rel.str("date").take(4),
                trackCount = rel.optInt("track-count", 0),
                detail = listOf(formatText, rel.str("country"), rel.str("status"))
                    .filter { it.isNotBlank() }.joinToString(" · "),
                coverThumb = "$CAA/release/$id/front-250",
                coverFull = coverUrls(id, rgId),
            )
        }
    }

    fun releaseTracks(id: String): AlbumTracks {
        val j = get("/release/$id?fmt=json&inc=recordings+artist-credits+genres+release-groups")
            ?: throw IOException("That release no longer exists on MusicBrainz")
        val albumTitle = j.str("title")
        val albumArtist = artistCredit(j.optJSONArray("artist-credit"))
        val year = j.str("date").take(4)
        val rg = j.optJSONObject("release-group")
        val rgId = rg?.str("id")?.ifBlank { null }
        var genre = topGenre(j.optJSONArray("genres"))
        if (genre.isBlank()) genre = topGenre(rg?.optJSONArray("genres"))
        if (genre.isBlank() && rgId != null) genre = try {
            genreFor(rgId)
        } catch (e: Exception) {
            ""
        }
        val media = j.optJSONArray("media")?.objects().orEmpty()
        val discTotal = if (media.size > 1) media.size.toString() else ""
        val tracks = ArrayList<TrackInfo>()
        media.forEachIndexed { m, med ->
            val disc = med.optInt("position", m + 1)
            val list = med.optJSONArray("tracks")?.objects().orEmpty()
            val count = med.optInt("track-count", list.size)
            list.forEachIndexed { k, t ->
                val rec = t.optJSONObject("recording")
                val trackArtist = artistCredit(t.optJSONArray("artist-credit"))
                    .ifBlank { artistCredit(rec?.optJSONArray("artist-credit")) }
                    .ifBlank { albumArtist }
                var len = t.optLong("length", 0L)
                if (len <= 0) len = rec?.optLong("length", 0L) ?: 0L
                tracks += TrackInfo(
                    TagData(
                        title = t.str("title").ifBlank { rec?.str("title").orEmpty() },
                        artist = trackArtist,
                        albumArtist = albumArtist,
                        album = albumTitle,
                        genre = genre,
                        year = year,
                        track = t.optInt("position", k + 1).toString(),
                        trackTotal = count.toString(),
                        disc = if (media.size > 1) disc.toString() else "",
                        discTotal = discTotal,
                    ),
                    if (len > 0) (len / 1000).toInt() else null,
                )
            }
        }
        return AlbumTracks(
            source = "MusicBrainz",
            title = albumTitle,
            artist = albumArtist,
            year = year,
            coverThumb = "$CAA/release/$id/front-250",
            coverFull = coverUrls(id, rgId),
            tracks = tracks,
        )
    }
}
