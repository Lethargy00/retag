package app.retag.net

import app.retag.data.Matcher
import org.json.JSONObject
import kotlin.math.abs

/** LRCLIB: free lyrics database with plain and time-synced (LRC) lyrics. */
object Lrclib {
    data class Lyrics(val plain: String, val synced: String) {
        fun pick(preferSynced: Boolean): String =
            if (preferSynced) synced.ifBlank { plain } else plain.ifBlank { synced }
    }

    private const val BASE = "https://lrclib.net/api"

    private fun from(o: JSONObject?): Lyrics? {
        if (o == null) return null
        val l = Lyrics(o.str("plainLyrics"), o.str("syncedLyrics"))
        return if (l.plain.isBlank() && l.synced.isBlank()) null else l
    }

    fun find(title: String, artist: String, album: String, durationSec: Int): Lyrics? {
        if (title.isBlank()) return null
        if (artist.isNotBlank()) {
            val url = buildString {
                append("$BASE/get?track_name=").append(Http.enc(title))
                append("&artist_name=").append(Http.enc(artist))
                if (album.isNotBlank()) append("&album_name=").append(Http.enc(album))
                if (durationSec > 0) append("&duration=").append(durationSec)
            }
            try {
                from(Http.getJson(url))?.let { return it }
            } catch (e: Exception) {
                // fall through to search
            }
        }
        val searchUrl = buildString {
            append("$BASE/search?track_name=").append(Http.enc(title))
            if (artist.isNotBlank()) append("&artist_name=").append(Http.enc(artist))
        }
        val results = Http.getJsonArray(searchUrl)?.objects().orEmpty()
            .filter { from(it) != null }
            .filter { artist.isBlank() || Matcher.sim(it.str("artistName"), artist) >= 0.6 }
            .filter { Matcher.sim(it.str("trackName"), title) >= 0.7 }
        val best = if (durationSec > 0) {
            results.filter { abs(it.optDouble("duration", 0.0) - durationSec) <= 5 }
                .minByOrNull { abs(it.optDouble("duration", 0.0) - durationSec) }
        } else {
            results.firstOrNull()
        }
        return from(best)
    }
}
