package app.morsecode.android

import android.app.Activity
import android.graphics.Bitmap
import android.os.Build
import android.view.View
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File

/**
 * §21.3: "capturing a PNG per screen per theme, published as build artifacts —
 * these are the inputs to the §4.15 fidelity diff and the only honest evidence
 * that a screen renders at all."
 *
 * Files land in the app's external files dir, which CI pulls after the run.
 */
object Screenshots {

    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()

    /**
     * The first run pulled nothing, because the app-private external directory
     * is not readable by `adb pull` on every image. `/sdcard/morsecode-shots`
     * is, on both API 23 and 34, and the suite holds WRITE_EXTERNAL_STORAGE on
     * the old one.
     */
    val directory: File by lazy {
        val shared = File(android.os.Environment.getExternalStorageDirectory(), "morsecode-shots")
        val usable = if (shared.mkdirs() || shared.isDirectory) {
            shared
        } else {
            File(
                instrumentation.targetContext.getExternalFilesDir(null)
                    ?: instrumentation.targetContext.filesDir,
                "screenshots",
            ).apply { mkdirs() }
        }
        android.util.Log.i("Morsecode", "screenshots → ${usable.absolutePath}")
        usable
    }

    /**
     * Captures the activity's whole window. `UiAutomation.takeScreenshot` needs
     * API 18+ and gives the real composited frame, which is what a fidelity
     * diff has to look at; the view-drawing fallback exists because some
     * emulator images return null for it.
     */
    fun capture(activity: Activity, name: String, theme: String) {
        val file = File(directory, "${name}-$theme.png")
        val bitmap = automationShot() ?: viewShot(activity)
        if (bitmap == null) {
            File(directory, "${name}-$theme.MISSING.txt")
                .writeText("no bitmap could be captured on API ${Build.VERSION.SDK_INT}")
            return
        }
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun automationShot(): Bitmap? = try {
        instrumentation.uiAutomation.takeScreenshot()
    } catch (error: Throwable) {
        null
    }

    private fun viewShot(activity: Activity): Bitmap? = try {
        val root: View = activity.window.decorView.rootView
        val bitmap = Bitmap.createBitmap(
            root.width.coerceAtLeast(1),
            root.height.coerceAtLeast(1),
            Bitmap.Config.ARGB_8888,
        )
        instrumentation.runOnMainSync { root.draw(android.graphics.Canvas(bitmap)) }
        bitmap
    } catch (error: Throwable) {
        null
    }

    /** A note beside the PNGs when a screen could not be reached at all. */
    fun note(name: String, message: String) {
        File(directory, "$name.NOTE.txt").writeText(message)
    }
}
