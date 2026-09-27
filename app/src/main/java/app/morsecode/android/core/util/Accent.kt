package app.morsecode.android.core.util

import androidx.annotation.ColorRes
import androidx.annotation.StyleRes
import app.morsecode.android.R

/**
 * §4.4 ACCENT PALETTE — exactly five swatches, in this order, the first is the
 * default. One accent is in force at a time across the whole product, WebShare
 * included (§4.13). The accent never recolours the launcher icon or the logo.
 *
 * `key` is what Prefs stores; it is stable and must not be renamed.
 */
enum class Accent(
    val key: String,
    @StyleRes val themeRes: Int,
    @ColorRes val colorRes: Int,
    @ColorRes val pressedRes: Int,
    @ColorRes val deepRes: Int,
) {
    SUNFLOWER(
        "sunflower", R.style.Theme_Morsecode_Sunflower,
        R.color.accent_sunflower, R.color.accent_sunflower_pressed, R.color.accent_sunflower_deep,
    ),
    LEAF(
        "leaf", R.style.Theme_Morsecode_Leaf,
        R.color.accent_leaf, R.color.accent_leaf_pressed, R.color.accent_leaf_pressed,
    ),
    EMBER(
        "ember", R.style.Theme_Morsecode_Ember,
        R.color.accent_ember, R.color.accent_ember_pressed, R.color.accent_ember_pressed,
    ),
    VIOLET(
        "violet", R.style.Theme_Morsecode_Violet,
        R.color.accent_violet, R.color.accent_violet_pressed, R.color.accent_violet_pressed,
    ),
    SKY(
        "sky", R.style.Theme_Morsecode_Sky,
        R.color.accent_sky, R.color.accent_sky_pressed, R.color.accent_sky_pressed,
    );

    companion object {
        /** The default accent is the first swatch, Sunflower (§4.4). */
        val DEFAULT = SUNFLOWER

        fun fromKey(key: String?): Accent {
            if (key == null) return DEFAULT
            for (accent in values()) if (accent.key == key) return accent
            return DEFAULT
        }
    }
}
