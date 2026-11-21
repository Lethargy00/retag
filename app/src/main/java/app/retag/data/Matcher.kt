package app.retag.data

import java.io.File
import java.text.Normalizer
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** Fuzzy string matching and scoring of online results against a file. */
object Matcher {
    private val FEAT = Regex("\\s(feat|ft|featuring)\\.?\\s.*$")
    private val BRACKETS = Regex("[(\\[][^)\\]]*[)\\]]")
    private val NON_WORD = Regex("[^\\p{L}\\p{N}]+")
    private val MARKS = Regex("\\p{M}+")

    fun norm(s: String): String {
        val base = Normalizer.normalize(s.lowercase(), Normalizer.Form.NFD).replace(MARKS, "")
        fun finish(x: String) = x.replace("&", " and ").replace(NON_WORD, " ").trim()
        val stripped = finish(base.replace(FEAT, "").replace(BRACKETS, " "))
        return stripped.ifEmpty { finish(base) }
    }

    fun sim(a: String, b: String): Double {
        val x = norm(a)
        val y = norm(b)
        if (x.isEmpty() || y.isEmpty()) return 0.0
        if (x == y) return 1.0
        val lev = 1.0 - levenshtein(x, y).toDouble() / max(x.length, y.length)
        val tx = x.split(' ').toSet()
        val ty = y.split(' ').toSet()
        val jaccard = tx.intersect(ty).size.toDouble() / tx.union(ty).size
        val contains = if (x.contains(y) || y.contains(x)) 0.85 else 0.0
        return maxOf(lev, jaccard, contains)
    }

    private fun levenshtein(a0: String, b0: String): Int {
        val a = a0.take(120)
        val b = b0.take(120)
        var prev = IntArray(b.length + 1) { it }
        var cur = IntArray(b.length + 1)
        for (i in 1..a.length) {
            cur[0] = i
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                cur[j] = min(min(cur[j - 1] + 1, prev[j] + 1), prev[j - 1] + cost)
            }
            val t = prev; prev = cur; cur = t
        }
        return prev[b.length]
    }

    private val LEADING_NUMBER = Regex("^(?:\\d{1,2}-\\d{2}\\s*[-._)]?\\s*|\\d{1,3}\\s*[-._)]\\s*|0\\d\\s+)")
    private val DASH = Regex("\\s+[-–—]\\s+")

    /** "01 - Artist - Title" -> ("Artist", "Title"); "Title" -> ("", "Title"). */
    fun parseFileName(name: String): Pair<String, String> {
        val n = name.replace('_', ' ').trim().replace(LEADING_NUMBER, "").trim()
        val parts = n.split(DASH)
        return if (parts.size >= 2) parts[0].trim() to parts.drop(1).joinToString(" - ").trim() else "" to n
    }

    fun queryFor(path: String, tags: TagData, durationSec: Int): Query {
        val (fileArtist, fileTitle) = parseFileName(Store.baseName(path))
        return Query(
            title = tags.title.ifBlank { fileTitle },
            artist = tags.artist.ifBlank { tags.albumArtist }.ifBlank { fileArtist },
            album = tags.album,
            durationSec = durationSec,
        )
    }

    fun durationScore(a: Int?, b: Int): Double {
        if (a == null || a <= 0 || b <= 0) return 0.5
        val d = abs(a - b)
        return when {
            d <= 3 -> 1.0
            d <= 8 -> 0.7
            d <= 20 -> 0.3
            else -> 0.0
        }
    }

    fun score(c: Candidate, q: Query): Double {
        val t = sim(c.tags.title, q.title)
        val d = durationScore(c.durationSec, q.durationSec)
        var s = if (q.artist.isBlank()) {
            0.65 * t + 0.35 * d
        } else {
            0.5 * t + 0.3 * sim(c.tags.artist, q.artist) + 0.2 * d
        }
        // Small nudge towards the album the file already says it's from.
        if (q.album.isNotBlank() && sim(c.tags.album, q.album) >= 0.8) s += 0.03
        return min(s, 1.0)
    }

    fun albumScore(c: AlbumCandidate, album: String, artist: String, fileCount: Int): Double {
        val a = sim(c.title, album)
        val ar = if (artist.isBlank()) 0.5 else sim(c.artist, artist)
        val countPenalty = if (fileCount > 0 && c.trackCount > 0) min(abs(c.trackCount - fileCount), 5) * 0.04 else 0.0
        return 0.6 * a + 0.4 * ar - countPenalty
    }

    /** Fill in fields from [found]. With overwrite off, only empty fields are filled. */
    fun merge(existing: TagData, found: TagData, overwrite: Boolean): TagData {
        fun pick(old: String, new: String) = when {
            new.isBlank() -> old
            overwrite || old.isBlank() -> new
            else -> old
        }
        return existing.copy(
            title = pick(existing.title, found.title),
            artist = pick(existing.artist, found.artist),
            albumArtist = pick(existing.albumArtist, found.albumArtist),
            album = pick(existing.album, found.album),
            genre = pick(existing.genre, found.genre),
            year = pick(existing.year, found.year),
            track = pick(existing.track, found.track),
            trackTotal = pick(existing.trackTotal, found.trackTotal),
            disc = pick(existing.disc, found.disc),
            discTotal = pick(existing.discTotal, found.discTotal),
        )
    }

    private fun fileTrackNumber(f: FileInfo): Int? {
        TagIO.numberOf(f.tags.track).toIntOrNull()?.let { return it }
        val name = Store.baseName(f.path)
        val m = Regex("^(?:\\d-)?(\\d{1,3})(?!\\d)|^track\\s*(\\d{1,3})", RegexOption.IGNORE_CASE).find(name) ?: return null
        return m.groupValues[1].ifEmpty { m.groupValues[2] }.toIntOrNull()
    }

    /** Pairs each file with the most likely track on the chosen album. */
    fun assign(files: List<FileInfo>, tracks: List<TrackInfo>): List<Assignment> {
        val titles = files.map { f -> f.tags.title.ifBlank { parseFileName(Store.baseName(f.path)).second } }
        val numbers = files.map { fileTrackNumber(it) }
        val discs = files.map { TagIO.numberOf(it.tags.disc).toIntOrNull() }

        data class Pair3(val f: Int, val t: Int, val s: Double)
        val pairs = ArrayList<Pair3>()
        for (i in files.indices) for (j in tracks.indices) {
            val tr = tracks[j]
            var s = 0.6 * sim(titles[i], tr.tags.title)
            val tn = tr.tags.track.toIntOrNull()
            val td = tr.tags.disc.toIntOrNull()
            if (numbers[i] != null && numbers[i] == tn && (discs[i] == null || td == null || discs[i] == td)) s += 0.25
            s += 0.15 * durationScore(tr.durationSec, files[i].durationSec)
            pairs += Pair3(i, j, s)
        }
        pairs.sortByDescending { it.s }
        val fileTo = arrayOfNulls<Pair3>(files.size)
        val usedTracks = HashSet<Int>()
        for (p in pairs) {
            if (p.s < 0.4) break
            if (fileTo[p.f] != null || p.t in usedTracks) continue
            fileTo[p.f] = p
            usedTracks += p.t
        }
        return files.mapIndexed { i, f ->
            val p = fileTo[i]
            Assignment(f.path, p?.let { tracks[it.t] }, min(p?.s ?: 0.0, 1.0))
        }
    }
}
