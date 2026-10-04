package ai.relign.app

import android.app.Application
import ai.relign.app.data.PreferencesManager

class RelignApplication : Application() {
    lateinit var preferencesManager: PreferencesManager
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this
        preferencesManager = PreferencesManager(this)
    }

    companion object {
        lateinit var instance: RelignApplication
            private set
    }
}
