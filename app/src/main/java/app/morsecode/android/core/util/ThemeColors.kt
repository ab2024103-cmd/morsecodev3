package app.morsecode.android.core.util

import android.content.Context
import android.graphics.Color
import android.util.TypedValue
import androidx.annotation.AttrRes
import androidx.annotation.ColorInt
import app.morsecode.android.R

/**
 * The only sanctioned way to get a colour in code (§4.13): everything resolves
 * through a theme attribute, so the accent picker and the light theme keep
 * working and no screen ever holds a hex literal.
 */
object ThemeColors {

    @ColorInt
    fun resolve(context: Context, @AttrRes attr: Int): Int {
        val value = TypedValue()
        if (!context.theme.resolveAttribute(attr, value, true)) return Color.TRANSPARENT
        return if (value.resourceId != 0) {
            androidx.core.content.ContextCompat.getColor(context, value.resourceId)
        } else {
            value.data
        }
    }

    fun resolveDimen(context: Context, @AttrRes attr: Int): Float {
        val value = TypedValue()
        if (!context.theme.resolveAttribute(attr, value, true)) return 0f
        return TypedValue.complexToDimension(value.data, context.resources.displayMetrics)
    }

    fun isLight(context: Context): Boolean {
        val value = TypedValue()
        context.theme.resolveAttribute(R.attr.isLightSurface, value, true)
        return value.data != 0
    }

    @ColorInt
    fun accent(context: Context): Int = resolve(context, R.attr.colorAccentMain)

    @ColorInt
    fun withAlpha(@ColorInt color: Int, fraction: Float): Int {
        val alpha = (fraction.coerceIn(0f, 1f) * 255f + 0.5f).toInt()
        return Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color))
    }

    /**
     * §4.4 accent wash, used by every "summary" card:
     * accent @ 8% fill + accent @ 35% 1 dp border in dark,
     * accent @ 14% fill + accent @ 45% border in light.
     */
    @ColorInt
    fun accentWashFill(context: Context): Int =
        withAlpha(accent(context), if (isLight(context)) 0.14f else 0.08f)

    @ColorInt
    fun accentWashBorder(context: Context): Int =
        withAlpha(accent(context), if (isLight(context)) 0.45f else 0.35f)
}
