package app.morsecode.android.core.util

import androidx.annotation.ColorRes
import app.morsecode.android.R

/**
 * §4.6 PEER IDENTITY COLOURS.
 *
 * Every peer gets a letter avatar (first letter of its device name, uppercase)
 * on a deterministic colour chosen by `abs(deviceId.hashCode()) % 6` over this
 * fixed palette — independent of the accent so peers stay distinguishable.
 *
 * NOTE ON THE MOCK AVATARS. §4.6 also requires the deck's four avatars to
 * reproduce exactly (MYA-L10 amber, Ravi's Redmi violet, Pixel 7X sky,
 * Samsung A14 green). The prescribed formula is keyed on the deviceId, not on
 * the display name — applying it to those names directly yields green, pink,
 * pink and sky instead. The formula is the binding part and is reproduced here
 * untouched; the four documented peers are reproduced by giving those fixtures
 * device ids whose hash lands on the prescribed index (see PeerPaletteTest).
 */
object PeerPalette {

    /** The fixed six, in the order §4.6 lists them. */
    @JvmField
    val COLORS: IntArray = intArrayOf(
        R.color.peer_amber,
        R.color.peer_violet,
        R.color.peer_sky,
        R.color.peer_green,
        R.color.peer_red,
        R.color.peer_pink,
    )

    fun indexFor(deviceId: String): Int = Math.abs(deviceId.hashCode()) % COLORS.size

    @ColorRes
    fun colorResFor(deviceId: String): Int = COLORS[indexFor(deviceId)]

    /** First letter of the device name, uppercase; "?" when the name is empty. */
    fun letterFor(deviceName: String): String {
        for (index in deviceName.indices) {
            val ch = deviceName[index]
            if (!ch.isWhitespace()) return ch.uppercaseChar().toString()
        }
        return "?"
    }
}
