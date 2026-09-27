package app.morsecode.android.core.network

import android.content.Context

/**
 * Whether the Nearby transport can run at all.
 *
 * §11.3 and the Stage 7 brief: a device without Play Services degrades to LAN
 * only, with the §6.15 Connection Doctor line — never a crash, and never a UI
 * that offers Nearby and then fails silently (§20.6 honest capability).
 *
 * The status code is read through reflection so the product does not take a
 * hard dependency on play-services-base for one integer, and so a device where
 * the class is missing entirely is simply "unavailable" rather than a
 * `NoClassDefFoundError` at startup.
 */
object PlayServices {

    enum class Availability {
        /** Nearby can advertise and discover. */
        AVAILABLE,

        /** Present but out of date — Nearby works, slowly (§6.15 amber line). */
        OUTDATED,

        /** Not on this device: LAN only. */
        UNAVAILABLE,
    }

    /** ConnectionResult constants, reproduced so no extra dependency is needed. */
    private const val SUCCESS = 0
    private const val SERVICE_VERSION_UPDATE_REQUIRED = 2
    private const val SERVICE_UPDATING = 18

    /** The pure decision, so it can be tested without a device. */
    fun classify(statusCode: Int): Availability = when (statusCode) {
        SUCCESS -> Availability.AVAILABLE
        SERVICE_VERSION_UPDATE_REQUIRED, SERVICE_UPDATING -> Availability.OUTDATED
        else -> Availability.UNAVAILABLE
    }

    fun availability(context: Context): Availability = try {
        val clazz = Class.forName("com.google.android.gms.common.GoogleApiAvailability")
        val instance = clazz.getMethod("getInstance").invoke(null)
        val method = clazz.getMethod("isGooglePlayServicesAvailable", Context::class.java)
        classify(method.invoke(instance, context) as Int)
    } catch (e: Throwable) {
        Availability.UNAVAILABLE
    }

    fun canUseNearby(context: Context): Boolean = availability(context) != Availability.UNAVAILABLE

    /**
     * §6.15's line, status glyph included as text because colour alone is
     * never the signal (§4.13).
     */
    fun doctorLine(availability: Availability): Pair<String, String> = when (availability) {
        Availability.AVAILABLE -> "Play Services ready" to "Nearby Connections available"
        Availability.OUTDATED -> "Play Services old" to "Nearby Connections may be slower"
        Availability.UNAVAILABLE -> "Play Services missing" to "Wi-Fi LAN only on this device"
    }
}
