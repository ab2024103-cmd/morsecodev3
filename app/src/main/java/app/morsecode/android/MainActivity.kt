package app.morsecode.android

import android.os.Bundle
import android.view.View
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import app.morsecode.android.core.ui.BottomNavView
import app.morsecode.android.core.ui.Nav
import app.morsecode.android.core.ui.Navigator
import app.morsecode.android.core.ui.Screen
import app.morsecode.android.core.ui.Themes
import app.morsecode.android.databinding.ActivityMainBinding
import app.morsecode.android.di.AppServices
import app.morsecode.android.feature.dashboard.DashboardFragment
import app.morsecode.android.feature.filemanager.FilesFragment
import app.morsecode.android.feature.history.HistoryFragment
import app.morsecode.android.feature.settings.SettingsFragment

/**
 * The single activity (§8.1): fragment destinations, one back stack, and the
 * four-tab shell (§5.1).
 *
 * §5.2 — Transfer, Broadcast and Now-Playing are full destinations that KEEP
 * the bottom nav visible, with the tab they were launched from still
 * highlighted. The photo viewer and the video player are the documented
 * exception and are separate immersive activities, so they never reach here.
 *
 * §5.3 — Back pops the stack. A destination that wants different behaviour
 * (Transfer's Back = Minimize) registers its own callback; when nothing does,
 * popping is always the fallback, which is what covers the post-crash
 * relaunch state.
 */
class MainActivity : AppCompatActivity(), Navigator {

    private lateinit var binding: ActivityMainBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        // The accent is a full theme, so it must be applied before the first
        // view is inflated (§4.4, §6.13).
        Themes.applyAccent(this, AppServices.prefs)
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.bottomNav.setOnTabSelected { tab -> showRoot(tab) }

        if (savedInstanceState == null) {
            showRoot(BottomNavView.Tab.CONNECT)
        }

        supportFragmentManager.addOnBackStackChangedListener { syncShell() }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (supportFragmentManager.backStackEntryCount > 0) {
                    supportFragmentManager.popBackStack()
                } else if (binding.bottomNav.selectedTab() != BottomNavView.Tab.CONNECT) {
                    // No sensible up-target on a root tab other than Connect.
                    binding.bottomNav.select(BottomNavView.Tab.CONNECT)
                } else {
                    finish()
                }
            }
        })
        syncShell()
    }

    // ----- Navigator (§5.2) -------------------------------------------------

    override fun push(fragment: Fragment) {
        val args = Bundle(fragment.arguments ?: Bundle())
        // Remember which tab launched this destination so it stays lit (§5.2).
        args.putString(Nav.ARG_LAUNCH_TAB, binding.bottomNav.selectedTab().name)
        fragment.arguments = args

        supportFragmentManager.beginTransaction()
            .replace(R.id.nav_host, fragment, fragment.javaClass.name)
            .addToBackStack(fragment.javaClass.name)
            .setReorderingAllowed(true)
            .commit()
    }

    override fun selectTab(tab: BottomNavView.Tab) {
        binding.bottomNav.select(tab)
    }

    override fun pop() {
        if (supportFragmentManager.backStackEntryCount > 0) {
            supportFragmentManager.popBackStack()
        } else {
            onBackPressedDispatcher.onBackPressed()
        }
    }

    // ----- Shell ------------------------------------------------------------

    private fun showRoot(tab: BottomNavView.Tab) {
        // Switching tabs drops the pushed stack; the four roots are peers.
        supportFragmentManager.popBackStackImmediate(
            null,
            androidx.fragment.app.FragmentManager.POP_BACK_STACK_INCLUSIVE,
        )
        val fragment: Fragment = when (tab) {
            BottomNavView.Tab.CONNECT -> DashboardFragment()
            BottomNavView.Tab.FILES -> FilesFragment()
            BottomNavView.Tab.HISTORY -> HistoryFragment()
            BottomNavView.Tab.SETTINGS -> SettingsFragment()
        }
        supportFragmentManager.beginTransaction()
            .replace(R.id.nav_host, fragment, fragment.javaClass.name)
            .setReorderingAllowed(true)
            .commit()
        supportFragmentManager.executePendingTransactions()
        syncShell()
    }

    /** Applies the current destination's two shell facts (§5.2). */
    private fun syncShell() {
        val current = supportFragmentManager.findFragmentById(R.id.nav_host) as? Screen ?: return
        binding.bottomNav.visibility = if (current.hidesBottomNav) View.GONE else View.VISIBLE
        val lit = current.navTab ?: current.launchTab()
        if (lit != null && lit != binding.bottomNav.selectedTab()) {
            // notify = false: lighting the launching tab must not re-navigate.
            binding.bottomNav.select(lit, notify = false)
        }
    }
}
