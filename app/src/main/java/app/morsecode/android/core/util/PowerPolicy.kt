package app.morsecode.android.core.util

import android.content.Context
import android.net.wifi.WifiManager
import android.os.Build
import android.os.PowerManager
import app.morsecode.android.core.logging.LogStore
import app.morsecode.android.core.model.TransferItem
import app.morsecode.android.core.model.TransferState

/**
 * §14 BATTERY, POWER AND OEM RELIABILITY.
 *
 * §14.5: "A partial WakeLock, and on LAN/hotspot paths a WifiLock, are held
 * from the first active item until the batch is fully terminal or paused-idle
 * — never acquired at process start and never leaked."
 *
 * The decision of WHEN to hold is a pure function ([shouldHold]) so the rule
 * can be tested without a radio; the acquiring is the small part.
 */
class PowerPolicy(context: Context, private val logStore: LogStore? = null) {

    private val appContext = context.applicationContext
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    val isHolding: Boolean get() = wakeLock?.isHeld == true

    /**
     * True while anything is actually moving. A queue that is entirely
     * terminal — or paused and idle — releases the locks, which is the half of
     * §14.5 that gets forgotten and drains a battery overnight.
     */
    fun shouldHold(items: List<TransferItem>): Boolean = items.any {
        it.state == TransferState.IN_PROGRESS || it.state == TransferState.QUEUED
    }

    /** Applies [shouldHold] to the real locks. Idempotent in both directions. */
    fun apply(items: List<TransferItem>) {
        if (shouldHold(items)) acquire() else release()
    }

    fun acquire() {
        if (isHolding) return
        try {
            val power = appContext.getSystemService(Context.POWER_SERVICE) as? PowerManager
            wakeLock = power?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_TAG)?.also {
                it.setReferenceCounted(false)
                it.acquire(MAX_HOLD_MS)
            }
            val wifi = appContext.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            val mode = if (Build.VERSION.SDK_INT >= 29) {
                WifiManager.WIFI_MODE_FULL_LOW_LATENCY
            } else {
                @Suppress("DEPRECATION")
                WifiManager.WIFI_MODE_FULL_HIGH_PERF
            }
            wifiLock = wifi?.createWifiLock(mode, WIFI_TAG)?.also {
                it.setReferenceCounted(false)
                it.acquire()
            }
            logStore?.i("Wake and Wi-Fi locks held for the active batch")
        } catch (e: Exception) {
            logStore?.w("Could not hold power locks: ${e.javaClass.simpleName}")
        }
    }

    fun release() {
        runCatching { wakeLock?.takeIf { it.isHeld }?.release() }
        runCatching { wifiLock?.takeIf { it.isHeld }?.release() }
        if (wakeLock != null || wifiLock != null) logStore?.i("Power locks released")
        wakeLock = null
        wifiLock = null
    }

    companion object {

        private const val WAKE_TAG = "morsecode:transfer"
        private const val WIFI_TAG = "morsecode:transfer"

        /** A safety net: the lock is released by policy long before this. */
        private const val MAX_HOLD_MS = 60L * 60 * 1000

        /**
         * §14.4: before a large or long batch, if the battery is low and
         * unplugged — or Battery Saver is on — advise, never block.
         */
        fun lowBatteryAdvisory(
            batteryPercent: Int,
            isCharging: Boolean,
            batterySaverOn: Boolean,
            batchBytes: Long,
        ): String? {
            val large = batchBytes >= LARGE_BATCH_BYTES
            if (!large) return null
            val risky = (batteryPercent <= LOW_BATTERY_PERCENT && !isCharging) || batterySaverOn
            return if (risky) "Battery is low — this transfer may be interrupted." else null
        }

        const val LOW_BATTERY_PERCENT = 20
        const val LARGE_BATCH_BYTES = 200L * 1024 * 1024

        /**
         * §14.2 OEM autostart guidance: "ordered with the current
         * Build.MANUFACTURER first but always listing all of them" — a Xiaomi
         * user must not have to scroll past Samsung, and a Nokia user must
         * still see every entry.
         */
        fun autostartGuidance(manufacturer: String = Build.MANUFACTURER.orEmpty()): List<Pair<String, String>> {
            val all = listOf(
                "Xiaomi" to "Settings → Apps → Manage apps → Morsecode → Autostart, and set Battery saver to No restrictions (MIUI Autostart).",
                "Huawei" to "Settings → Battery → App launch → Morsecode → Manage manually, and enable all three switches (Protected Apps).",
                "Oppo" to "Settings → Battery → App battery usage → Morsecode → Allow background activity and Allow auto launch.",
                "Vivo" to "Settings → Battery → High background power consumption → allow Morsecode, and enable Auto start.",
                "Samsung" to "Settings → Apps → Morsecode → Battery → Unrestricted, and remove it from Sleeping apps.",
            )
            val current = manufacturer.lowercase()
            val mine = all.filter { current.contains(it.first.lowercase()) }
            return mine + all.filterNot { it in mine }
        }
    }
}
