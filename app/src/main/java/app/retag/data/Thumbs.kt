package app.retag.data

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.util.LruCache
import androidx.compose.runtime.mutableStateMapOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest

/** Small cover thumbnails for the song list, cached in memory and on disk. */
object Thumbs {
    private const val SIZE = 144

    // Android's own reader can't get pictures out of these, so read them with the tag library instead.
    private val SLOW_FORMATS = setOf("ogg", "opus", "wma", "dsf", "aif", "aiff", "wav")

    private val memory = LruCache<String, Bitmap>(300)
    private val gate = Semaphore(3)

    /** Bumped when a file's cover may have changed, so the list reloads it. */
    val versions = mutableStateMapOf<String, Int>()

    private val dir: File get() = File(Store.app.cacheDir, "thumbs").apply { mkdirs() }

    private fun key(id: String): String =
        MessageDigest.getInstance("SHA-1").digest(id.toByteArray()).joinToString("") { "%02x".format(it) }

    private fun jpg(id: String) = File(dir, key(id) + ".jpg")
    private fun none(id: String) = File(dir, key(id) + ".none")

    suspend fun load(id: String): Bitmap? {
        memory.get(id)?.let { return it }
        return withContext(Dispatchers.IO) {
            gate.withPermit {
                memory.get(id)?.let { return@withPermit it }
                val file = jpg(id)
                if (file.exists()) {
                    BitmapFactory.decodeFile(file.path)?.let {
                        memory.put(id, it)
                        return@withPermit it
                    }
                }
                if (none(id).exists()) return@withPermit null
                val bytes = try {
                    embedded(id)
                } catch (e: Exception) {
                    null
                }
                val bmp = bytes?.let { decodeSmall(it) }
                if (bmp == null) {
                    none(id).createNewFile()
                    return@withPermit null
                }
                try {
                    FileOutputStream(file).use { bmp.compress(Bitmap.CompressFormat.JPEG, 85, it) }
                } catch (e: Exception) {
                    // memory cache still works
                }
                memory.put(id, bmp)
                bmp
            }
        }
    }

    private fun embedded(id: String): ByteArray? {
        val r = MediaMetadataRetriever()
        val fromAndroid = try {
            r.setDataSource(Store.app, Store.uri(id))
            r.embeddedPicture
        } catch (e: Exception) {
            null
        } finally {
            try {
                r.release()
            } catch (e: Exception) {
                // ignore
            }
        }
        if (fromAndroid != null) return fromAndroid
        return if (Store.extension(id) in SLOW_FORMATS) TagIO.read(id).cover else null
    }

    private fun decodeSmall(bytes: ByteArray): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= SIZE && bounds.outHeight / (sample * 2) >= SIZE) sample *= 2
        val full = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
            ?: return null
        val side = minOf(full.width, full.height)
        val square = Bitmap.createBitmap(full, (full.width - side) / 2, (full.height - side) / 2, side, side)
        return if (side > SIZE) Bitmap.createScaledBitmap(square, SIZE, SIZE, true) else square
    }

    /** Call after a file's tags were written or the file changed outside the app. */
    fun invalidate(vararg ids: String) {
        for (id in ids) {
            memory.remove(id)
            jpg(id).delete()
            none(id).delete()
            versions[id] = (versions[id] ?: 0) + 1
        }
    }
}
