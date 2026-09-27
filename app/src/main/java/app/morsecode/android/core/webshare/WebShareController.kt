package app.morsecode.android.core.webshare

import android.content.Context
import android.net.wifi.WifiManager
import app.morsecode.android.core.logging.LogStore
import app.morsecode.android.core.media.MediaLibrary
import app.morsecode.android.core.storage.Destinations
import app.morsecode.android.core.util.Ids
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.net.Inet4Address
import java.net.NetworkInterface

/**
 * §7.1's lifecycle owner.
 *
 * INV-4 [KEPT], in one sentence and one place: **the server stays up until the
 * user stops it.** There is no idle timer here, nothing watches the screen
 * state, and nothing counts connected sessions in order to decide to stop —
 * because any of those would be the auto-teardown the invariant forbids.
 */
class WebShareController(
    context: Context,
    private val library: MediaLibrary,
    private val destinations: Destinations,
    private val logStore: LogStore? = null,
) {

    private val appContext = context.applicationContext

    val sessions = WebSessions()

    private val runningFlow = MutableStateFlow(false)
    val isRunning: StateFlow<Boolean> get() = runningFlow

    private val addressFlow = MutableStateFlow<String?>(null)

    /** "http://192.168.1.24:33455" — a bare address, no token (§7.1). */
    val address: StateFlow<String?> get() = addressFlow

    /** Asked when a browser first appears; wired to §6.16b's dialog. */
    var onConsentNeeded: suspend (WebSessions.Session) -> Boolean = { true }

    private var server: WebShareServer? = null

    /** Started ONLY by explicit user action (§7.1). */
    fun start(): Boolean {
        if (runningFlow.value) return true
        val instance = WebShareServer(
            context = appContext,
            library = library,
            destinations = destinations,
            sessions = sessions,
            logStore = logStore,
            onConsentNeeded = { session -> onConsentNeeded(session) },
        )
        return try {
            instance.start(SOCKET_READ_TIMEOUT, false)
            server = instance
            runningFlow.value = true
            addressFlow.value = "http://${localAddress()}:${Ids.PORT_WEBSHARE}"
            logStore?.i("WebShare started on ${addressFlow.value}")
            true
        } catch (e: Exception) {
            logStore?.e("WebShare could not start: ${e.javaClass.simpleName}")
            false
        }
    }

    /** Stopped ONLY by explicit user action. Tokens die with the server (§7.1). */
    fun stop() {
        server?.stop()
        server = null
        runningFlow.value = false
        addressFlow.value = null
        sessions.clear()
        logStore?.i("WebShare stopped by the user")
    }

    fun revoke(sessionId: String) {
        sessions.revoke(sessionId)
        logStore?.i("WebShare session revoked")
    }

    /** The address the server is actually bound to, never a guess (G10). */
    fun localAddress(): String {
        wifiAddress()?.let { return it }
        return try {
            NetworkInterface.getNetworkInterfaces().asSequence()
                .filter { it.isUp && !it.isLoopback }
                .flatMap { it.inetAddresses.asSequence() }
                .filterIsInstance<Inet4Address>()
                .firstOrNull()
                ?.hostAddress
                ?: FALLBACK
        } catch (e: Exception) {
            FALLBACK
        }
    }

    private fun wifiAddress(): String? = try {
        val wifi = appContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        val ip = wifi?.connectionInfo?.ipAddress ?: 0
        if (ip == 0) {
            null
        } else {
            "%d.%d.%d.%d".format(ip and 0xFF, ip shr 8 and 0xFF, ip shr 16 and 0xFF, ip shr 24 and 0xFF)
        }
    } catch (e: Exception) {
        null
    }

    private companion object {
        const val SOCKET_READ_TIMEOUT = 10_000
        const val FALLBACK = "127.0.0.1"
    }
}
