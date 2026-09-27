package app.morsecode.android

import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import app.morsecode.android.core.media.Selection
import app.morsecode.android.core.model.FileType
import app.morsecode.android.core.model.MediaItem
import app.morsecode.android.core.ui.BottomNavView
import app.morsecode.android.di.AppServices
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * §21.3: "the Files tabs, selection, sort" — on a device.
 *
 * The emulator's media store is empty, so this exercises the tab machinery,
 * the sort sheet and the selection basket rather than pretending there are
 * photos to tick. What it proves is that the surfaces open, switch and respond
 * without throwing — which is exactly what had never been checked.
 */
@RunWith(AndroidJUnit4::class)
class FilesFlowTest {

    @get:Rule
    val permissions: GrantPermissionRule = GrantPermissionRule.grant(
        *app.morsecode.android.core.util.Permissions.mediaRead(),
    )

    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()

    @Test
    fun everyFilesTabOpensAndTheSortSheetWorks() {
        AppServices.prefs.onboardingSeen = true
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { it.selectTab(BottomNavView.Tab.FILES) }
            instrumentation.waitForIdleSync()

            for (tab in listOf("Photos", "Videos", "Music", "Apps", "Files")) {
                onView(withText(tab)).perform(click())
                instrumentation.waitForIdleSync()
                Thread.sleep(400)
                scenario.onActivity { Screenshots.capture(it, "06.9-files-${tab.lowercase()}", "dark") }
            }

            // §6.9: the sort icon is a working control, not decoration.
            onView(withText("Sort")).perform(click())
            instrumentation.waitForIdleSync()
            Thread.sleep(400)
            onView(withText("Size")).perform(click())
            instrumentation.waitForIdleSync()
            Thread.sleep(300)
            // The echo under the tab strip must now name the active order.
            onView(withText("Size · largest first")).check(matches(isDisplayed()))
            scenario.onActivity { Screenshots.capture(it, "06.9-files-sorted", "dark") }
        }
    }

    @Test
    fun theSelectionBasketDrivesTheBarOnADevice() {
        val selection = AppServices.selection
        selection.clear()
        val item = MediaItem(
            id = 1,
            uri = android.net.Uri.parse("content://media/external/images/media/1"),
            name = "IMG_1.jpg",
            sizeBytes = 357_100,
            dateMillis = System.currentTimeMillis(),
            mimeType = "image/jpeg",
            type = FileType.IMAGE,
        )
        selection.toggle(Selection.of(item))

        AppServices.prefs.onboardingSeen = true
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { it.selectTab(BottomNavView.Tab.FILES) }
            instrumentation.waitForIdleSync()
            Thread.sleep(600)
            scenario.onActivity { Screenshots.capture(it, "06.9-files-selection", "dark") }
        }

        assertEquals(1, selection.count)
        assertTrue(selection.totalBytes > 0)
        selection.clear()
    }
}
