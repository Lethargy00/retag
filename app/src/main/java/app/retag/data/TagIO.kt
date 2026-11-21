package app.retag.data

import android.graphics.BitmapFactory
import android.util.Base64
import org.jaudiotagger.audio.AudioFileIO
import org.jaudiotagger.audio.mp3.MP3File
import org.jaudiotagger.tag.id3.ID3v1Tag
import org.jaudiotagger.audio.flac.metadatablock.MetadataBlockDataPicture
import org.jaudiotagger.tag.FieldKey
import org.jaudiotagger.tag.Tag
import org.jaudiotagger.tag.TagOptionSingleton
import org.jaudiotagger.tag.flac.FlacTag
import org.jaudiotagger.tag.images.ArtworkFactory
import org.jaudiotagger.tag.vorbiscomment.VorbisCommentFieldKey
import org.jaudiotagger.tag.vorbiscomment.VorbisCommentTag
import java.io.File
import java.util.logging.Level
import java.util.logging.Logger

/** Reads and writes tags inside the audio files themselves (via jaudiotagger). */
object TagIO {
    private const val FRONT_COVER = 3

    @Volatile
    private var ready = false

    private fun init() {
        if (ready) return
        synchronized(this) {
            if (ready) return
            TagOptionSingleton.getInstance().setAndroid(true)
            Logger.getLogger("org.jaudiotagger").level = Level.OFF
            ready = true
        }
    }

    /** [id] is a document ID from [Store]. jaudiotagger needs a real file, so it works on a temporary copy. */
    fun read(id: String): FileTags {
        val tmp = Store.copyToTemp(id)
        try {
            return readFile(tmp)
        } finally {
            tmp.delete()
        }
    }

    /** With [replaceAll], every existing field in the file is removed first (the cover too, unless [cover] is Keep). */
    fun write(id: String, t: TagData, cover: CoverChange, replaceAll: Boolean = false) {
        val tmp = Store.copyToTemp(id)
        try {
            writeFile(tmp, t, cover, replaceAll)
            Store.writeBack(id, tmp)
        } finally {
            tmp.delete()
        }
    }

    private fun readFile(file: File): FileTags {
        if (OpusTags.isOpus(file)) return OpusTags.read(file)
        init()
        val af = AudioFileIO.read(file)
        val duration = try {
            af.audioHeader.trackLength
        } catch (e: Exception) {
            0
        }
        val tag: Tag = af.tag ?: return FileTags(TagData(), null, duration)

        fun get(key: FieldKey): String = try {
            tag.getFirst(key)?.trim().orEmpty()
        } catch (e: Exception) {
            ""
        }

        val data = TagData(
            title = get(FieldKey.TITLE),
            artist = get(FieldKey.ARTIST),
            albumArtist = get(FieldKey.ALBUM_ARTIST),
            album = get(FieldKey.ALBUM),
            genre = get(FieldKey.GENRE),
            year = get(FieldKey.YEAR),
            track = get(FieldKey.TRACK),
            trackTotal = get(FieldKey.TRACK_TOTAL),
            disc = get(FieldKey.DISC_NO),
            discTotal = get(FieldKey.DISC_TOTAL),
            lyrics = get(FieldKey.LYRICS),
            comment = get(FieldKey.COMMENT),
        )
        val cover = try {
            tag.firstArtwork?.binaryData
        } catch (e: Exception) {
            null
        }
        return FileTags(data, cover, duration)
    }

    private fun writeFile(file: File, t: TagData, cover: CoverChange, replaceAll: Boolean) {
        if (OpusTags.isOpus(file)) {
            OpusTags.write(file, t, cover, replaceAll)
            return
        }
        init()
        val af = AudioFileIO.read(file)
        val tag: Tag = af.tagOrCreateAndSetDefault
        if (replaceAll) {
            clearAll(tag, keepCover = cover is CoverChange.Keep)
            // Old ID3v1 tags at the end of MP3s hold their own title/comment; drop them too.
            if (af is MP3File && af.hasID3v1Tag()) af.setID3v1Tag(null as ID3v1Tag?)
        }

        setText(tag, FieldKey.TITLE, t.title)
        setText(tag, FieldKey.ARTIST, t.artist)
        setText(tag, FieldKey.ALBUM_ARTIST, t.albumArtist)
        setText(tag, FieldKey.ALBUM, t.album)
        setText(tag, FieldKey.GENRE, t.genre)
        setText(tag, FieldKey.YEAR, yearOf(t.year))
        setText(tag, FieldKey.TRACK, numberOf(t.track))
        setText(tag, FieldKey.TRACK_TOTAL, numberOf(t.trackTotal))
        setText(tag, FieldKey.DISC_NO, numberOf(t.disc))
        setText(tag, FieldKey.DISC_TOTAL, numberOf(t.discTotal))
        setText(tag, FieldKey.LYRICS, t.lyrics)
        setText(tag, FieldKey.COMMENT, t.comment)

        when (cover) {
            CoverChange.Keep -> Unit
            CoverChange.Remove -> deleteArtwork(tag)
            is CoverChange.Set -> setArtwork(tag, cover.bytes)
        }
        af.commit()
    }

    /** "03/12" -> "3"; "0" or no digits -> "" */
    fun numberOf(s: String): String = Regex("\\d+").find(s)?.value?.trimStart('0') ?: ""

    fun yearOf(s: String): String = Regex("\\d{4}").find(s)?.value ?: ""

    private fun setText(tag: Tag, key: FieldKey, value: String) {
        val v = value.trim()
        try {
            if (v.isEmpty()) {
                if (tag.hasField(key)) tag.deleteField(key)
            } else {
                tag.setField(key, v)
            }
        } catch (e: UnsupportedOperationException) {
            // This format doesn't support this field; skip it.
        } catch (e: org.jaudiotagger.tag.KeyNotFoundException) {
            // Same as above.
        } catch (e: org.jaudiotagger.tag.FieldDataInvalidException) {
            throw IllegalArgumentException("Invalid value for ${key.name.lowercase()}: $v")
        }
    }

    private val PICTURE_IDS = setOf("APIC", "PIC", "COVER_ART", "COVERART", "COVR", "WM/PICTURE", "METADATA_BLOCK_PICTURE")

    /** Removes every field in the tag, including ones the app doesn't show. */
    private fun clearAll(tag: Tag, keepCover: Boolean) {
        val ids = LinkedHashSet<String>()
        try {
            val it = tag.fields
            while (it.hasNext()) {
                val f = it.next()
                val id = f.id ?: continue
                if (keepCover && (f.isBinary || id.uppercase() in PICTURE_IDS)) continue
                ids.add(id)
            }
        } catch (e: Exception) {
            // fall through with what we have
        }
        for (id in ids) {
            try {
                tag.deleteField(id)
            } catch (e: Exception) {
                // some formats refuse to delete certain fields; skip them
            }
        }
        if (!keepCover) deleteArtwork(tag)
    }

    private fun deleteArtwork(tag: Tag) {
        try {
            tag.deleteArtworkField()
        } catch (e: Exception) {
            // nothing to delete
        }
    }

    private fun setArtwork(tag: Tag, bytes: ByteArray) {
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
        val mime = opts.outMimeType ?: "image/jpeg"
        val w = opts.outWidth.coerceAtLeast(0)
        val h = opts.outHeight.coerceAtLeast(0)
        deleteArtwork(tag)
        when (tag) {
            // FLAC and Ogg need the picture dimensions, which jaudiotagger can't
            // work out on Android, so we build the picture block ourselves.
            is FlacTag -> {
                tag.setField(MetadataBlockDataPicture(bytes, FRONT_COVER, mime, "", w, h, 24, 0))
            }
            is VorbisCommentTag -> {
                val pic = MetadataBlockDataPicture(bytes, FRONT_COVER, mime, "", w, h, 24, 0)
                val b64 = Base64.encodeToString(pic.rawContent, Base64.NO_WRAP)
                tag.setField(tag.createField(VorbisCommentFieldKey.METADATA_BLOCK_PICTURE, b64))
            }
            else -> {
                val art = ArtworkFactory.getNew()
                art.binaryData = bytes
                art.mimeType = mime
                art.pictureType = FRONT_COVER
                art.description = ""
                tag.setField(art)
            }
        }
    }
}
