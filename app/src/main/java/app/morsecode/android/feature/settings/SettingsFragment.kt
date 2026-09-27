package app.morsecode.android.feature.settings

import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import androidx.appcompat.widget.AppCompatTextView
import androidx.core.content.ContextCompat
import app.morsecode.android.BuildConfig
import app.morsecode.android.R
import app.morsecode.android.core.data.Prefs
import app.morsecode.android.core.ui.AvatarView
import app.morsecode.android.core.ui.BottomNavView
import app.morsecode.android.core.ui.Screen
import app.morsecode.android.core.ui.SectionHeaderView
import app.morsecode.android.core.ui.SettingRowView
import app.morsecode.android.core.ui.Shapes
import app.morsecode.android.core.ui.Themes
import app.morsecode.android.core.ui.Ui
import app.morsecode.android.core.util.Accent
import app.morsecode.android.core.util.ThemeColors
import app.morsecode.android.di.AppServices
import app.morsecode.android.feature.help.HelpFragment
import app.morsecode.android.feature.onboarding.OnboardingFragment

/**
 * §6.13 SETTINGS — profile card, ACCENT COLOUR, APPEARANCE, TRANSFER, SYSTEM,
 * DIAGNOSTICS, ABOUT.
 *
 * §6.13: "every switch here must actually be wired to behaviour; a
 * stored-but-unread preference is a defect". Stage 3 therefore ships only the
 * controls whose behaviour exists — the accent picker, Dark mode and Follow
 * system, which the Stage 2 theme system already honours, plus the rows that
 * navigate. The switches whose behaviour arrives later (Sounds, Notifications,
 * Crash reports, Conflict policy, Broadcast peers, Storage access, Battery)
 * are shown as rows that route to their stage rather than as toggles that
 * would store a value nobody reads.
 */
class SettingsFragment : Screen() {

    override val navTab: BottomNavView.Tab = BottomNavView.Tab.SETTINGS

    private lateinit var darkModeRow: SettingRowView

    override fun onBuildScreen(column: LinearLayout) {
        val context = requireContext()
        val prefs = AppServices.prefs

        toolbar.bind(getString(R.string.title_settings))

        column.addView(profileCard(), params(8))

        column.addView(header(getString(R.string.settings_section_accent)), params(8))
        column.addView(accentPicker(), params(0))

        column.addView(header(getString(R.string.settings_section_appearance)), params(8))

        darkModeRow = SettingRowView(context)
        darkModeRow.bindToggle(
            label = getString(R.string.settings_dark_mode),
            subtitle = getString(R.string.settings_dark_mode_sub),
            checked = prefs.themeMode.value == Prefs.ThemeMode.DARK,
            enabled = prefs.themeMode.value != Prefs.ThemeMode.SYSTEM,
        ) { isDark ->
            prefs.setThemeMode(if (isDark) Prefs.ThemeMode.DARK else Prefs.ThemeMode.LIGHT)
            Themes.applyNightMode(prefs)
        }
        column.addView(darkModeRow, params(0))

        // §6.13 [GAP G3]: Follow system disables Dark mode and mirrors the
        // system state, so the two switches can never disagree.
        val followSystem = SettingRowView(context)
        followSystem.bindToggle(
            label = getString(R.string.settings_follow_system),
            checked = prefs.themeMode.value == Prefs.ThemeMode.SYSTEM,
        ) { follows ->
            if (follows) {
                prefs.setThemeMode(Prefs.ThemeMode.SYSTEM)
            } else {
                prefs.setThemeMode(if (isSystemDark()) Prefs.ThemeMode.DARK else Prefs.ThemeMode.LIGHT)
            }
            Themes.applyNightMode(prefs)
            darkModeRow.setRowEnabled(!follows)
            darkModeRow.setToggleChecked(prefs.themeMode.value == Prefs.ThemeMode.DARK || (follows && isSystemDark()))
        }
        column.addView(followSystem, params(0))

        column.addView(header(getString(R.string.settings_section_transfer)), params(8))
        column.addView(laterRow(R.string.settings_conflict_policy, R.string.settings_conflict_policy_sub), params(0))
        column.addView(laterRow(R.string.settings_notifications, R.string.settings_notifications_sub), params(0))
        column.addView(laterRow(R.string.settings_broadcast_peers, R.string.settings_broadcast_peers_sub), params(0))

        column.addView(header(getString(R.string.settings_section_system)), params(8))
        column.addView(laterRow(R.string.settings_storage_access, R.string.settings_storage_access_sub), params(0))
        column.addView(laterRow(R.string.settings_battery, R.string.settings_battery_sub), params(0))

        val logs = SettingRowView(context)
        logs.bindAction(getString(R.string.settings_logs), getString(R.string.settings_logs_sub)) {
            nav().push(LogViewerFragment())
        }
        column.addView(logs, params(0))
        column.addView(laterRow(R.string.settings_crash_reports, R.string.settings_crash_reports_sub), params(0))

        column.addView(header(getString(R.string.settings_section_diagnostics)), params(8))
        val doctor = SettingRowView(context)
        doctor.bindAction(
            getString(R.string.title_connection_doctor),
            getString(R.string.settings_doctor_sub),
        ) { nav().push(ConnectionDoctorFragment()) }
        column.addView(doctor, params(0))

        column.addView(header(getString(R.string.settings_section_about)), params(8))
        val replay = SettingRowView(context)
        replay.bindAction(
            getString(R.string.settings_replay_onboarding),
            getString(R.string.settings_replay_onboarding_sub),
        ) { nav().push(OnboardingFragment()) }
        column.addView(replay, params(0))

        val help = SettingRowView(context)
        help.bindAction(getString(R.string.settings_help)) { nav().push(HelpFragment()) }
        column.addView(help, params(0))

        val about = SettingRowView(context)
        about.bindAction(
            getString(R.string.settings_about),
            getString(
                R.string.settings_about_sub,
                BuildConfig.VERSION_NAME,
                BuildConfig.VERSION_CODE,
            ),
        ) { Ui.snackbar(requireActivity(), getString(R.string.stub_screen)) }
        column.addView(about, params(0))
    }

    private fun isSystemDark(): Boolean {
        val mask = resources.configuration.uiMode and
            android.content.res.Configuration.UI_MODE_NIGHT_MASK
        return mask == android.content.res.Configuration.UI_MODE_NIGHT_YES
    }

    /** §6.13 profile card: accent letter avatar, device name, rename hint. */
    private fun profileCard(): LinearLayout {
        val context = requireContext()
        val card = LinearLayout(context)
        card.orientation = LinearLayout.HORIZONTAL
        card.gravity = Gravity.CENTER_VERTICAL
        card.background = Shapes.card(context)
        val pad = Shapes.dpInt(context, 14f)
        card.setPadding(pad, pad, pad, pad)

        val deviceName = android.os.Build.MODEL ?: getString(R.string.app_name)
        val avatar = AvatarView(context)
        avatar.bind(deviceName, deviceName)
        val size = Shapes.dpInt(context, 48f)
        card.addView(avatar, LinearLayout.LayoutParams(size, size))

        val text = LinearLayout(context)
        text.orientation = LinearLayout.VERTICAL
        val name = AppCompatTextView(context)
        name.setTextAppearance(context, R.style.TextAppearance_Morsecode_ItemTitle)
        name.text = deviceName
        text.addView(name)
        val subtitle = AppCompatTextView(context)
        subtitle.setTextAppearance(context, R.style.TextAppearance_Morsecode_ItemMeta)
        subtitle.text = getString(R.string.settings_profile_subtitle)
        text.addView(subtitle)
        val textParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        textParams.leftMargin = Shapes.dpInt(context, 12f)
        card.addView(text, textParams)
        return card
    }

    /** §6.13 ACCENT COLOUR: five 40 dp circles, the selected one ringed. */
    private fun accentPicker(): LinearLayout {
        val context = requireContext()
        val row = LinearLayout(context)
        row.orientation = LinearLayout.HORIZONTAL
        val selected = AppServices.prefs.accent.value

        for (accent in Accent.values()) {
            val swatch = View(context)
            val fill = ContextCompat.getColor(context, accent.colorRes)
            val circle = Shapes.circle(fill)
            if (accent == selected) {
                circle.setStroke(
                    Shapes.dpInt(context, 2f),
                    ThemeColors.resolve(context, R.attr.colorTextPrimary),
                )
            }
            swatch.background = circle
            swatch.contentDescription = accent.key
            swatch.isClickable = true
            swatch.isFocusable = true
            swatch.setOnClickListener {
                AppServices.prefs.setAccent(accent)
                // The accent is a whole theme, so the screen is recreated and
                // every view repaints from the token file (§4.4, §4.13).
                requireActivity().recreate()
            }
            val size = Shapes.dpInt(context, 40f)
            val params = LinearLayout.LayoutParams(size, size)
            params.rightMargin = Shapes.dpInt(context, 12f)
            params.topMargin = Shapes.dpInt(context, 4f)
            params.bottomMargin = params.topMargin
            row.addView(swatch, params)
        }
        // 40 dp circles in a 48 dp row keep the touch target legal (§15.2).
        row.minimumHeight = Shapes.dpInt(context, 48f)
        row.gravity = Gravity.CENTER_VERTICAL
        return row
    }

    private fun header(title: CharSequence): SectionHeaderView {
        val view = SectionHeaderView(requireContext())
        view.bind(title)
        return view
    }

    /** A real row for a setting whose behaviour lands in a later stage (§20.6). */
    private fun laterRow(labelRes: Int, subtitleRes: Int): SettingRowView {
        val row = SettingRowView(requireContext())
        row.bindAction(getString(labelRes), getString(subtitleRes)) {
            Ui.snackbar(requireActivity(), getString(R.string.stub_screen))
        }
        return row
    }

    private fun params(topMarginDp: Int): LinearLayout.LayoutParams {
        val params = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        )
        params.topMargin = Shapes.dpInt(requireContext(), topMarginDp.toFloat())
        return params
    }
}
