package app.morsecode.android.core.ui

import android.app.Activity
import androidx.appcompat.app.AppCompatDelegate
import app.morsecode.android.core.data.Prefs

/**
 * §4.1 THEMES: Light, Dark, Follow system — and §4.4's five accents.
 *
 * One call before `setContentView` re-skins a whole screen, which is what makes
 * "changing the accent retints the whole app instantly" true (§6.13, A11): the
 * accent is a full theme, so a recreate is enough and no view needs a manual
 * repaint path that could drift from the token file.
 */
object Themes {

    fun nightMode(mode: Prefs.ThemeMode): Int = when (mode) {
        Prefs.ThemeMode.LIGHT -> AppCompatDelegate.MODE_NIGHT_NO
        Prefs.ThemeMode.DARK -> AppCompatDelegate.MODE_NIGHT_YES
        Prefs.ThemeMode.SYSTEM -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
    }

    /** Applies the stored night mode process-wide. */
    fun applyNightMode(prefs: Prefs) {
        AppCompatDelegate.setDefaultNightMode(nightMode(prefs.themeMode.value))
    }

    /** Applies the stored accent to one activity. Call before setContentView. */
    fun applyAccent(activity: Activity, prefs: Prefs) {
        activity.setTheme(prefs.accent.value.themeRes)
    }
}
