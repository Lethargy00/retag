package app.retag.data

import java.io.File

/** All the tag fields the app edits. Empty string = no value. */
data class TagData(
    val title: String = "",
    val artist: String = "",
    val albumArtist: String = "",
    val album: String = "",
    val genre: String = "",
    val year: String = "",
    val track: String = "",
    val trackTotal: String = "",
    val disc: String = "",
    val discTotal: String = "",
    val lyrics: String = "",
    val comment: String = "",
)

sealed interface CoverChange {
    data object Keep : CoverChange
    data object Remove : CoverChange
    class Set(val bytes: ByteArray) : CoverChange
}

class FileTags(val tags: TagData, val cover: ByteArray?, val durationSec: Int)

data class Song(
    val path: String,
    val title: String,
    val artist: String,
    val album: String,
) {
    val fileName: String get() = File(path).name
    val folder: String get() = File(path).parent ?: ""
    val missingTags: Boolean get() = artist.isBlank() || album.isBlank()
}

/** A possible match for one song, from an online source. */
data class Candidate(
    val source: String,
    val tags: TagData,
    val durationSec: Int?,
    val coverThumb: String?,
    val coverFull: List<String>,
    val releaseGroupId: String? = null,
    val score: Double = 0.0,
)

/** A possible album/release for "tag as album". */
data class AlbumCandidate(
    val source: String,
    val id: String,
    val title: String,
    val artist: String,
    val year: String,
    val trackCount: Int,
    val detail: String,
    val coverThumb: String?,
    val coverFull: List<String>,
    val score: Double = 0.0,
)

data class TrackInfo(val tags: TagData, val durationSec: Int?)

data class AlbumTracks(
    val source: String,
    val title: String,
    val artist: String,
    val year: String,
    val coverThumb: String?,
    val coverFull: List<String>,
    val tracks: List<TrackInfo>,
)

data class FileInfo(val path: String, val tags: TagData, val durationSec: Int, val hasCover: Boolean)

data class Assignment(val path: String, val track: TrackInfo?, val confidence: Double)

data class CoverOption(val label: String, val source: String, val thumb: String, val full: List<String>)

data class Query(val title: String, val artist: String, val album: String, val durationSec: Int)
