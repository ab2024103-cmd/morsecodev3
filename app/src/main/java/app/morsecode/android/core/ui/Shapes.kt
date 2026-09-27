package app.morsecode.android.core.ui

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.graphics.drawable.StateListDrawable
import android.os.Build
import android.util.TypedValue
import androidx.annotation.ColorInt
import androidx.core.content.ContextCompat
import app.morsecode.android.R
import app.morsecode.android.core.util.ThemeColors

/**
 * Shape factory for the Sunflower surfaces (§4.3, §4.4, §4.8).
 *
 * Built in code rather than as drawable XML on purpose: the accent-dependent
 * fills need the live accent and the theme's wash percentages (8/35 dark,
 * 14/45 light), and theme attributes inside drawable XML are unreliable on the
 * API 21–22 end of the support range. No hex literal appears here — every
 * colour arrives from a token (§4.13).
 */
object Shapes {

    fun dp(context: Context, value: Float): Float = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, value, context.resources.displayMetrics,
    )

    fun dpInt(context: Context, value: Float): Int = (dp(context, value) + 0.5f).toInt()

    /** Flat card: fill + 1 dp hairline border, radius 16 dp (§4.3, §4.8). */
    fun card(context: Context, radiusDp: Float = 16f): GradientDrawable = rounded(
        fill = ThemeColors.resolve(context, R.attr.colorSurfaceCard),
        radiusPx = dp(context, radiusDp),
        strokePx = dpInt(context, 1f),
        strokeColor = ThemeColors.resolve(context, R.attr.colorHairline),
    )

    /** Sheets, dialogs, chips and inputs sit on surface/raised (§4.2). */
    fun raised(context: Context, radiusDp: Float): GradientDrawable = rounded(
        fill = ThemeColors.resolve(context, R.attr.colorSurfaceRaised),
        radiusPx = dp(context, radiusDp),
        strokePx = dpInt(context, 1f),
        strokeColor = ThemeColors.resolve(context, R.attr.colorHairline),
    )

    /** §4.4 accent wash: used by every summary card and the selected nav tile. */
    fun accentWash(context: Context, radiusDp: Float): GradientDrawable = rounded(
        fill = ThemeColors.accentWashFill(context),
        radiusPx = dp(context, radiusDp),
        strokePx = dpInt(context, 1f),
        strokeColor = ThemeColors.accentWashBorder(context),
    )

    /** Accent-filled primary button: 14 dp radius, rectangular-rounded, NOT a pill (§4.8). */
    fun accentButton(context: Context): Drawable = pressable(
        context,
        rounded(ThemeColors.accent(context), dp(context, 14f)),
        rounded(ThemeColors.resolve(context, R.attr.colorAccentPressed), dp(context, 14f)),
    )

    fun outlinedButton(context: Context): Drawable = pressable(
        context,
        rounded(
            Color.TRANSPARENT, dp(context, 14f),
            dpInt(context, 1f), ThemeColors.resolve(context, R.attr.colorHairline),
        ),
        rounded(ThemeColors.resolve(context, R.attr.colorSurfacePressed), dp(context, 14f)),
    )

    /** Full-pill: segmented controls only (§4.8). */
    fun pill(context: Context, @ColorInt fill: Int): GradientDrawable =
        rounded(fill, dp(context, 999f))

    fun chip(context: Context, @ColorInt fill: Int, @ColorInt stroke: Int): GradientDrawable =
        rounded(fill, dp(context, 8f), dpInt(context, 1f), stroke)

    fun circle(@ColorInt fill: Int): GradientDrawable {
        val drawable = GradientDrawable()
        drawable.shape = GradientDrawable.OVAL
        drawable.setColor(fill)
        return drawable
    }

    /** Neutral chip: surface/raised + text/secondary (§4.5). */
    fun neutralChip(context: Context): GradientDrawable = chip(
        context,
        ThemeColors.resolve(context, R.attr.colorSurfaceRaised),
        ThemeColors.resolve(context, R.attr.colorHairline),
    )

    fun rounded(
        @ColorInt fill: Int,
        radiusPx: Float,
        strokePx: Int = 0,
        @ColorInt strokeColor: Int = Color.TRANSPARENT,
    ): GradientDrawable {
        val drawable = GradientDrawable()
        drawable.shape = GradientDrawable.RECTANGLE
        drawable.cornerRadius = radiusPx
        drawable.setColor(fill)
        if (strokePx > 0) drawable.setStroke(strokePx, strokeColor)
        return drawable
    }

    /** Pressed feedback that works on every supported API (ripple from 21, states below). */
    fun pressable(context: Context, normal: Drawable, pressed: Drawable): Drawable {
        if (Build.VERSION.SDK_INT >= 21) {
            val ripple = ThemeColors.withAlpha(ThemeColors.resolve(context, R.attr.colorTextPrimary), 0.12f)
            return RippleDrawable(android.content.res.ColorStateList.valueOf(ripple), normal, null)
        }
        val list = StateListDrawable()
        list.addState(intArrayOf(android.R.attr.state_pressed), pressed)
        list.addState(intArrayOf(), normal)
        return list
    }

    /** File-type squircle tile: solid rounded square, 22% radius, white glyph (§4.9). */
    fun typeTile(context: Context, colorRes: Int, sizeDp: Float): GradientDrawable {
        val fill = ContextCompat.getColor(context, colorRes)
        return rounded(fill, dp(context, sizeDp * 0.22f))
    }
}
