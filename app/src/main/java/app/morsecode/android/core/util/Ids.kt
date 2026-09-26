package app.morsecode.android.core.util

/**
 * NAMING IS SINGLE-SOURCE (spec §1.1.1).
 *
 * Every prescribed identifier in the product lives here exactly once, so no
 * string literal of an identity value is ever typed a second time. The values
 * below are reproduced verbatim from §1.1.1 and are covered by `IdsTest`.
 *
 * This is a greenfield product: there is no predecessor, no installed base and
 * therefore NO compatibility shim anywhere. If a branch that reads a legacy
 * format ever appears, delete it — the case cannot occur.
 */
object Ids {

    /** Root package / applicationId. */
    const val APPLICATION_ID = "app.morsecode.android"

    /** Launcher label and every user-visible product name. One word, capital M only. */
    const val APP_NAME = "Morsecode"

    const val VERSION_NAME = "1.0.0"
    const val VERSION_CODE = 1

    /** Wire chunk magic, 4 bytes (§11.2 framing: magic | seq | len | payload | crc32). */
    const val CHUNK_MAGIC = "MRSC"

    /** Nearby Connections service id (§11.3). */
    const val NEARBY_SERVICE_ID = "app.morsecode.android.nearby"

    /** Deep-link scheme — typed or pasted only; there is no QR anywhere (§11.5). */
    const val LINK_SCHEME = "morsecode"

    /** Partial-download suffix appended while a receive is in flight (§9.4). */
    const val PART_SUFFIX = ".morsecode.part"

    /** Default media folder, relative to shared storage (§12.4). */
    const val DEFAULT_MEDIA_FOLDER = "Download/Morsecode"

    /** Notification channel ids (§1.1.1). The two ids below are prescribed verbatim. */
    const val CHANNEL_TRANSFER = "morsecode.transfer"
    const val CHANNEL_WEBSHARE = "morsecode.webshare"

    /**
     * §6.18 names four channels; §1.1.1 prescribes ids for two of them. These
     * two follow the same prescribed shape and are NOT overriding anything.
     */
    const val CHANNEL_REQUESTS = "morsecode.requests"
    const val CHANNEL_PLAYBACK = "morsecode.playback"

    /** WebShare HTTP server (§1.1.1, §7.1). */
    const val PORT_WEBSHARE = 33455

    /** TCP control + data (§1.1.1, §11.2). */
    const val PORT_TCP = 33456

    /** UDP discovery beacons (§1.1.1, §11.2). */
    const val PORT_UDP_DISCOVERY = 33457

    /** HELLO protocol version. Forward compatibility only (§1.1.1, §11.6). */
    const val PROTOCOL_VERSION = 1

    /** Exported-log header: "Morsecode <version> (<versionCode>)" (§1.1.1, §16.3). */
    fun logHeader(
        versionName: String = VERSION_NAME,
        versionCode: Int = VERSION_CODE,
    ): String = "$APP_NAME $versionName ($versionCode)"
}
