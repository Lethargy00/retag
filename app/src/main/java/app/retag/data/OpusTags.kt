package app.retag.data

import android.graphics.BitmapFactory
import android.util.Base64
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Reads and writes tags in Ogg Opus files (.opus), which jaudiotagger doesn't support.
 * Opus keeps its tags in an "OpusTags" packet right after the "OpusHead" packet; the format
 * is the same as Ogg Vorbis comments, and cover art is a base64 FLAC picture block
 * (METADATA_BLOCK_PICTURE). See RFC 7845.
 */
object OpusTags {
    private const val PICTURE = "METADATA_BLOCK_PICTURE"
    private const val FRONT_COVER = 3

    private val MANAGED = setOf(
        "TITLE", "ARTIST", "ALBUMARTIST", "ALBUM ARTIST", "ALBUM_ARTIST", "ALBUM", "GENRE", "DATE", "YEAR",
        "TRACKNUMBER", "TRACKTOTAL", "TOTALTRACKS", "DISCNUMBER", "DISCTOTAL", "TOTALDISCS",
        "LYRICS", "UNSYNCEDLYRICS", "COMMENT", "DESCRIPTION",
    )

    /** True when the file is an Ogg stream that carries Opus audio (whatever its extension). */
    fun isOpus(f: File): Boolean = try {
        RandomAccessFile(f, "r").use { raf ->
            val p = readPage(raf) ?: return false
            p.data.size >= 8 && String(p.data, 0, 8, Charsets.US_ASCII) == "OpusHead"
        }
    } catch (e: Exception) {
        false
    }

    // ---------------- Ogg pages ----------------

    private class Page(
        val type: Int,
        val granule: Long,
        val serial: Int,
        val seq: Int,
        val segments: ByteArray,
        val data: ByteArray,
        val length: Int,
    )

    private fun readPage(raf: RandomAccessFile): Page? {
        val hdr = ByteArray(27)
        val n = raf.read(hdr)
        if (n <= 0) return null
        if (n < 27 || hdr[0] != 'O'.code.toByte() || hdr[1] != 'g'.code.toByte() || hdr[2] != 'g'.code.toByte() || hdr[3] != 'S'.code.toByte()) {
            throw IOException("Damaged Ogg file")
        }
        val bb = ByteBuffer.wrap(hdr).order(ByteOrder.LITTLE_ENDIAN)
        val nseg = hdr[26].toInt() and 0xff
        val segs = ByteArray(nseg)
        raf.readFully(segs)
        val size = segs.sumOf { it.toInt() and 0xff }
        val data = ByteArray(size)
        raf.readFully(data)
        return Page(hdr[5].toInt() and 0xff, bb.getLong(6), bb.getInt(14), bb.getInt(18), segs, data, 27 + nseg + size)
    }

    private val CRC_TABLE = IntArray(256) { i ->
        var r = i shl 24
        repeat(8) { r = if (r and 0x80000000.toInt() != 0) (r shl 1) xor 0x04c11db7 else r shl 1 }
        r
    }

    private fun crc(b: ByteArray): Int {
        var c = 0
        for (x in b) c = (c shl 8) xor CRC_TABLE[((c ushr 24) xor (x.toInt() and 0xff)) and 0xff]
        return c
    }

    private fun pageBytes(type: Int, granule: Long, serial: Int, seq: Int, segs: ByteArray, data: ByteArray, off: Int, len: Int): ByteArray {
        val b = ByteArray(27 + segs.size + len)
        val bb = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN)
        b[0] = 'O'.code.toByte(); b[1] = 'g'.code.toByte(); b[2] = 'g'.code.toByte(); b[3] = 'S'.code.toByte()
        b[4] = 0
        b[5] = type.toByte()
        bb.putLong(6, granule)
        bb.putInt(14, serial)
        bb.putInt(18, seq)
        bb.putInt(22, 0)
        b[26] = segs.size.toByte()
        System.arraycopy(segs, 0, b, 27, segs.size)
        System.arraycopy(data, off, b, 27 + segs.size, len)
        bb.putInt(22, crc(b))
        return b
    }

    /** Splits one packet into Ogg pages starting at sequence number [firstSeq]. */
    private fun paginate(packet: ByteArray, serial: Int, firstSeq: Int): List<ByteArray> {
        val lacing = ArrayList<Int>()
        var left = packet.size
        while (left >= 255) {
            lacing.add(255)
            left -= 255
        }
        lacing.add(left)
        val pages = ArrayList<ByteArray>()
        var off = 0
        val chunks = lacing.chunked(255)
        for ((i, chunk) in chunks.withIndex()) {
            val last = i == chunks.lastIndex
            val len = chunk.sum()
            val segs = ByteArray(chunk.size) { chunk[it].toByte() }
            pages.add(
                pageBytes(
                    type = if (i == 0) 0 else 1, // 1 = continues the packet from the previous page
                    granule = if (last) 0L else -1L, // -1 = no packet ends on this page
                    serial = serial,
                    seq = firstSeq + i,
                    segs = segs,
                    data = packet,
                    off = off,
                    len = len,
                )
            )
            off += len
        }
        return pages
    }

    private class Headers(
        val head: ByteArray,
        val firstPageLength: Int,
        val tags: ByteArray,
        val tagPageCount: Int,
        val audioStart: Long,
        val serial: Int,
    )

    private fun readHeaders(raf: RandomAccessFile): Headers {
        raf.seek(0)
        val p0 = readPage(raf) ?: throw IOException("Empty file")
        if (p0.data.size < 19 || String(p0.data, 0, 8, Charsets.US_ASCII) != "OpusHead") throw IOException("Not an Opus file")
        val packet = ByteArrayOutputStream()
        var pages = 0
        var done = false
        while (!done) {
            val p = readPage(raf) ?: throw IOException("Opus file ends before its tags")
            if (p.serial != p0.serial) throw IOException("Opus files with several streams aren't supported")
            pages++
            var pos = 0
            for ((i, s) in p.segments.withIndex()) {
                val len = s.toInt() and 0xff
                packet.write(p.data, pos, len)
                pos += len
                if (len < 255) {
                    done = true
                    if (i != p.segments.lastIndex) throw IOException("Unusual Opus file layout; can't safely edit its tags")
                    break
                }
            }
            if (pages > 2000) throw IOException("Opus tags are too large")
        }
        return Headers(p0.data, p0.length, packet.toByteArray(), pages, raf.filePointer, p0.serial)
    }

    // ---------------- Comments ----------------

    private class Comments(val vendor: String, val items: List<Pair<String, String>>, val extra: ByteArray)

    private fun parse(b: ByteArray): Comments {
        if (b.size < 16 || String(b, 0, 8, Charsets.US_ASCII) != "OpusTags") throw IOException("Opus tags are damaged")
        val bb = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN)
        bb.position(8)
        fun str(): String {
            val len = bb.int
            if (len < 0 || len > bb.remaining()) throw IOException("Opus tags are damaged")
            val s = String(b, bb.position(), len, Charsets.UTF_8)
            bb.position(bb.position() + len)
            return s
        }
        val vendor = str()
        val count = bb.int
        val items = ArrayList<Pair<String, String>>()
        repeat(count.coerceIn(0, 100_000)) {
            val c = str()
            val eq = c.indexOf('=')
            if (eq > 0) items.add(c.substring(0, eq) to c.substring(eq + 1))
        }
        // RFC 7845: data after the comments must be kept if its first bit is set.
        val extra = if (bb.hasRemaining() && (b[bb.position()].toInt() and 1) == 1) b.copyOfRange(bb.position(), b.size) else ByteArray(0)
        return Comments(vendor, items, extra)
    }

    private fun build(c: Comments): ByteArray {
        val out = ByteArrayOutputStream()
        fun int(v: Int) = out.write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(v).array())
        fun str(s: String) {
            val bytes = s.toByteArray(Charsets.UTF_8)
            int(bytes.size)
            out.write(bytes)
        }
        out.write("OpusTags".toByteArray(Charsets.US_ASCII))
        str(c.vendor)
        int(c.items.size)
        for ((k, v) in c.items) str("$k=$v")
        out.write(c.extra)
        return out.toByteArray()
    }

    // ---------------- Pictures ----------------

    private fun pictureData(b64: String): Pair<Int, ByteArray>? = try {
        val b = Base64.decode(b64, Base64.DEFAULT)
        val bb = ByteBuffer.wrap(b).order(ByteOrder.BIG_ENDIAN)
        val type = bb.int
        val mimeLen = bb.int
        bb.position(bb.position() + mimeLen)
        val descLen = bb.int
        bb.position(bb.position() + descLen)
        bb.position(bb.position() + 16) // width, height, depth, colours
        val len = bb.int
        type to b.copyOfRange(bb.position(), bb.position() + len)
    } catch (e: Exception) {
        null
    }

    private fun pictureBlock(bytes: ByteArray): String {
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
        val mime = (opts.outMimeType ?: "image/jpeg").toByteArray(Charsets.US_ASCII)
        val bb = ByteBuffer.allocate(32 + mime.size + bytes.size).order(ByteOrder.BIG_ENDIAN)
        bb.putInt(FRONT_COVER)
        bb.putInt(mime.size).put(mime)
        bb.putInt(0) // no description
        bb.putInt(opts.outWidth.coerceAtLeast(0))
        bb.putInt(opts.outHeight.coerceAtLeast(0))
        bb.putInt(24)
        bb.putInt(0)
        bb.putInt(bytes.size).put(bytes)
        return Base64.encodeToString(bb.array(), Base64.NO_WRAP)
    }

    // ---------------- Public API ----------------

    fun read(f: File): FileTags = RandomAccessFile(f, "r").use { raf ->
        val h = readHeaders(raf)
        val c = parse(h.tags)
        fun get(vararg keys: String): String =
            keys.firstNotNullOfOrNull { k -> c.items.firstOrNull { it.first.equals(k, ignoreCase = true) }?.second }?.trim().orEmpty()

        val tags = TagData(
            title = get("TITLE"),
            artist = get("ARTIST"),
            albumArtist = get("ALBUMARTIST", "ALBUM ARTIST", "ALBUM_ARTIST"),
            album = get("ALBUM"),
            genre = get("GENRE"),
            year = get("DATE", "YEAR"),
            track = get("TRACKNUMBER"),
            trackTotal = get("TRACKTOTAL", "TOTALTRACKS"),
            disc = get("DISCNUMBER"),
            discTotal = get("DISCTOTAL", "TOTALDISCS"),
            lyrics = get("LYRICS", "UNSYNCEDLYRICS"),
            comment = get("COMMENT", "DESCRIPTION"),
        )
        val pictures = c.items.filter { it.first.equals(PICTURE, ignoreCase = true) }.mapNotNull { pictureData(it.second) }
        val cover = (pictures.firstOrNull { it.first == FRONT_COVER } ?: pictures.firstOrNull())?.second
        FileTags(tags, cover, duration(raf, h))
    }

    /** Last page's granule position minus the pre-skip, at 48 kHz. */
    private fun duration(raf: RandomAccessFile, h: Headers): Int = try {
        val preSkip = (h.head[10].toInt() and 0xff) or ((h.head[11].toInt() and 0xff) shl 8)
        val len = raf.length()
        val tail = minOf(len, 256 * 1024L).toInt()
        val buf = ByteArray(tail)
        raf.seek(len - tail)
        raf.readFully(buf)
        var granule = -1L
        var i = tail - 27
        while (i >= 0) {
            if (buf[i] == 'O'.code.toByte() && buf[i + 1] == 'g'.code.toByte() && buf[i + 2] == 'g'.code.toByte() && buf[i + 3] == 'S'.code.toByte()) {
                val bb = ByteBuffer.wrap(buf, i, 27).order(ByteOrder.LITTLE_ENDIAN)
                val g = bb.getLong(i + 6)
                if (bb.getInt(i + 14) == h.serial && g > 0) {
                    granule = g
                    break
                }
            }
            i--
        }
        if (granule <= 0) 0 else ((granule - preSkip) / 48000).toInt().coerceAtLeast(0)
    } catch (e: Exception) {
        0
    }

    fun write(f: File, t: TagData, cover: CoverChange) {
        val out = File(f.path + ".new")
        RandomAccessFile(f, "r").use { raf ->
            val h = readHeaders(raf)
            val old = parse(h.tags)
            val items = ArrayList<Pair<String, String>>()
            for (it in old.items) {
                val k = it.first.uppercase()
                if (k in MANAGED) continue
                if (k == PICTURE && cover !is CoverChange.Keep) continue
                items.add(it)
            }
            fun add(k: String, v: String) {
                if (v.isNotBlank()) items.add(k to v.trim())
            }
            add("TITLE", t.title)
            add("ARTIST", t.artist)
            add("ALBUMARTIST", t.albumArtist)
            add("ALBUM", t.album)
            add("GENRE", t.genre)
            add("DATE", TagIO.yearOf(t.year).ifBlank { t.year })
            add("TRACKNUMBER", TagIO.numberOf(t.track))
            add("TRACKTOTAL", TagIO.numberOf(t.trackTotal))
            add("DISCNUMBER", TagIO.numberOf(t.disc))
            add("DISCTOTAL", TagIO.numberOf(t.discTotal))
            add("LYRICS", t.lyrics)
            add("COMMENT", t.comment)
            if (cover is CoverChange.Set) {
                // Put the picture first so it isn't lost among other fields.
                items.add(0, PICTURE to pictureBlock(cover.bytes))
            }
            val packet = build(Comments(old.vendor, items, old.extra))
            val tagPages = paginate(packet, h.serial, firstSeq = 1)
            val shift = tagPages.size - h.tagPageCount

            FileOutputStream(out).use { o ->
                raf.seek(0)
                copy(raf, o, h.firstPageLength.toLong())
                for (p in tagPages) o.write(p)
                raf.seek(h.audioStart)
                if (shift == 0) {
                    copy(raf, o, raf.length() - h.audioStart)
                } else {
                    // Page numbers after the tags change, so each page needs a new number and checksum.
                    while (true) {
                        val p = readPage(raf) ?: break
                        val seq = if (p.serial == h.serial) p.seq + shift else p.seq
                        o.write(pageBytes(p.type, p.granule, p.serial, seq, p.segments, p.data, 0, p.data.size))
                    }
                }
            }
        }
        if (!f.delete() || !out.renameTo(f)) {
            out.delete()
            throw IOException("Couldn't save the Opus file")
        }
    }

    private fun copy(raf: RandomAccessFile, o: OutputStream, count: Long) {
        val buf = ByteArray(256 * 1024)
        var left = count
        while (left > 0) {
            val n = raf.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
            if (n <= 0) break
            o.write(buf, 0, n)
            left -= n
        }
    }
}
