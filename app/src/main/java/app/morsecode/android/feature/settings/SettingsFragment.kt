package app.morsecode.android.feature.settings

import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import androidx.appcompat.app.AlertDialog
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
import app.morsecode.android.core.util.Permissions
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
    private lateinit var conflictRow: SettingRowView
    private lateinit var peersRow: SettingRowView
    private lateinit var storageRow: SettingRowView
    private lateinit var batteryRow: SettingRowView

    /** §12.1: a granted tree is persisted the moment it is picked. */
    private val treePicker = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocumentTree(),
    ) { uri ->
        if (uri != null) {
            AppServices.safStore.persist(uri)
            storageRow.bindAction(
                getString(R.string.settings_storage_access),
                storageSubtitle(),
            ) { showStorageAccess() }
        }
    }

    override fun onBuildScreen(column: LinearLayout) {
        val context = requireContext()
        val prefs = AppServices.prefs
        // §14.1: the persisted background-kill warning is resolved only once
        // the platform actually reports the exemption as granted.
        if (isBatteryExempt()) prefs.hasBackgroundTransferDeathWarning = false

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

        // §6.13 Sounds — read by SoundFx on every cue (§6.13's own warning).
        val sounds = SettingRowView(context)
        sounds.bindToggle(
            label = getString(R.string.settings_sounds),
            subtitle = getString(R.string.settings_sounds_sub),
            checked = prefs.sounds.value,
        ) { enabled -> prefs.setSounds(enabled) }
        column.addView(sounds, params(0))

        column.addView(header(getString(R.string.settings_section_transfer)), params(8))

        conflictRow = SettingRowView(context)
        conflictRow.bindAction(
            getString(R.string.settings_conflict_policy),
            conflictLabel(prefs.conflictPolicy.value),
        ) { chooseConflictPolicy() }
        column.addView(conflictRow, params(0))

        val notifications = SettingRowView(context)
        notifications.bindToggle(
            label = getString(R.string.settings_notifications),
            subtitle = getString(R.string.settings_notifications_sub),
            checked = prefs.notifications.value,
        ) { enabled -> prefs.setNotifications(enabled) }
        column.addView(notifications, params(0))

        peersRow = SettingRowView(context)
        peersRow.bindAction(
            getString(R.string.settings_broadcast_peers),
            getString(R.string.settings_broadcast_peers_value, prefs.broadcastPeers.value),
        ) { chooseBroadcastPeers() }
        column.addView(peersRow, params(0))

        column.addView(header(getString(R.string.settings_section_system)), params(8))

        storageRow = SettingRowView(context)
        storageRow.bindAction(
            getString(R.string.settings_storage_access),
            storageSubtitle(),
        ) { showStorageAccess() }
        column.addView(storageRow, params(0))

        val autostart = SettingRowView(context)
        autostart.bindAction(
            getString(R.string.oem_autostart_title),
            getString(R.string.oem_autostart_intro),
        ) { showAutostartGuidance() }
        column.addView(autostart, params(0))

        batteryRow = SettingRowView(context)
        batteryRow.bindAction(getString(R.string.settings_battery), batterySubtitle()) {
            requestBatteryExemption()
        }
        column.addView(batteryRow, params(0))

        val logs = SettingRowView(context)
        logs.bindAction(getString(R.string.settings_logs), getString(R.string.settings_logs_sub)) {
            nav().push(LogViewerFragment())
        }
        column.addView(logs, params(0))

        // §16.6 / §6.13: local crash capture, never uploaded — and the switch
        // is what LogStore reads before writing a report.
        val crashReports = SettingRowView(context)
        crashReports.bindToggle(
            label = getString(R.string.settings_crash_reports),
            subtitle = getString(R.string.settings_crash_reports_sub),
            checked = prefs.crashReports.value,
        ) { enabled -> prefs.setCrashReports(enabled) }
        column.addView(crashReports, params(0))

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

    /** §6.13 profile card: persisted display name plus independently chosen avatar colour. */
    private fun profileCard(): LinearLayout {
        val context = requireContext()
        val prefs = AppServices.prefs
        val card = LinearLayout(context)
        card.orientation = LinearLayout.HORIZONTAL
        card.gravity = Gravity.CENTER_VERTICAL
        val pad = Shapes.dpInt(context, 14f)
        card.setPadding(pad, pad, pad, pad)
        card.background = Shapes.pressable(context, Shapes.card(context), Shapes.card(context))
        card.isClickable = true
        card.isFocusable = true

        val fallback = android.os.Build.MODEL ?: getString(R.string.app_name)
        val deviceName = prefs.profileName.ifBlank { fallback }
        val avatar = AvatarView(context)
        avatar.bindProfile(deviceName, prefs.profileAvatar.colorRes)
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
        card.contentDescription = getString(R.string.settings_profile_edit_cd, deviceName)
        card.setOnClickListener { editProfile() }
        return card
    }

    /** Name and avatar colour are saved together and immediately re-rendered. */
    private fun editProfile() {
        val context = requireContext()
        val prefs = AppServices.prefs
        val body = LinearLayout(context)
        body.orientation = LinearLayout.VERTICAL
        val pad = Shapes.dpInt(context, 24f)
        body.setPadding(pad, Shapes.dpInt(context, 8f), pad, 0)

        val name = EditText(context)
        name.hint = getString(R.string.settings_profile_name_hint)
        name.setText(prefs.profileName)
        name.setSingleLine(true)
        body.addView(name, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))

        var selectedAvatar = prefs.profileAvatar
        val swatches = LinkedHashMap<Accent, View>()
        val colours = LinearLayout(context)
        colours.orientation = LinearLayout.HORIZONTAL
        colours.contentDescription = getString(R.string.settings_profile_avatar_colour)
        fun repaintSwatches() {
            for ((accent, swatch) in swatches) {
                val shape = Shapes.circle(ContextCompat.getColor(context, accent.colorRes))
                if (accent == selectedAvatar) {
                    shape.setStroke(Shapes.dpInt(context, 2f), ThemeColors.resolve(context, R.attr.colorTextPrimary))
                }
                swatch.background = shape
            }
        }
        for (accent in Accent.values()) {
            val swatch = View(context)
            swatches[accent] = swatch
            swatch.isClickable = true
            swatch.isFocusable = true
            swatch.contentDescription = accent.key
            swatch.setOnClickListener {
                selectedAvatar = accent
                repaintSwatches()
            }
            val side = Shapes.dpInt(context, 40f)
            val swatchParams = LinearLayout.LayoutParams(side, side)
            swatchParams.rightMargin = Shapes.dpInt(context, 12f)
            swatchParams.topMargin = Shapes.dpInt(context, 16f)
            colours.addView(swatch, swatchParams)
        }
        repaintSwatches()
        body.addView(colours)

        AlertDialog.Builder(context)
            .setTitle(R.string.settings_profile_edit_title)
            .setView(body)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.action_save) { _, _ ->
                prefs.profileName = name.text?.toString().orEmpty()
                prefs.profileAvatar = selectedAvatar
                // Rebuild the current visible screen instead of requiring an
                // app restart before the profile/advertised device name updates.
                requireActivity().recreate()
            }
            .show()
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

    // ----- The wired settings (§6.13) --------------------------------------

    private fun conflictLabel(policy: Prefs.ConflictPolicy): String = getString(
        when (policy) {
            Prefs.ConflictPolicy.ASK -> R.string.settings_conflict_ask
            Prefs.ConflictPolicy.RENAME -> R.string.settings_conflict_rename
            Prefs.ConflictPolicy.OVERWRITE -> R.string.settings_conflict_overwrite
            Prefs.ConflictPolicy.SKIP -> R.string.settings_conflict_skip
        },
    )

    /** §9.5's four policies; the receiver reads this on the next META. */
    private fun chooseConflictPolicy() {
        val policies = Prefs.ConflictPolicy.values()
        val labels = policies.map { conflictLabel(it) }.toTypedArray()
        androidx.appcompat.app.AlertDialog.Builder(requireContext())
            .setTitle(R.string.settings_conflict_policy)
            .setItems(labels) { _, which ->
                AppServices.prefs.setConflictPolicy(policies[which])
                AppServices.applyConflictPolicy()
                conflictRow.bindAction(
                    getString(R.string.settings_conflict_policy),
                    conflictLabel(policies[which]),
                ) { chooseConflictPolicy() }
            }
            .show()
    }

    /** §10.2 / G7: default 4, range 2–8. The discovery cap reads it. */
    private fun chooseBroadcastPeers() {
        val options = (Prefs.MIN_PEERS..Prefs.MAX_PEERS).toList()
        val labels = options.map { getString(R.string.settings_broadcast_peers_value, it) }.toTypedArray()
        androidx.appcompat.app.AlertDialog.Builder(requireContext())
            .setTitle(R.string.settings_broadcast_peers)
            .setItems(labels) { _, which ->
                AppServices.prefs.setBroadcastPeers(options[which])
                peersRow.bindAction(
                    getString(R.string.settings_broadcast_peers),
                    getString(R.string.settings_broadcast_peers_value, options[which]),
                ) { chooseBroadcastPeers() }
            }
            .show()
    }

    private fun storageSubtitle(): String {
        val trees = AppServices.safStore.grantedTrees()
        return if (trees.isEmpty()) {
            getString(R.string.settings_storage_access_sub)
        } else {
            trees.joinToString(" · ") { AppServices.safStore.displayName(it) }
        }
    }

    /**
     * §6.13 Storage access: the granted trees with a per-entry Remove, "+ Add a
     * folder", and §12.3's optional all-files upgrade behind a plain-language
     * rationale — never at first run, never bundled into the permission flow.
     */
    private fun showStorageAccess() {
        val context = requireContext()
        val trees = AppServices.safStore.grantedTrees()
        val labels = ArrayList<String>()
        for (tree in trees) {
            labels.add("${AppServices.safStore.displayName(tree)} — ${getString(R.string.settings_storage_remove)}")
        }
        labels.add(getString(R.string.files_add_folder))
        labels.add(getString(R.string.settings_all_files))

        androidx.appcompat.app.AlertDialog.Builder(context)
            .setTitle(R.string.settings_storage_access)
            .setItems(labels.toTypedArray()) { _, which ->
                when {
                    which < trees.size -> {
                        AppServices.safStore.release(trees[which])
                        storageRow.bindAction(
                            getString(R.string.settings_storage_access),
                            storageSubtitle(),
                        ) { showStorageAccess() }
                    }
                    which == trees.size -> treePicker.launch(null)
                    else -> explainAllFilesAccess()
                }
            }
            .show()
    }

    /** §12.3: the rationale sheet comes BEFORE the system screen. */
    private fun explainAllFilesAccess() {
        androidx.appcompat.app.AlertDialog.Builder(requireContext())
            .setTitle(R.string.settings_all_files)
            .setMessage(R.string.settings_all_files_rationale)
            .setNegativeButton(R.string.action_cancel, null)
            .setPositiveButton(R.string.settings_continue) { _, _ ->
                Permissions.allFilesAccessIntent(requireContext())?.let {
                    runCatching { startActivity(it) }
                }
            }
            .show()
    }

    /**
     * §14.2: concrete steps for Xiaomi, Huawei, Oppo, Vivo and Samsung,
     * ordered with THIS phone's manufacturer first but always listing all of
     * them — a Nokia owner still needs to see the list.
     */
    private fun showAutostartGuidance() {
        val guidance = app.morsecode.android.core.util.PowerPolicy.autostartGuidance()
        val body = guidance.joinToString("\n\n") { "${it.first}\n${it.second}" }
        androidx.appcompat.app.AlertDialog.Builder(requireContext())
            .setTitle(R.string.oem_autostart_title)
            .setMessage(body)
            .setPositiveButton(R.string.action_done, null)
            .show()
    }

    private fun batterySubtitle(): String = if (isBatteryExempt()) {
        getString(R.string.settings_battery_on)
    } else {
        getString(R.string.settings_battery_sub)
    }

    private fun isBatteryExempt(): Boolean {
        if (android.os.Build.VERSION.SDK_INT < 23) return true
        val manager = requireContext().getSystemService(android.content.Context.POWER_SERVICE)
            as? android.os.PowerManager ?: return false
        return manager.isIgnoringBatteryOptimizations(requireContext().packageName)
    }

    /** §14.1: explained, never silent, and never required. */
    private fun requestBatteryExemption() {
        if (isBatteryExempt()) return
        val intent = android.content.Intent(
            android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
            android.net.Uri.parse("package:${requireContext().packageName}"),
        )
        runCatching { startActivity(intent) }
            .onFailure { Ui.snackbar(requireActivity(), getString(R.string.stub_screen)) }
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
