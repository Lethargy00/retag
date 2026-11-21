package app.retag

import android.app.Application
import app.retag.data.Prefs
import app.retag.data.Store

class RetagApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Prefs.init(this)
        Store.init(this)
    }
}
