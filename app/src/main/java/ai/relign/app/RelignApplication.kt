package ai.relign.app

import android.app.Application
import ai.relign.app.data.PreferencesManager

class RelignApplication : Application() {
    lateinit var preferencesManager: PreferencesManager
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this

        try {
            preferencesManager = PreferencesManager(this)
        } catch (e: Exception) {
            android.util.Log.e("RelignApplication", "Error initializing PreferencesManager", e)
            preferencesManager = PreferencesManager(applicationContext)
        }

        // Global crash guard to prevent app dying and showing "Relign closed because this app has a bug"
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            android.util.Log.e("RelignCrashHandler", "Uncaught exception on thread ${thread.name}", throwable)
            try {
                defaultHandler?.uncaughtException(thread, throwable)
            } catch (_: Exception) {}
        }
    }

    companion object {
        lateinit var instance: RelignApplication
            private set
    }
}
