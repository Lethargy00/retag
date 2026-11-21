package app.retag.data

/**
 * File and folder name patterns, in the style of Mp3tag / foobar2000:
 *  - %title%, %artist%, %albumartist%, %album%, %year%, %track%, %disc%, %genre%
 *  - [ ... ] is an optional part, left out when any field inside it is empty.
 *    "%title%[ - %year%]" gives "Song - 1999", or just "Song" when there's no year.
 *  - In folder patterns, "/" starts a new folder level.
 */
object Naming {
    val TOKENS = listOf("title", "artist", "albumartist", "album", "year", "track", "disc", "genre")

    val SAMPLE = TagData(
        title = "Bohemian Rhapsody",
        artist = "Queen",
        albumArtist = "Queen",
        album = "A Night at the Opera",
        year = "1975",
        track = "11",
        disc = "1",
        genre = "Rock",
    )

    private fun value(token: String, t: TagData): String? = when (token.lowercase()) {
        "title" -> t.title
        "artist" -> t.artist.ifBlank { t.albumArtist }
        "albumartist" -> t.albumArtist.ifBlank { t.artist }
        "album" -> t.album
        "year" -> TagIO.yearOf(t.year)
        "track" -> TagIO.numberOf(t.track).let { if (it.isEmpty()) "" else it.padStart(2, '0') }
        "disc" -> TagIO.numberOf(t.disc)
        "genre" -> t.genre
        else -> null // not a field: keep the text as typed
    }?.trim()?.let(::cleanPart)

    private class Out(val text: String, val missing: Boolean)

    /** Fills in a pattern. Returns the text and whether a required field was empty. */
    private fun expand(p: String, t: TagData, start: Int, nested: Boolean): Pair<Out, Int> {
        val sb = StringBuilder()
        var missing = false
        var i = start
        while (i < p.length) {
            val ch = p[i]
            when {
                ch == '[' -> {
                    val (inner, next) = expand(p, t, i + 1, true)
                    if (!inner.missing) sb.append(inner.text)
                    i = next
                    continue
                }
                ch == ']' && nested -> return Out(sb.toString(), missing) to i + 1
                ch == '%' -> {
                    val end = p.indexOf('%', i + 1)
                    if (end > i) {
                        val token = p.substring(i + 1, end)
                        val v = value(token, t)
                        if (v != null) {
                            if (v.isEmpty()) missing = true
                            sb.append(v)
                            i = end + 1
                            continue
                        }
                    }
                    sb.append(ch)
                }
                else -> sb.append(ch)
            }
            i++
        }
        return Out(sb.toString(), missing) to i
    }

    fun format(pattern: String, t: TagData): String = expand(pattern, t, 0, false).first.text

    /** Characters Android/SD cards don't allow in names. */
    private fun cleanPart(s: String): String = s
        .replace(Regex("[/\\\\|]"), "-")
        .replace(":", " -")
        .replace("\"", "'")
        .replace(Regex("[*?<>\\u0000-\\u001f]"), "")

    private fun tidy(s: String): String = cleanPart(s)
        .replace(Regex("\\s+"), " ")
        .trim()
        .trimEnd('.', ' ')
        .take(150)
        .trim()

    /** New file name without extension, or null when there's nothing sensible to name it. */
    fun fileBase(t: TagData, pattern: String = Prefs.renamePattern): String? {
        if (t.title.isBlank()) return null
        // "/" isn't allowed in a file name, so treat it as text here.
        return tidy(format(pattern.replace("/", "-"), t)).ifBlank { null }
    }

    /** Folder levels below the chosen folder, e.g. ["Queen", "A Night at the Opera"]. */
    fun folders(t: TagData, pattern: String = Prefs.folderPattern): List<String> =
        pattern.split('/').filter { it.isNotBlank() }.map { level ->
            tidy(format(level, t)).ifBlank {
                when {
                    level.contains("%album%", ignoreCase = true) -> "Unknown album"
                    level.contains("artist%", ignoreCase = true) -> "Unknown artist"
                    else -> ""
                }
            }
        }.filter { it.isNotBlank() }
}

/** Saving tags, then renaming/moving the file if the user turned that on. */
object Organizer {
    class Saved(val id: String, val note: String?)

    fun save(id: String, tags: TagData, cover: CoverChange): Saved {
        TagIO.write(id, tags, cover)
        if (!Prefs.renameOn && !Prefs.organizeOn) return Saved(id, null)
        return try {
            Saved(organize(id, tags, Prefs.renameOn, Prefs.organizeOn), null)
        } catch (e: Exception) {
            Saved(id, "Tags saved, but renaming/moving failed: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    /** Renames and/or moves one file. Returns its new ID (the same one if nothing changed). */
    fun organize(id: String, tags: TagData, rename: Boolean, folders: Boolean): String {
        if (!rename && !folders) return id
        val curName = Store.name(id)
        val ext = curName.substringAfterLast('.', "")
        val curParent = Store.parentId(id) ?: Store.rootId()

        val target = if (folders) {
            var p = Store.rootId()
            for (level in Naming.folders(tags)) p = Store.findOrCreateDir(p, level)
            p
        } else {
            curParent
        }
        val wanted = if (rename) Naming.fileBase(tags)?.let { if (ext.isEmpty()) it else "$it.$ext" } ?: curName else curName
        if (target == curParent && wanted == curName) return id

        val taken = Store.children(target).filter { it.id != id }.mapTo(HashSet()) { it.name.lowercase() }
        val finalName = unique(wanted, taken)

        var cur = id
        if (target != curParent) {
            if (curName.lowercase() in taken) cur = Store.rename(cur, tempName(ext))
            cur = Store.move(cur, curParent, target)
        }
        val nowName = Store.name(cur)
        if (nowName != finalName) {
            // Some storage treats "song.mp3" and "Song.mp3" as the same file.
            if (nowName.equals(finalName, ignoreCase = true)) cur = Store.rename(cur, tempName(ext))
            cur = Store.rename(cur, finalName)
        }
        if (target != curParent) {
            try {
                Store.deleteEmptyDirs(curParent)
            } catch (e: Exception) {
                // leaving an empty folder is harmless
            }
        }
        Store.mediaScan(id, cur)
        return cur
    }

    private fun tempName(ext: String) = "retag-temp-${System.nanoTime()}" + if (ext.isEmpty()) "" else ".$ext"

    private fun unique(name: String, taken: Set<String>): String {
        if (name.lowercase() !in taken) return name
        val dot = name.lastIndexOf('.')
        val base = if (dot > 0) name.substring(0, dot) else name
        val ext = if (dot > 0) name.substring(dot) else ""
        var n = 2
        while ("$base ($n)$ext".lowercase() in taken) n++
        return "$base ($n)$ext"
    }
}
