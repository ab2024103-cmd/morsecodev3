package app.morsecode.android

import android.app.Application
import androidx.appcompat.app.AppCompatDelegate
import app.morsecode.android.core.util.Ids
import app.morsecode.android.di.AppServices

/**
 * Process init, crash handler, theme bootstrap (§8.1).
 *
 * Nothing here touches the network: there is no update ping, no analytics and
 * no crash upload (§3.4, §16.6).
 */
class MorsecodeApp : Application() {

    override fun onCreate() {
        super.onCreate()
        AppServices.init(this)

        installCrashHandler()

        // Theme bootstrap. §4.1: first run resolves to the system setting; if
        // the system has no preference, Dark. The stored Light/Dark/Follow
        // system choice is read here once Prefs exists (Stage 2).
        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)

        AppServices.logStore.i("${Ids.logHeader(BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE)} started")
    }

    /**
     * Uncaught-exception handler (§16.6). The report is written locally and
     * listed in the log viewer; it is never uploaded. The previous handler is
     * always chained so the platform still reports the crash normally.
     */
    private fun installCrashHandler() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            try {
                AppServices.logStore.recordCrash(thread, error)
            } catch (ignored: Throwable) {
                // A crash inside crash capture must never replace the real crash.
            }
            previous?.uncaughtException(thread, error)
        }
    }
}
