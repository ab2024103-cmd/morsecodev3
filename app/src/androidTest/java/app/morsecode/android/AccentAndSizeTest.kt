package app.morsecode.android

import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.morsecode.android.core.util.Accent
import app.morsecode.android.core.util.ThemeColors
import app.morsecode.android.di.AppServices
import androidx.core.content.ContextCompat
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/**
 * A11's accent clause, settled on a device: switching the accent must retint
 * the product, and the colour a screen resolves must be the token's own value.
 */
@RunWith(AndroidJUnit4::class)
class AccentAndSizeTest {

    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()

    @Test
    fun everyAccentRetintsTheProduct() {
        AppServices.prefs.onboardingSeen = true
        for (accent in Accent.values()) {
            AppServices.prefs.setAccent(accent)
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                instrumentation.waitForIdleSync()
                val held = arrayOfNulls<MainActivity>(1)
                scenario.onActivity { activity ->
                    val resolved = ThemeColors.accent(activity)
                    val expected = ContextCompat.getColor(activity, accent.colorRes)
                    assertEquals(
                        "accent ${accent.key} did not reach the theme",
                        expected,
                        resolved,
                    )
                    held[0] = activity
                }
                held[0]?.let { runCatching { Screenshots.capture(it, "accent-${accent.key}", "dark") } }
            }
        }
        AppServices.prefs.setAccent(Accent.DEFAULT)
    }
}
