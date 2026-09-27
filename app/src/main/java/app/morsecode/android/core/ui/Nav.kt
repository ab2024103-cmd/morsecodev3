package app.morsecode.android.core.ui

import android.os.Bundle
import androidx.fragment.app.Fragment

/**
 * Navigation contract for the single-activity shell (§5.2, §8.1).
 *
 * §5.4 / §20.4: a screen's purpose is always an explicit argument. Nothing in
 * here lets a screen ask "is something queued right now" and guess what it is
 * for — [Purpose] is required, and a destination without one fails loudly at
 * the point of the mistake instead of rendering the wrong chrome.
 */
enum class Purpose { SENDER, RECEIVER, BROADCAST, RESUME }

/** Implemented by the activity; screens reach it with [Fragment.navigator]. */
interface Navigator {

    /** Push a destination onto the back stack. The launching tab stays lit (§5.2). */
    fun push(fragment: Fragment)

    /** Switch root tab; clears the pushed stack (§5.1). */
    fun selectTab(tab: BottomNavView.Tab)

    /** Pop one destination. Used by back, Minimize and End (§5.3). */
    fun pop()
}

object Nav {

    const val ARG_PURPOSE = "morsecode.purpose"
    const val ARG_LAUNCH_TAB = "morsecode.launchTab"
    const val ARG_MULTI_SELECT = "morsecode.multiSelect"

    /** Builds the argument bundle every purpose-carrying destination needs. */
    fun args(purpose: Purpose, extras: Bundle = Bundle()): Bundle {
        val bundle = Bundle(extras)
        bundle.putString(ARG_PURPOSE, purpose.name)
        return bundle
    }

    /**
     * Reads the explicit purpose. Throws when it is missing: a screen that
     * silently defaulted would be inferring its job from incidental state,
     * which is exactly what §20.4 forbids.
     */
    fun purposeOf(fragment: Fragment): Purpose {
        val name = fragment.arguments?.getString(ARG_PURPOSE)
            ?: error("${fragment.javaClass.simpleName} was opened without an explicit Purpose (§5.4)")
        return Purpose.valueOf(name)
    }
}

fun Fragment.navigator(): Navigator = requireActivity() as Navigator
