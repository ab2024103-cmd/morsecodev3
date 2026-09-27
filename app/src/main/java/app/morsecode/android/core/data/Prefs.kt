package app.morsecode.android.core.data

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import app.morsecode.android.core.util.Accent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Stored preferences.
 *
 * §8.2: anything the UI must react to is a Flow, never a plain field, so the
 * accent picker and the theme switch retint every open screen at once.
 * §6.13: a stored-but-unread preference is a defect — every value exposed here
 * is read by the behaviour it names.
 */
class Prefs(context: Context) {

    /** §4.1 THEMES: three user choices. */
    enum class ThemeMode(val key: String) {
        LIGHT("light"), DARK("dark"), SYSTEM("system");

        companion object {
            fun fromKey(key: String?): ThemeMode {
                if (key == null) return SYSTEM
                for (mode in values()) if (mode.key == key) return mode
                return SYSTEM
            }
        }
    }

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences("morsecode.prefs", Context.MODE_PRIVATE)

    private val themeModeFlow = MutableStateFlow(resolveInitialThemeMode())
    private val accentFlow = MutableStateFlow(Accent.fromKey(prefs.getString(KEY_ACCENT, null)))

    val themeMode: StateFlow<ThemeMode> get() = themeModeFlow
    val accent: StateFlow<Accent> get() = accentFlow

    fun setThemeMode(mode: ThemeMode) {
        prefs.edit().putString(KEY_THEME_MODE, mode.key).apply()
        themeModeFlow.value = mode
    }

    fun setAccent(accent: Accent) {
        prefs.edit().putString(KEY_ACCENT, accent.key).apply()
        accentFlow.value = accent
    }

    /** §6.1: onboarding is replayable, so this is a flag, never a one-way door. */
    var onboardingSeen: Boolean
        get() = prefs.getBoolean(KEY_ONBOARDING_SEEN, false)
        set(value) = prefs.edit().putBoolean(KEY_ONBOARDING_SEEN, value).apply()

    /** §4.12g: one-time hints, dismissed for good once the user closes them. */
    fun isTipDismissed(id: String): Boolean = prefs.getBoolean(KEY_TIP_PREFIX + id, false)

    fun dismissTip(id: String) {
        prefs.edit().putBoolean(KEY_TIP_PREFIX + id, true).apply()
    }

    /**
     * §4.1: first run resolves to the system setting; if the system has no
     * preference, Dark. A system-wide dark-theme setting only exists from
     * API 29, so below that "no preference" is the honest reading and the
     * product starts Dark (§20.6).
     */
    private fun resolveInitialThemeMode(): ThemeMode {
        val stored = prefs.getString(KEY_THEME_MODE, null)
        if (stored != null) return ThemeMode.fromKey(stored)
        val resolved = if (Build.VERSION.SDK_INT >= 29) ThemeMode.SYSTEM else ThemeMode.DARK
        prefs.edit().putString(KEY_THEME_MODE, resolved.key).apply()
        return resolved
    }

    companion object {
        private const val KEY_THEME_MODE = "theme_mode"
        private const val KEY_ACCENT = "accent"
        private const val KEY_ONBOARDING_SEEN = "onboarding_seen"
        private const val KEY_TIP_PREFIX = "tip."
    }
}
