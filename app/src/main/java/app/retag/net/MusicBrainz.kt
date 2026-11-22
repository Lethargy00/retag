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

    /**
     * The credited artist names. With [english] (artist ID → English name), names written in another
     * script are replaced by their English version when there is one.
     */
    fun artistCredit(arr: JSONArray?, english: Map<String, String>? = null): String {
        if (arr == null) return ""
        val sb = StringBuilder()
        var replaced = false
        val parts = arr.objects()
        for ((i, o) in parts.withIndex()) {
            var name = o.str("name").ifBlank { o.optJSONObject("artist")?.str("name").orEmpty() }
            if (english != null && !isLatin(name)) {
                english[o.optJSONObject("artist")?.str("id").orEmpty()]?.let {
                    name = it
                    replaced = true
                }
            }
            sb.append(name)
            var join = o.optString("joinphrase", "")
            // e.g. "と" between two names that are now in English
            if (replaced && !isLatin(join) && i < parts.lastIndex) join = " & "
            sb.append(join)
        }
        return sb.toString().trim()
    }

    // ---------------- English names ----------------

    /** True if every letter is in the Latin alphabet (so it's already readable as "English"). */
    fun isLatin(s: String): Boolean =
        s.all { !it.isLetter() || Character.UnicodeScript.of(it.code) == Character.UnicodeScript.LATIN }

    /** An artist's English name from its aliases: the primary English one first. */
    private fun englishAlias(artist: JSONObject?): String? {
        val en = artist?.optJSONArray("aliases")?.objects().orEmpty().filter {
            it.str("locale").startsWith("en") && it.str("type") != "Search hint" && it.str("name").isNotBlank()
        }
        return (en.firstOrNull { it.optBoolean("primary") } ?: en.firstOrNull())?.str("name")
    }

    /** Artist ID → English name ("" = has none), so each artist is looked up at most once. */
    private val aliasCache = java.util.concurrent.ConcurrentHashMap<String, String>()

    /** Adds Latin-letter credited names (as on an English version of a release) to [into]. */
    private fun latinCredits(arr: JSONArray?, into: MutableMap<String, String>) {
        for (o in arr?.objects().orEmpty()) {
            val id = o.optJSONObject("artist")?.str("id").orEmpty()
            val name = o.str("name").ifBlank { o.optJSONObject("artist")?.str("name").orEmpty() }
            if (id.isNotBlank() && name.isNotBlank() && isLatin(name)) into.putIfAbsent(id, name)
        }
    }

    /** Adds English aliases included in the response to [into]. */
    private fun inlineAliases(arr: JSONArray?, into: MutableMap<String, String>) {
        for (o in arr?.objects().orEmpty()) {
            val a = o.optJSONObject("artist") ?: continue
            val id = a.str("id")
            if (id.isBlank() || !a.has("aliases")) continue
            val name = englishAlias(a)
            aliasCache.putIfAbsent(id, name.orEmpty())
            if (name != null) into.putIfAbsent(id, name)
        }
    }

    /** Looks up English aliases for up to [max] credited artists that still have only a non-Latin name. */
    private fun lookupAliases(credits: List<JSONArray?>, into: MutableMap<String, String>, max: Int) {
        var looked = 0
        for (arr in credits) for (o in arr?.objects().orEmpty()) {
            val id = o.optJSONObject("artist")?.str("id").orEmpty()
            val credited = o.str("name").ifBlank { o.optJSONObject("artist")?.str("name").orEmpty() }
            if (id.isBlank() || id in into || isLatin(credited)) continue
            val cached = aliasCache[id]
            if (cached != null) {
                if (cached.isNotEmpty()) into[id] = cached
                continue
            }
            if (looked++ >= max) return
            val name = try {
                englishAlias(get("/artist/$id?fmt=json&inc=aliases"))
            } catch (e: Exception) {
                null
            }
            aliasCache[id] = name.orEmpty()
            if (name != null) into[id] = name
        }
    }

    /** The first track title on a release as listed in search results. */
    private fun firstTrackTitle(rel: JSONObject): String =
        rel.optJSONArray("media")?.optJSONObject(0)?.optJSONArray("track")?.optJSONObject(0)?.str("title").orEmpty()

    /** Releases linked as translated/transliterated tracklists. [original] = links pointing back to the original. */
    private fun translLinks(rel: JSONObject, original: Boolean): List<JSONObject> =
        rel.optJSONArray("relations")?.objects().orEmpty()
            .filter { it.str("type") == "transl-tracklisting" }
            .filter { r ->
                val status = r.optJSONObject("release")?.str("status").orEmpty()
                when {
                    status.isNotEmpty() -> (status == "Pseudo-Release") != original
                    else -> r.str("direction") == (if (original) "backward" else "forward")
                }
            }
            .mapNotNull { it.optJSONObject("release") }

    /** "Official", …; MusicBrainz's "Pseudo-Release" is shown as what it is: a translated track list. */
    private fun statusText(rel: JSONObject): String = when {
        rel.str("status") != "Pseudo-Release" -> rel.str("status")
        language(rel) == "eng" -> "English track list"
        else -> "Translated track list"
    }

    private fun language(rel: JSONObject) = rel.optJSONObject("text-representation")?.str("language").orEmpty()
    private fun script(rel: JSONObject) = rel.optJSONObject("text-representation")?.str("script").orEmpty()

    /** An English (or else Latin-letter) version of [rel], with its tracks, if MusicBrainz has one. */
    private fun englishVersion(rel: JSONObject): JSONObject? {
        val originalLatin = isLatin(rel.str("title"))
        fun useful(r: JSONObject) = language(r) == "eng" || (!originalLatin && (script(r) == "Latn" || isLatin(r.str("title"))))
        val links = translLinks(rel, original = false)
            .filter { language(it).isEmpty() || useful(it) }
            .sortedByDescending { if (language(it) == "eng") 2 else 1 }
        for (link in links.take(2)) {
            val full = try {
                get("/release/${link.str("id")}?fmt=json&inc=recordings+artist-credits+aliases")
            } catch (e: Exception) {
                null
            } ?: continue
            if (useful(full)) return full
        }
        return null
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

        val english = Prefs.preferEnglish
        val names = HashMap<String, String>()
        if (english) {
            for (r in recs.objects()) {
                inlineAliases(r.optJSONArray("artist-credit"), names)
                for (rel in r.optJSONArray("releases")?.objects().orEmpty()) {
                    if (rel.str("status") == "Pseudo-Release") latinCredits(rel.optJSONArray("artist-credit"), names)
                }
            }
            // Only a couple of extra lookups per search (1 request per second); results are remembered.
            lookupAliases(recs.objects().take(3).map { it.optJSONArray("artist-credit") }, names, max = 2)
        }

        val out = ArrayList<Candidate>()
        for (r in recs.objects()) {
            val nativeTitle = r.str("title")
            val nativeArtist = artistCredit(r.optJSONArray("artist-credit"))
            val len = r.optLong("length", 0L).let { if (it > 0) (it / 1000).toInt() else null }
            val allReleases = r.optJSONArray("releases")?.objects().orEmpty()
            // English versions of releases this recording is on (they share the recording).
            val pseudos = if (english) {
                allReleases.filter { it.str("status") == "Pseudo-Release" && isLatin(firstTrackTitle(it)) && firstTrackTitle(it).isNotBlank() }
            } else {
                emptyList()
            }
            val rTitle = if (english && !isLatin(nativeTitle)) pseudos.firstOrNull()?.let { firstTrackTitle(it) } ?: nativeTitle else nativeTitle
            val rArtist = if (english) artistCredit(r.optJSONArray("artist-credit"), names) else nativeArtist
            val releases = if (english && allReleases.any { it.str("status") != "Pseudo-Release" }) {
                allReleases.filter { it.str("status") != "Pseudo-Release" }
            } else {
                allReleases
            }
            if (releases.isEmpty()) {
                val tags = TagData(title = rTitle, artist = rArtist)
                val native = TagData(title = nativeTitle, artist = nativeArtist).takeIf { it != tags }
                out += Candidate("MusicBrainz", tags, len, null, emptyList(), nativeTags = native)
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
                val nativeAlbum = rel.str("title")
                // The English version of this release is in the same release group.
                val album = if (english && !isLatin(nativeAlbum)) {
                    pseudos.firstOrNull { rgId != null && it.optJSONObject("release-group")?.str("id") == rgId }
                        ?.str("title")?.takeIf { it.isNotBlank() && isLatin(it) } ?: nativeAlbum
                } else {
                    nativeAlbum
                }
                val nativeAlbumArtist = artistCredit(rel.optJSONArray("artist-credit")).ifBlank { nativeArtist }
                val albumArtist = if (english) artistCredit(rel.optJSONArray("artist-credit"), names).ifBlank { rArtist } else nativeAlbumArtist
                val tags = TagData(
                    title = rTitle,
                    artist = rArtist,
                    albumArtist = albumArtist,
                    album = album,
                    year = rel.str("date").take(4),
                    track = trackNo,
                    trackTotal = medium?.posInt("track-count").orEmpty(),
                    disc = medium?.posInt("position").orEmpty(),
                )
                val native = tags.copy(title = nativeTitle, artist = nativeArtist, albumArtist = nativeAlbumArtist, album = nativeAlbum)
                out += Candidate(
                    source = "MusicBrainz",
                    tags = tags,
                    durationSec = len,
                    coverThumb = "$CAA/release/$relId/front-250",
                    coverFull = coverUrls(relId, rgId),
                    releaseGroupId = rgId,
                    nativeTags = native.takeIf { it != tags },
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
                detail = listOf(formatText, rel.str("country"), statusText(rel))
                    .filter { it.isNotBlank() }.joinToString(" · "),
                coverThumb = "$CAA/release/$id/front-250",
                coverFull = coverUrls(id, rgId),
            )
        }
    }

    fun releaseTracks(id: String): AlbumTracks {
        val english = Prefs.preferEnglish
        val inc = "recordings+artist-credits+genres+release-groups" + if (english) "+aliases+release-rels" else ""
        var j = get("/release/$id?fmt=json&inc=$inc")
            ?: throw IOException("That release no longer exists on MusicBrainz")
        // English version of the release (a "pseudo-release" on MusicBrainz) to take titles from.
        var en: JSONObject? = null
        if (english) {
            if (j.str("status") == "Pseudo-Release") {
                // The English version itself was picked: dates, genre and cover come from the original.
                val originalId = translLinks(j, original = true).firstOrNull()?.str("id")
                val original = originalId?.let {
                    try {
                        get("/release/$it?fmt=json&inc=$inc")
                    } catch (e: Exception) {
                        null
                    }
                }
                if (original != null && original.optJSONArray("media")?.length() == j.optJSONArray("media")?.length()) {
                    en = j
                    j = original
                }
            } else {
                en = englishVersion(j)
            }
        }
        val names = HashMap<String, String>()
        val enTitles = HashMap<String, String>() // recording ID → English title
        val enByPosition = HashMap<String, String>() // "disc/track" → English title
        if (english) {
            en?.let { e ->
                latinCredits(e.optJSONArray("artist-credit"), names)
                e.optJSONArray("media")?.objects().orEmpty().forEachIndexed { m, med ->
                    med.optJSONArray("tracks")?.objects().orEmpty().forEachIndexed { k, t ->
                        latinCredits(t.optJSONArray("artist-credit"), names)
                        val title = t.str("title")
                        if (title.isBlank()) return@forEachIndexed
                        t.optJSONObject("recording")?.str("id")?.takeIf { it.isNotBlank() }?.let { enTitles[it] = title }
                        enByPosition["$m/$k"] = title
                    }
                }
            }
            val credits = ArrayList<JSONArray?>()
            credits += j.optJSONArray("artist-credit")
            for (med in j.optJSONArray("media")?.objects().orEmpty()) {
                for (t in med.optJSONArray("tracks")?.objects().orEmpty()) {
                    credits += t.optJSONArray("artist-credit")
                    credits += t.optJSONObject("recording")?.optJSONArray("artist-credit")
                }
            }
            for (c in credits) inlineAliases(c, names)
            lookupAliases(credits, names, max = 6)
        }
        val nameMap = if (english) names else null
        val albumTitle = en?.str("title")?.ifBlank { null } ?: j.str("title")
        val albumArtist = artistCredit(j.optJSONArray("artist-credit"), nameMap)
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
                val trackArtist = artistCredit(t.optJSONArray("artist-credit"), nameMap)
                    .ifBlank { artistCredit(rec?.optJSONArray("artist-credit"), nameMap) }
                    .ifBlank { albumArtist }
                val nativeTitle = t.str("title").ifBlank { rec?.str("title").orEmpty() }
                val title = rec?.str("id")?.let { enTitles[it] } ?: enByPosition["$m/$k"] ?: nativeTitle
                var len = t.optLong("length", 0L)
                if (len <= 0) len = rec?.optLong("length", 0L) ?: 0L
                tracks += TrackInfo(
                    TagData(
                        title = title,
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
                    nativeTitle = if (title != nativeTitle) nativeTitle else "",
                )
            }
        }
        return AlbumTracks(
            source = "MusicBrainz",
            title = albumTitle,
            artist = albumArtist,
            year = year,
            // If an English version was picked, the cover comes from the original release.
            coverThumb = "$CAA/release/${j.str("id").ifBlank { id }}/front-250",
            coverFull = coverUrls(j.str("id").ifBlank { id }, rgId),
            tracks = tracks,
        )
    }
}
