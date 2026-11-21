package app.retag.data

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

enum class SortBy(val label: String) {
    AddedNewest("Date added (newest)"),
    AddedOldest("Date added (oldest)"),
    Title("Title"),
    Artist("Artist"),
    Album("Album"),
    FileName("File name"),
}

/** App settings. Backed by SharedPreferences and observable from Compose. */
object Prefs {
    const val DEFAULT_RENAME = "%title%[ - %year%]"
    const val DEFAULT_FOLDERS = "%albumartist%/%album%"

    private lateinit var sp: SharedPreferences

    private var _tree by mutableStateOf<String?>(null)
    private var _sort by mutableStateOf(SortBy.AddedNewest)
    private var _renameOn by mutableStateOf(false)
    private var _renamePattern by mutableStateOf(DEFAULT_RENAME)
    private var _organizeOn by mutableStateOf(false)
    private var _folderPattern by mutableStateOf(DEFAULT_FOLDERS)

    fun init(ctx: Context) {
        sp = ctx.getSharedPreferences("retag", Context.MODE_PRIVATE)
        _tree = sp.getString("tree", null)
        _sort = runCatching { SortBy.valueOf(sp.getString("sort", null) ?: "") }.getOrDefault(SortBy.AddedNewest)
        _renameOn = sp.getBoolean("renameOn", false)
        _renamePattern = sp.getString("renamePattern", null) ?: DEFAULT_RENAME
        _organizeOn = sp.getBoolean("organizeOn", false)
        _folderPattern = sp.getString("folderPattern", null) ?: DEFAULT_FOLDERS
    }

    var treeUri: Uri?
        get() = _tree?.let(Uri::parse)
        set(v) {
            _tree = v?.toString()
            sp.edit().putString("tree", _tree).apply()
        }

    var sort: SortBy
        get() = _sort
        set(v) {
            _sort = v
            sp.edit().putString("sort", v.name).apply()
        }

    var renameOn: Boolean
        get() = _renameOn
        set(v) {
            _renameOn = v
            sp.edit().putBoolean("renameOn", v).apply()
        }

    var renamePattern: String
        get() = _renamePattern
        set(v) {
            _renamePattern = v
            sp.edit().putString("renamePattern", v).apply()
        }

    var organizeOn: Boolean
        get() = _organizeOn
        set(v) {
            _organizeOn = v
            sp.edit().putBoolean("organizeOn", v).apply()
        }

    var folderPattern: String
        get() = _folderPattern
        set(v) {
            _folderPattern = v
            sp.edit().putString("folderPattern", v).apply()
        }
}
