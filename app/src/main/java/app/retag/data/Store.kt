package app.retag.data

import android.content.Context
import android.content.Intent
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * All file access goes through here. The app only sees the one folder the user picked
 * (Storage Access Framework), so files are identified by their document ID, not a path.
 */
object Store {
    private const val EXTERNAL_STORAGE = "com.android.externalstorage.documents"

    lateinit var app: Context
        private set

    private class Entry(val name: String, val parent: String?)

    private val entries = ConcurrentHashMap<String, Entry>()

    class Child(val id: String, val name: String, val isDir: Boolean, val modified: Long, val size: Long)

    fun init(ctx: Context) {
        app = ctx.applicationContext
        app.cacheDir.listFiles()?.filter { it.name.startsWith("rt-") }?.forEach { it.delete() }
    }

    private val tree: Uri get() = Prefs.treeUri ?: throw IOException("No music folder chosen")

    fun hasAccess(): Boolean {
        val t = Prefs.treeUri ?: return false
        return app.contentResolver.persistedUriPermissions.any {
            it.uri == t && it.isReadPermission && it.isWritePermission
        }
    }

    fun setTree(uri: Uri) {
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        app.contentResolver.takePersistableUriPermission(uri, flags)
        val old = Prefs.treeUri
        if (old != null && old != uri) {
            try {
                app.contentResolver.releasePersistableUriPermission(old, flags)
            } catch (e: Exception) {
                // already gone
            }
        }
        entries.clear()
        Prefs.treeUri = uri
    }

    /** A folder to open the picker in: the phone's Music folder. */
    fun suggestedStart(): Uri = DocumentsContract.buildDocumentUri(EXTERNAL_STORAGE, "primary:Music")

    fun rootId(): String = DocumentsContract.getTreeDocumentId(tree)

    fun uri(id: String): Uri = DocumentsContract.buildDocumentUriUsingTree(tree, id)

    fun rootName(): String = try {
        name(rootId())
    } catch (e: Exception) {
        "Music"
    }

    fun remember(id: String, name: String, parent: String?) {
        entries[id] = Entry(name, parent)
    }

    fun children(parentId: String): List<Child> {
        val out = ArrayList<Child>()
        val projection = arrayOf(
            Document.COLUMN_DOCUMENT_ID,
            Document.COLUMN_DISPLAY_NAME,
            Document.COLUMN_MIME_TYPE,
            Document.COLUMN_LAST_MODIFIED,
            Document.COLUMN_SIZE,
        )
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(tree, parentId)
        app.contentResolver.query(childrenUri, projection, null, null, null)?.use { c ->
            while (c.moveToNext()) {
                val id = c.getString(0) ?: continue
                val name = c.getString(1) ?: continue
                val isDir = c.getString(2) == Document.MIME_TYPE_DIR
                val mod = if (c.isNull(3)) 0L else c.getLong(3)
                val size = if (c.isNull(4)) 0L else c.getLong(4)
                entries[id] = Entry(name, parentId)
                out.add(Child(id, name, isDir, mod, size))
            }
        }
        return out
    }

    /** (last modified, size) of one file, as the folder listing reports them. Null if it can't be read. */
    fun stat(id: String): Pair<Long, Long>? = try {
        app.contentResolver.query(uri(id), arrayOf(Document.COLUMN_LAST_MODIFIED, Document.COLUMN_SIZE), null, null, null)?.use { c ->
            if (c.moveToFirst() && !c.isNull(0) && !c.isNull(1)) c.getLong(0) to c.getLong(1) else null
        }
    } catch (e: Exception) {
        null
    }

    private fun queryName(id: String): String? = try {
        app.contentResolver.query(uri(id), arrayOf(Document.COLUMN_DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    } catch (e: Exception) {
        null
    }

    fun name(id: String): String {
        entries[id]?.let { return it.name }
        val n = queryName(id)
            ?: id.substringAfterLast('/').substringAfterLast(':').ifBlank { id }
        entries[id] = Entry(n, entries[id]?.parent ?: guessParent(id))
        return n
    }

    fun baseName(id: String): String = name(id).let { n ->
        val dot = n.lastIndexOf('.')
        if (dot > 0) n.substring(0, dot) else n
    }

    fun extension(id: String): String = name(id).substringAfterLast('.', "").lowercase()

    /** Local storage IDs look like "primary:Music/Artist/Song.mp3", so the parent is easy to work out. */
    private fun guessParent(id: String): String? = if (id.contains('/')) id.substringBeforeLast('/') else null

    fun parentId(id: String): String? = entries[id]?.parent ?: guessParent(id)

    fun parentName(id: String): String {
        val p = parentId(id) ?: return ""
        return name(p)
    }

    /** Folder of a file relative to the chosen folder, e.g. "Queen/A Night at the Opera". "" = top level. */
    fun relFolder(id: String): String {
        val root = try {
            rootId()
        } catch (e: Exception) {
            return ""
        }
        val parts = ArrayList<String>()
        var p = parentId(id)
        var guard = 0
        while (p != null && p != root && guard++ < 40) {
            parts.add(name(p))
            p = parentId(p)
        }
        return parts.reversed().joinToString("/")
    }

    fun copyToTemp(id: String): File {
        val ext = extension(id).ifBlank { "bin" }
        val tmp = File(app.cacheDir, "rt-${UUID.randomUUID()}.$ext")
        val input = app.contentResolver.openInputStream(uri(id)) ?: throw IOException("Couldn't open ${name(id)}")
        input.use { i -> FileOutputStream(tmp).use { o -> i.copyTo(o, 256 * 1024) } }
        return tmp
    }

    fun writeBack(id: String, file: File) {
        val pfd = app.contentResolver.openFileDescriptor(uri(id), "rwt") ?: throw IOException("Couldn't write ${name(id)}")
        pfd.use { fd ->
            FileOutputStream(fd.fileDescriptor).use { o ->
                FileInputStream(file).use { i -> i.copyTo(o, 256 * 1024) }
                o.flush()
                try {
                    o.fd.sync()
                } catch (e: Exception) {
                    // best effort
                }
            }
        }
    }

    fun findOrCreateDir(parentId: String, name: String): String {
        children(parentId).firstOrNull { it.isDir && it.name.equals(name, ignoreCase = true) }?.let { return it.id }
        val created = DocumentsContract.createDocument(app.contentResolver, uri(parentId), Document.MIME_TYPE_DIR, name)
            ?: throw IOException("Couldn't create folder \"$name\"")
        val id = DocumentsContract.getDocumentId(created)
        entries[id] = Entry(queryName(id) ?: name, parentId)
        return id
    }

    fun rename(id: String, newName: String): String {
        val parent = parentId(id)
        val result = DocumentsContract.renameDocument(app.contentResolver, uri(id), newName)
        val newId = result?.let { DocumentsContract.getDocumentId(it) } ?: id
        if (newId != id) entries.remove(id)
        entries[newId] = Entry(queryName(newId) ?: newName, parent)
        return newId
    }

    fun move(id: String, fromParent: String, toParent: String): String {
        val name = name(id)
        val newId = try {
            val moved = DocumentsContract.moveDocument(app.contentResolver, uri(id), uri(fromParent), uri(toParent))
                ?: throw IOException("Move not supported")
            DocumentsContract.getDocumentId(moved)
        } catch (e: Exception) {
            copyThenDelete(id, name, toParent)
        }
        entries.remove(id)
        entries[newId] = Entry(queryName(newId) ?: name, toParent)
        return newId
    }

    /** For storage that can't move files directly. */
    private fun copyThenDelete(id: String, name: String, toParent: String): String {
        val mime = app.contentResolver.getType(uri(id)) ?: "application/octet-stream"
        val created = DocumentsContract.createDocument(app.contentResolver, uri(toParent), mime, name)
            ?: throw IOException("Couldn't create $name")
        app.contentResolver.openInputStream(uri(id))!!.use { i ->
            app.contentResolver.openOutputStream(created, "w")!!.use { o -> i.copyTo(o, 256 * 1024) }
        }
        DocumentsContract.deleteDocument(app.contentResolver, uri(id))
        return DocumentsContract.getDocumentId(created)
    }

    /** Removes [dirId] and its parents while they're empty, stopping at the chosen folder. */
    fun deleteEmptyDirs(dirId: String?) {
        val root = rootId()
        var d = dirId
        var guard = 0
        while (d != null && d != root && guard++ < 40) {
            val parent = parentId(d)
            if (children(d).isNotEmpty()) return
            try {
                DocumentsContract.deleteDocument(app.contentResolver, uri(d))
                entries.remove(d)
            } catch (e: Exception) {
                return
            }
            d = parent
        }
    }

    /** Real path for local storage, so other music apps can be told to rescan. */
    private fun fsPath(id: String): String? {
        if (tree.authority != EXTERNAL_STORAGE || !id.contains(':')) return null
        val volume = id.substringBefore(':')
        val rel = id.substringAfter(':')
        val base = if (volume == "primary") Environment.getExternalStorageDirectory().path else "/storage/$volume"
        return if (rel.isEmpty()) base else "$base/$rel"
    }

    fun mediaScan(vararg ids: String) {
        val paths = ids.mapNotNull { runCatching { fsPath(it) }.getOrNull() }.toTypedArray()
        if (paths.isNotEmpty()) MediaScannerConnection.scanFile(app, paths, null, null)
    }
}
