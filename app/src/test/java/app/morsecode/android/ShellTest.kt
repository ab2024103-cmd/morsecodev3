package app.morsecode.android

import android.view.View
import androidx.fragment.app.Fragment
import app.morsecode.android.core.ui.BottomNavView
import app.morsecode.android.core.ui.Nav
import app.morsecode.android.core.ui.Purpose
import app.morsecode.android.core.ui.Screen
import app.morsecode.android.feature.dashboard.DashboardFragment
import app.morsecode.android.feature.dashboard.DiscoveryFragment
import app.morsecode.android.feature.filemanager.FilesFragment
import app.morsecode.android.feature.history.HistoryFragment
import app.morsecode.android.feature.settings.SettingsFragment
import app.morsecode.android.feature.onboarding.OnboardingFragment
import app.morsecode.android.feature.transfer.TransferFragment
import app.morsecode.android.di.AppServices
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * §5.1–5.4, the navigation shell.
 *
 * §5.1 makes a crashing tab a release blocker, so the first test opens all
 * four. The rest lock the three rules that are easy to regress silently:
 * Transfer keeps the bottom nav with the launching tab lit (§5.2), Back pops
 * (§5.3), and a destination cannot be opened without an explicit purpose
 * (§5.4, §20.4).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ShellTest {

    /**
     * §6.1: first launch opens the tour. These tests are about the shell, so
     * they start from the state a returning user is in; the tour itself is
     * covered by [onboardingShowsOnceAndIsReplayable].
     */
    @Before
    fun markTourSeen() {
        AppServices.prefs.onboardingSeen = true
    }

    private fun launch() = Robolectric.buildActivity(MainActivity::class.java).setup()

    private fun current(activity: MainActivity): Fragment? =
        activity.supportFragmentManager.findFragmentById(R.id.nav_host)

    @Test
    fun everyTabOpensWithoutCrashing() {
        val controller = launch()
        val activity = controller.get()
        val expected = listOf(
            BottomNavView.Tab.CONNECT to DashboardFragment::class.java,
            BottomNavView.Tab.FILES to FilesFragment::class.java,
            BottomNavView.Tab.HISTORY to HistoryFragment::class.java,
            BottomNavView.Tab.SETTINGS to SettingsFragment::class.java,
        )
        for ((tab, type) in expected) {
            activity.selectTab(tab)
            activity.supportFragmentManager.executePendingTransactions()
            val shown = current(activity)
            assertNotNull("tab $tab rendered nothing", shown)
            assertEquals(type, shown!!.javaClass)
        }
    }

    @Test
    fun connectIsTheStartDestination() {
        val activity = launch().get()
        assertTrue(current(activity) is DashboardFragment)
    }

    @Test
    fun transferKeepsTheBottomNavAndTheLaunchingTabStaysLit() {
        val activity = launch().get()
        activity.selectTab(BottomNavView.Tab.FILES)
        activity.supportFragmentManager.executePendingTransactions()

        activity.push(TransferFragment.newInstance(Purpose.SENDER))
        activity.supportFragmentManager.executePendingTransactions()

        val transfer = current(activity) as Screen
        assertTrue("transfer must keep the nav (§5.2)", !transfer.hidesBottomNav)
        assertEquals(BottomNavView.Tab.FILES, transfer.launchTab())

        val nav = activity.findViewById<BottomNavView>(R.id.bottom_nav)
        assertEquals(View.VISIBLE, nav.visibility)
        assertEquals(BottomNavView.Tab.FILES, nav.selectedTab())
    }

    @Test
    fun backPopsThePushedDestination() {
        val activity = launch().get()
        activity.push(DiscoveryFragment.newInstance(multiSelect = false))
        activity.supportFragmentManager.executePendingTransactions()
        assertTrue(current(activity) is DiscoveryFragment)

        activity.pop()
        activity.supportFragmentManager.executePendingTransactions()
        assertTrue("back must fall back to popping the stack (§5.3)", current(activity) is DashboardFragment)
    }

    @Test
    fun switchingTabsClearsThePushedStack() {
        val activity = launch().get()
        activity.push(DiscoveryFragment.newInstance(multiSelect = true))
        activity.supportFragmentManager.executePendingTransactions()

        activity.selectTab(BottomNavView.Tab.HISTORY)
        activity.supportFragmentManager.executePendingTransactions()

        assertEquals(0, activity.supportFragmentManager.backStackEntryCount)
        assertTrue(current(activity) is HistoryFragment)
    }

    @Test
    fun purposeIsExplicitAndBroadcastDiscoveryIsMultiSelect() {
        val single = DiscoveryFragment.newInstance(multiSelect = false)
        val multi = DiscoveryFragment.newInstance(multiSelect = true)
        assertEquals(Purpose.SENDER, Nav.purposeOf(single))
        assertEquals(Purpose.BROADCAST, Nav.purposeOf(multi))
        assertTrue(multi.requireArguments().getBoolean(Nav.ARG_MULTI_SELECT))
    }

    @Test
    fun onboardingShowsOnceAndIsReplayable() {
        // §6.1: four cards on first launch, and never again by itself.
        AppServices.prefs.onboardingSeen = false
        val first = launch().get()
        first.supportFragmentManager.executePendingTransactions()
        assertTrue(
            "first launch must open the tour",
            current(first) is OnboardingFragment,
        )

        AppServices.prefs.onboardingSeen = true
        val second = launch().get()
        second.supportFragmentManager.executePendingTransactions()
        assertTrue(
            "a returning user lands on Connect",
            current(second) is DashboardFragment,
        )

        // Replayable at will (§6.13 → About → Replay onboarding).
        second.push(OnboardingFragment())
        second.supportFragmentManager.executePendingTransactions()
        assertTrue(current(second) is OnboardingFragment)
    }

    @Test(expected = IllegalStateException::class)
    fun aDestinationWithoutAPurposeFailsLoudly() {
        // §5.4 / §20.4: no silent default, because a default would be an
        // inference from incidental state.
        Nav.purposeOf(TransferFragment())
    }
}
