package app.retag.data

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.ContextCompat
import java.io.File

object Library {
    val EXTENSIONS = setOf("mp3", "m4a", "m4b", "flac", "ogg", "wma", "wav", "aif", "aiff", "dsf")

    fun hasPermission(ctx: Context): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.WRITE_EXTERNAL_STORAGE) ==
                PackageManager.PERMISSION_GRANTED
        }

    /** Internal storage plus any SD cards. */
    private fun roots(ctx: Context): List<File> {
        val list = mutableListOf(Environment.getExternalStorageDirectory())
        for (dir in ctx.getExternalFilesDirs(null)) {
            if (dir == null) continue
            val p = dir.absolutePath
            val i = p.indexOf("/Android/data")
            if (i > 0) {
                val root = File(p.substring(0, i))
                if (list.none { it.absolutePath == root.absolutePath }) list.add(root)
            }
        }
        return list
    }

    private class Meta(val title: String, val artist: String, val album: String)

    private fun mediaStoreMeta(ctx: Context): Map<String, Meta> {
        val map = HashMap<String, Meta>()
        val projection = arrayOf(
            MediaStore.Audio.Media.DATA,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.ALBUM,
        )
        try {
            ctx.contentResolver.query(
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, projection, null, null, null
            )?.use { c ->
                while (c.moveToNext()) {
                    val path = c.getString(0) ?: continue
                    map[path] = Meta(
                        title = c.getString(1).orEmpty(),
                        artist = clean(c.getString(2)),
                        album = clean(c.getString(3)),
                    )
                }
            }
        } catch (e: Exception) {
            // MediaStore is only used for faster display; the scan still works without it.
        }
        return map
    }

    private fun clean(s: String?): String = if (s == null || s == "<unknown>") "" else s

    fun scan(ctx: Context): List<Song> {
        val meta = mediaStoreMeta(ctx)
        val seen = HashSet<String>()
        val out = ArrayList<Song>()
        for (root in roots(ctx)) {
            root.walkTopDown()
                .maxDepth(15)
                .onEnter { d ->
                    !d.name.startsWith(".") && !(d.name == "Android" && d.parentFile?.absolutePath == root.absolutePath)
                }
                .filter { it.isFile && it.extension.lowercase() in EXTENSIONS }
                .forEach { f ->
                    val path = f.absolutePath
                    if (!seen.add(path)) return@forEach
                    val m = meta[path]
                    out.add(
                        Song(
                            path = path,
                            title = m?.title?.ifBlank { null } ?: f.nameWithoutExtension,
                            artist = m?.artist.orEmpty(),
                            album = m?.album.orEmpty(),
                        )
                    )
                }
        }
        out.sortWith(compareBy({ it.folder.lowercase() }, { it.fileName.lowercase() }))
        return out
    }
}
