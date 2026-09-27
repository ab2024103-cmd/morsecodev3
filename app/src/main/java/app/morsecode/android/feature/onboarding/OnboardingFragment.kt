package app.morsecode.android.feature.onboarding

import android.content.res.ColorStateList
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.widget.AppCompatImageView
import androidx.appcompat.widget.AppCompatTextView
import androidx.core.content.ContextCompat
import androidx.core.widget.ImageViewCompat
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import app.morsecode.android.R
import app.morsecode.android.core.ui.Buttons
import app.morsecode.android.core.ui.Screen
import app.morsecode.android.core.ui.Shapes
import app.morsecode.android.core.util.Permissions
import app.morsecode.android.core.util.ThemeColors
import app.morsecode.android.di.AppServices

/**
 * §6.1 ONBOARDING — four full-bleed slides, swipeable, with a dot indicator
 * whose active dot is an accent pill, a primary button and a text secondary
 * action. Replayable any time from Settings → About (§6.13).
 *
 * §6.1's two rules about permissions are structural here: slide 2 requests
 * ONLY what is needed to discover and save, and it never asks for
 * MANAGE_EXTERNAL_STORAGE or battery exemption — neither is in
 * [Permissions.nearby] or [Permissions.mediaRead], and §12.3 keeps all-files
 * access to Settings alone. Skipping is always allowed.
 */
class OnboardingFragment : Screen() {

    override val scrollable = false

    private data class Slide(
        val iconRes: Int,
        val iconColorRes: Int,
        val titleRes: Int,
        val bodyRes: Int,
        val primaryRes: Int,
        val secondaryRes: Int,
    )

    private val slides = listOf(
        Slide(
            R.drawable.ic_radar, R.color.onboarding_radar_start,
            R.string.onboarding_1_title, R.string.onboarding_1_body,
            R.string.onboarding_continue, R.string.onboarding_skip,
        ),
        Slide(
            R.drawable.ic_lock, R.color.onboarding_lock,
            R.string.onboarding_2_title, R.string.onboarding_2_body,
            R.string.onboarding_grant, R.string.onboarding_not_now,
        ),
        Slide(
            R.drawable.ic_globe, R.color.onboarding_globe,
            R.string.onboarding_3_title, R.string.onboarding_3_body,
            R.string.onboarding_continue, R.string.onboarding_skip,
        ),
        Slide(
            R.drawable.ic_check, R.color.onboarding_check,
            R.string.onboarding_4_title, R.string.onboarding_4_body,
            R.string.onboarding_open_app, R.string.onboarding_replay,
        ),
    )

    private lateinit var pager: ViewPager2
    private lateinit var dots: LinearLayout

    /**
     * §6.1 slide 2. A skipped permission is not a dead end: it is requested
     * again, contextually, the first time it is actually needed (§6.3).
     */
    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { granted ->
        val allowed = granted.count { it.value }
        AppServices.logStore.i("Onboarding permissions: $allowed of ${granted.size} granted")
        advance()
    }

    override fun onBuildScreen(column: LinearLayout) {
        val context = requireContext()
        toolbar.visibility = View.GONE

        pager = ViewPager2(context)
        pager.adapter = SlideAdapter()
        pager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) = paintDots(position)
        })
        column.addView(
            pager,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f),
        )

        dots = LinearLayout(context)
        dots.orientation = LinearLayout.HORIZONTAL
        dots.gravity = Gravity.CENTER
        repeat(slides.size) { dots.addView(View(context)) }
        val dotParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        )
        dotParams.topMargin = Shapes.dpInt(context, 12f)
        dotParams.bottomMargin = Shapes.dpInt(context, 12f)
        column.addView(dots, dotParams)
        paintDots(0)
    }

    private fun paintDots(active: Int) {
        val context = requireContext()
        val accent = ThemeColors.accent(context)
        val idle = ThemeColors.resolve(context, R.attr.colorHairline)
        val height = Shapes.dpInt(context, 6f)
        for (index in 0 until dots.childCount) {
            val dot = dots.getChildAt(index)
            val isActive = index == active
            // The active dot is an accent PILL, the rest are circles (§6.1).
            val width = if (isActive) Shapes.dpInt(context, 20f) else height
            val params = LinearLayout.LayoutParams(width, height)
            params.marginEnd = Shapes.dpInt(context, 6f)
            dot.layoutParams = params
            dot.background = Shapes.pill(context, if (isActive) accent else idle)
        }
        dots.contentDescription = getString(R.string.cd_onboarding_progress, active + 1, slides.size)
    }

    private fun advance() {
        if (pager.currentItem < slides.size - 1) {
            pager.currentItem = pager.currentItem + 1
        } else {
            finishTour()
        }
    }

    private fun finishTour() {
        AppServices.prefs.onboardingSeen = true
        nav().pop()
    }

    private inner class SlideAdapter : RecyclerView.Adapter<SlideHolder>() {

        override fun getItemCount(): Int = slides.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): SlideHolder {
            val context = parent.context
            val root = LinearLayout(context)
            root.orientation = LinearLayout.VERTICAL
            root.gravity = Gravity.CENTER
            val pad = Shapes.dpInt(context, 24f)
            root.setPadding(pad, pad, pad, pad)
            root.layoutParams = RecyclerView.LayoutParams(
                RecyclerView.LayoutParams.MATCH_PARENT,
                RecyclerView.LayoutParams.MATCH_PARENT,
            )
            return SlideHolder(root)
        }

        override fun onBindViewHolder(holder: SlideHolder, position: Int) =
            holder.bind(slides[position], position)
    }

    private inner class SlideHolder(private val root: LinearLayout) : RecyclerView.ViewHolder(root) {

        fun bind(slide: Slide, position: Int) {
            val context = root.context
            root.removeAllViews()

            // The squircle: a filled 22%-radius tile, as §4.9 draws type tiles.
            val icon = AppCompatImageView(context)
            icon.setImageResource(slide.iconRes)
            ImageViewCompat.setImageTintList(
                icon,
                ColorStateList.valueOf(ThemeColors.resolve(context, R.attr.colorInkOnAccent)),
            )
            val tile = Shapes.rounded(
                ContextCompat.getColor(context, slide.iconColorRes),
                Shapes.dp(context, 72f * 0.22f),
            )
            icon.background = tile
            val iconPad = Shapes.dpInt(context, 18f)
            icon.setPadding(iconPad, iconPad, iconPad, iconPad)
            icon.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            val size = Shapes.dpInt(context, 72f)
            root.addView(icon, LinearLayout.LayoutParams(size, size))

            val title = AppCompatTextView(context)
            title.setTextAppearance(context, R.style.TextAppearance_Morsecode_ScreenTitle)
            title.setText(slide.titleRes)
            title.gravity = Gravity.CENTER
            root.addView(title, spaced(context, 20))

            val body = AppCompatTextView(context)
            body.setTextAppearance(context, R.style.TextAppearance_Morsecode_Body)
            body.setText(slide.bodyRes)
            body.gravity = Gravity.CENTER
            root.addView(body, spaced(context, 12))

            val primary = Buttons.accent(context, getString(slide.primaryRes)) {
                onPrimary(position)
            }
            root.addView(primary, spaced(context, 28))

            val secondary = AppCompatTextView(context)
            secondary.setTextAppearance(context, R.style.TextAppearance_Morsecode_Button)
            secondary.setTextColor(ThemeColors.resolve(context, R.attr.colorTextSecondary))
            secondary.setText(slide.secondaryRes)
            secondary.gravity = Gravity.CENTER
            secondary.minHeight = Shapes.dpInt(context, 48f)
            secondary.isClickable = true
            secondary.setOnClickListener { onSecondary(position) }
            root.addView(secondary, spaced(context, 8))
        }

        private fun spaced(context: android.content.Context, topDp: Int): LinearLayout.LayoutParams {
            val params = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            )
            params.topMargin = Shapes.dpInt(context, topDp.toFloat())
            return params
        }
    }

    private fun onPrimary(position: Int) {
        when (position) {
            1 -> {
                // Only what is needed to discover and save (§6.1, §3.5).
                val wanted = (Permissions.nearby() + Permissions.mediaRead() + Permissions.notifications())
                    .distinct()
                    .toTypedArray()
                val missing = Permissions.missing(requireContext(), wanted)
                if (missing.isEmpty()) advance() else permissionLauncher.launch(missing.toTypedArray())
            }
            slides.size - 1 -> finishTour()
            else -> advance()
        }
    }

    private fun onSecondary(position: Int) {
        // Skipping is always allowed (§6.1); the last slide's secondary
        // replays the tour from the beginning.
        if (position == slides.size - 1) pager.currentItem = 0 else advance()
    }
}
