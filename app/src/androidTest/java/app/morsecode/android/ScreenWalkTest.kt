package app.morsecode.android

import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.morsecode.android.core.data.Prefs
import app.morsecode.android.core.ui.BottomNavView
import app.morsecode.android.core.ui.Purpose
import app.morsecode.android.core.ui.Themes
import app.morsecode.android.di.AppServices
import app.morsecode.android.feature.dashboard.DiscoveryFragment
import app.morsecode.android.feature.help.HelpFragment
import app.morsecode.android.feature.onboarding.OnboardingFragment
import app.morsecode.android.feature.settings.ConnectionDoctorFragment
import app.morsecode.android.feature.settings.LogViewerFragment
import app.morsecode.android.feature.transfer.BroadcastFragment
import app.morsecode.android.feature.transfer.TransferFragment
import app.morsecode.android.feature.viewer.MusicPlayerFragment
import app.morsecode.android.feature.webshare.WebShareFragment
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * §21.3: "walk every screen in §6 and §7 once, capturing a PNG per screen per
 * theme".
 *
 * This is the test that turns "compiles" into "runs": every destination is
 * actually instantiated on a device, in both themes. A screen that throws on
 * inflate fails here — which is the point.
 */
@RunWith(AndroidJUnit4::class)
class ScreenWalkTest {

    // No GrantPermissionRule here: API 34 answers WRITE_EXTERNAL_STORAGE with
    // a SecurityException, and screenshots are written by the shell anyway.

    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()

    private fun setTheme(dark: Boolean) {
        val prefs = AppServices.prefs
        prefs.setThemeMode(if (dark) Prefs.ThemeMode.DARK else Prefs.ThemeMode.LIGHT)
        instrumentation.runOnMainSync { Themes.applyNightMode(prefs) }
        instrumentation.waitForIdleSync()
    }

    /** Pushes a destination and photographs it. Failures are reported, not hidden. */
    private fun walk(name: String, theme: String, push: (MainActivity) -> Unit) {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                push(activity)
                activity.supportFragmentManager.executePendingTransactions()
            }
            instrumentation.waitForIdleSync()
            Thread.sleep(SETTLE_MS)
            scenario.onActivity { activity -> runCatching { Screenshots.capture(activity, name, theme) } }
        }
    }

    @Test
    fun everyScreenRendersInBothThemes() {
        AppServices.prefs.onboardingSeen = true
        val failures = ArrayList<String>()

        for (dark in listOf(true, false)) {
            val theme = if (dark) "dark" else "light"
            setTheme(dark)

            val destinations: List<Pair<String, (MainActivity) -> Unit>> = listOf(
                // §6.2, §6.9, §6.12, §6.13 — the four tabs.
                "06.2-connect" to { activity -> activity.selectTab(BottomNavView.Tab.CONNECT) },
                "06.9-files" to { activity -> activity.selectTab(BottomNavView.Tab.FILES) },
                "06.12-history" to { activity -> activity.selectTab(BottomNavView.Tab.HISTORY) },
                "06.13-settings" to { activity -> activity.selectTab(BottomNavView.Tab.SETTINGS) },
                // The pushed destinations.
                "06.1-onboarding" to { activity -> activity.push(OnboardingFragment()) },
                "06.3-discovery" to { activity ->
                    activity.push(DiscoveryFragment.newInstance(multiSelect = false))
                },
                "06.3-discovery-multi" to { activity ->
                    activity.push(DiscoveryFragment.newInstance(multiSelect = true))
                },
                "06.4-transfer-sending" to { activity ->
                    activity.push(TransferFragment.newInstance(Purpose.SENDER))
                },
                "06.5-receive-listening" to { activity ->
                    activity.push(TransferFragment.newInstance(Purpose.RECEIVER))
                },
                "06.8-broadcast" to { activity -> activity.push(BroadcastFragment.newInstance()) },
                "06.11-music" to { activity -> activity.push(MusicPlayerFragment()) },
                "06.14-logs" to { activity -> activity.push(LogViewerFragment()) },
                "06.15-doctor" to { activity -> activity.push(ConnectionDoctorFragment()) },
                "06.17-help" to { activity -> activity.push(HelpFragment()) },
                "07.1-webshare" to { activity -> activity.push(WebShareFragment()) },
            )

            for ((name, push) in destinations) {
                try {
                    walk(name, theme, push)
                } catch (error: Throwable) {
                    // §21.3 wants the real failure list, so a screen that
                    // cannot render is recorded and the walk continues.
                    failures.add("$name ($theme): ${error.javaClass.simpleName}: ${error.message}")
                    Screenshots.note("$name-$theme", error.stackTraceToString())
                }
            }
        }

        Screenshots.flushProblems()
        assertTrue(
            "screens that failed to render:\n" + failures.joinToString("\n"),
            failures.isEmpty(),
        )
    }

    private companion object {
        const val SETTLE_MS = 700L
    }
}
