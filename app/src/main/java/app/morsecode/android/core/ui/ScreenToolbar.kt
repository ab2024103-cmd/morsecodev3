package app.morsecode.android.core.ui

import android.content.Context
import android.content.res.ColorStateList
import android.util.AttributeSet
import android.view.Gravity
import android.widget.LinearLayout
import androidx.appcompat.widget.AppCompatImageView
import androidx.appcompat.widget.AppCompatTextView
import androidx.core.widget.ImageViewCompat
import app.morsecode.android.R
import app.morsecode.android.core.util.ThemeColors

/**
 * The one toolbar in the product (§4.12, §6.x): optional back circle, title,
 * and up to three trailing icon actions ("Files" + search · SORT · view toggle,
 * "History" + search · filter · clear-all, "Connect" + ?).
 *
 * Built once and reused rather than re-drawn per screen, so the title style,
 * the 48 dp targets (§15.2) and the content descriptions (§15.1) cannot drift
 * between screens.
 */
class ScreenToolbar @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : LinearLayout(context, attrs, defStyleAttr) {

    private val backButton = AppCompatImageView(context)
    private val titleView = AppCompatTextView(context)

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        val padV = Shapes.dpInt(context, 8f)
        setPadding(0, padV, 0, padV)

        backButton.setImageResource(R.drawable.ic_back)
        backButton.contentDescription = context.getString(R.string.cd_back)
        tint(backButton)
        val touch = Shapes.dpInt(context, 48f)
        val iconPad = Shapes.dpInt(context, 12f)
        backButton.setPadding(iconPad, iconPad, iconPad, iconPad)
        backButton.visibility = GONE
        addView(backButton, LayoutParams(touch, touch))

        titleView.setTextAppearance(context, R.style.TextAppearance_Morsecode_ScreenTitle)
        addView(titleView, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
    }

    /** @param onBack when null the back circle is hidden; a root tab has no up-target (§5.3). */
    fun bind(title: CharSequence, onBack: (() -> Unit)? = null) {
        titleView.text = title
        if (onBack == null) {
            backButton.visibility = GONE
            backButton.setOnClickListener(null)
        } else {
            backButton.visibility = VISIBLE
            backButton.background = Shapes.pressable(
                context,
                Shapes.circle(ThemeColors.resolve(context, R.attr.colorSurfaceCard)),
                Shapes.circle(ThemeColors.resolve(context, R.attr.colorSurfacePressed)),
            )
            backButton.setOnClickListener { onBack() }
        }
    }

    /** Trailing icon action. Icon-only is allowed here only with a description (§15.1). */
    fun addAction(iconRes: Int, descriptionRes: Int, onClick: () -> Unit) {
        val action = AppCompatImageView(context)
        action.setImageResource(iconRes)
        action.contentDescription = context.getString(descriptionRes)
        tint(action)
        val pad = Shapes.dpInt(context, 12f)
        action.setPadding(pad, pad, pad, pad)
        action.background = Shapes.pressable(
            context,
            Shapes.circle(android.graphics.Color.TRANSPARENT),
            Shapes.circle(ThemeColors.resolve(context, R.attr.colorSurfacePressed)),
        )
        action.setOnClickListener { onClick() }
        val touch = Shapes.dpInt(context, 48f)
        addView(action, LayoutParams(touch, touch))
    }

    private fun tint(view: AppCompatImageView) {
        ImageViewCompat.setImageTintList(
            view,
            ColorStateList.valueOf(ThemeColors.resolve(context, R.attr.colorTextPrimary)),
        )
    }
}
