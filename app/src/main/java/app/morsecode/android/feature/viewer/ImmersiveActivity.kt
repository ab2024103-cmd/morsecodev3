package app.morsecode.android.feature.viewer

import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.AppCompatImageView
import androidx.appcompat.widget.AppCompatTextView
import app.morsecode.android.R
import app.morsecode.android.core.ui.Shapes
import app.morsecode.android.core.ui.Themes
import app.morsecode.android.core.util.ThemeColors
import app.morsecode.android.di.AppServices

/**
 * Shared base for the two immersive surfaces (§5.2 [CHANGED], §8.1): the photo
 * viewer and the video player are full-bleed ACTIVITIES whose own action row
 * owns the bottom edge, so they hide the bottom navigation and restore it on
 * back. Being separate activities is what makes "hide it" structural rather
 * than a flag a screen could forget to set.
 *
 * §6.10: the surface is black in dark and #141310 in light — both read from
 * the token file, never typed here (§4.13).
 */
abstract class ImmersiveActivity : AppCompatActivity() {

    protected lateinit var actionRow: LinearLayout
        private set

    protected lateinit var stage: FrameLayout
        private set

    override fun onCreate(savedInstanceState: Bundle?) {
        Themes.applyAccent(this, AppServices.prefs)
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setBackgroundColor(ThemeColors.resolve(this, R.attr.colorViewerBackdrop))

        root.addView(topBar(), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        // The stage takes every pixel between the bar and the action row
        // (§6.10: a small card floating in black is wrong).
        stage = FrameLayout(this)
        root.addView(stage, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        actionRow = LinearLayout(this)
        actionRow.orientation = LinearLayout.HORIZONTAL
        actionRow.gravity = Gravity.CENTER
        val pad = Shapes.dpInt(this, 12f)
        actionRow.setPadding(pad, pad, pad, pad)
        root.addView(
            actionRow,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT),
        )

        setContentView(root)
        onBuildImmersive()
    }

    protected abstract fun onBuildImmersive()

    /** Top bar: back circle, file name, mono meta line, overflow circle (§6.10). */
    private fun topBar(): LinearLayout {
        val bar = LinearLayout(this)
        bar.orientation = LinearLayout.HORIZONTAL
        bar.gravity = Gravity.CENTER_VERTICAL
        val pad = Shapes.dpInt(this, 8f)
        bar.setPadding(pad, pad, pad, pad)

        val back = AppCompatImageView(this)
        back.setImageResource(R.drawable.ic_back)
        back.contentDescription = getString(R.string.cd_back)
        androidx.core.widget.ImageViewCompat.setImageTintList(
            back,
            android.content.res.ColorStateList.valueOf(
                ThemeColors.resolve(this, R.attr.colorViewerChrome),
            ),
        )
        val iconPad = Shapes.dpInt(this, 12f)
        back.setPadding(iconPad, iconPad, iconPad, iconPad)
        back.background = Shapes.circle(ThemeColors.resolve(this, R.attr.colorViewerChromeScrim))
        back.setOnClickListener { onBackPressedDispatcher.onBackPressed() }
        val touch = Shapes.dpInt(this, 48f)
        bar.addView(back, LinearLayout.LayoutParams(touch, touch))

        val title = AppCompatTextView(this)
        title.setTextAppearance(this, R.style.TextAppearance_Morsecode_ItemTitle)
        title.setTextColor(ThemeColors.resolve(this, R.attr.colorViewerChrome))
        title.text = titleText()
        val titleParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        titleParams.leftMargin = Shapes.dpInt(this, 12f)
        bar.addView(title, titleParams)

        val overflow = AppCompatImageView(this)
        overflow.setImageResource(R.drawable.ic_overflow)
        overflow.contentDescription = getString(R.string.cd_overflow)
        androidx.core.widget.ImageViewCompat.setImageTintList(
            overflow,
            android.content.res.ColorStateList.valueOf(
                ThemeColors.resolve(this, R.attr.colorViewerChrome),
            ),
        )
        overflow.background = Shapes.circle(ThemeColors.resolve(this, R.attr.colorViewerChromeScrim))
        overflow.setPadding(iconPad, iconPad, iconPad, iconPad)
        overflow.visibility = View.VISIBLE
        bar.addView(overflow, LinearLayout.LayoutParams(touch, touch))
        return bar
    }

    protected abstract fun titleText(): CharSequence
}
