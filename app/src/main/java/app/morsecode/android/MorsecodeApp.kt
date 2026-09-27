package app.morsecode.android

import android.app.Application
import app.morsecode.android.core.ui.Themes
import app.morsecode.android.core.util.Ids
import app.morsecode.android.core.util.Notifications
import app.morsecode.android.di.AppServices
import kotlinx.coroutines.launch

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

        // Theme bootstrap (§4.1). Prefs resolves first run to the system
        // setting, or Dark when the system has no preference.
        Themes.applyNightMode(AppServices.prefs)

        // §6.18 channels exist from process start.
        Notifications.createChannels(this)

        // §17.2: the consent gates are wired before any transport can listen,
        // so there is no window in which a peer could be accepted silently.
        AppServices.connections.install()

        observeForReliability()

        AppServices.logStore.i("${Ids.logHeader(BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE)} started")
    }

    /**
     * §14.5 and §7.8's last wire.
     *
     * The power locks follow the queue — held from the first active item,
     * released when nothing is moving — and every accepted browser session is
     * published into the one discovery list, so a laptop appears beside phones
     * (§11.1, G11). Both are process-scoped collectors: a screen cannot be
     * responsible for either.
     */
    private fun observeForReliability() {
        val scope = AppServices.engineScope
        scope.launch {
            AppServices.transferEngine.items.collect { items ->
                AppServices.powerPolicy.apply(items)
            }
        }
        scope.launch {
            AppServices.webShare.sessions.active.collect { sessions ->
                AppServices.webPeerTransport.publish(sessions)
            }
        }
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
