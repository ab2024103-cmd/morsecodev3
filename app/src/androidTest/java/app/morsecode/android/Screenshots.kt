package app.morsecode.android

import android.app.Activity
import android.graphics.Bitmap
import android.os.Build
import android.os.ParcelFileDescriptor
import android.view.View
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.io.FileInputStream

/**
 * §21.3: "capturing a PNG per screen per theme, published as build artifacts —
 * these are the inputs to the §4.15 fidelity diff and the only honest evidence
 * that a screen renders at all."
 *
 * Two lessons from the first two emulator runs are baked in here:
 *
 *  1. **Capture through the shell, not the app.** Writing to `/sdcard` from the
 *     test process failed with EACCES on API 23 and EPERM on API 34 — scoped
 *     storage means an app cannot put files where `adb pull` can reach them.
 *     `screencap` runs as the shell user, which can, and it also captures the
 *     real composited frame rather than a redrawn view tree.
 *  2. **A screenshot must never fail a test.** The second run reported four
 *     "failures" that were all this file's fault, masking what the product
 *     actually did. Capture is best-effort and records its own problems.
 */
object Screenshots {

    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()

    /**
     * `/data/local/tmp` — not `/sdcard`.
     *
     * The evidence from three runs: `/sdcard/morsecode-shots` stayed empty and
     * the app-private external directory was never even created, because
     * `getExternalFilesDir` can return null and scoped storage blocks the rest.
     * `/data/local/tmp` is writable by the shell user (which is what
     * `screencap` runs as) and readable by `adb pull` on every image.
     */
    const val SHELL_DIR = "/data/local/tmp/morsecode-shots"

    /** Fallback when `screencap` is unavailable: app-private, still pullable. */
    private val appDirectory: File by lazy {
        val base = instrumentation.targetContext.getExternalFilesDir(null)
            ?: instrumentation.targetContext.filesDir
        File(base, "screenshots").apply { mkdirs() }
    }

    private val problems = StringBuilder()

    /** Written once, so a zero-PNG run can be diagnosed from the artifact. */
    private var probed = false

    private fun probeOnce() {
        if (probed) return
        probed = true
        val result = runCatching {
            shell("mkdir -p $SHELL_DIR")
            shell("ls -ld $SHELL_DIR")
        }.getOrElse { "probe failed: ${it.javaClass.simpleName}: ${it.message}" }
        report("probe: ${result.trim()}")
    }

    /**
     * MUST be called from the test thread, never from inside `onActivity`.
     * `waitForIdleSync` throws on the main thread, and three rounds of zero
     * screenshots were exactly that: the throw happened on capture's first
     * line and the caller's `runCatching` swallowed it, so nothing was written
     * and nothing was logged.
     */
    fun capture(activity: Activity, name: String, theme: String) {
        probeOnce()
        val fileName = "$name-$theme.png"
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) {
            report("capture called on the main thread: $fileName")
            return
        }
        instrumentation.waitForIdleSync()
        if (shellCapture(fileName)) {
            report("ok(shell): $fileName")
            return
        }
        if (bitmapCapture(activity, fileName)) {
            report("ok(bitmap): $fileName")
            return
        }
        note(fileName, "no capture path worked on API ${Build.VERSION.SDK_INT}")
        flushProblems()
    }

    /** `screencap -p <path>`, run as shell through UiAutomation. */
    /**
     * Diagnostics go to logcat, which CI already captures — the last two runs
     * wrote them to a directory adb could not read, which is how a
     * zero-screenshot result stayed unexplained for two rounds.
     */
    private fun report(message: String) {
        android.util.Log.i(TAG, message)
        problems.append(message).append("\n")
    }

    private fun shellCapture(fileName: String): Boolean = try {
        shell("mkdir -p $SHELL_DIR")
        shell("screencap -p $SHELL_DIR/$fileName")
        // screencap returns before the file is flushed on slow emulators.
        var size = 0L
        var waited = 0
        while (size == 0L && waited < CAPTURE_TIMEOUT_MS) {
            size = shell("stat -c %s $SHELL_DIR/$fileName").trim().toLongOrNull() ?: 0L
            if (size == 0L) {
                Thread.sleep(POLL_MS)
                waited += POLL_MS.toInt()
            }
        }
        size > 0
    } catch (error: Throwable) {
        report("shellCapture $fileName failed: ${error.javaClass.simpleName}: ${error.message}")
        false
    }

    private fun bitmapCapture(activity: Activity, fileName: String): Boolean = try {
        val bitmap = runCatching { instrumentation.uiAutomation.takeScreenshot() }.getOrNull()
            ?: drawView(activity)
        if (bitmap == null) {
            false
        } else {
            File(appDirectory, fileName).outputStream().use {
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
            }
            true
        }
    } catch (error: Throwable) {
        problems.append("bitmapCapture $fileName: ${error.javaClass.simpleName}\n")
        false
    }

    private fun drawView(activity: Activity): Bitmap? = try {
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
        runCatching { shell("echo ${quote(message.take(400))} > $SHELL_DIR/$name.NOTE.txt") }
        runCatching { File(appDirectory, "$name.NOTE.txt").writeText(message) }
    }

    /** Written continuously so a zero-PNG result explains itself. */
    fun flushProblems() {
        if (problems.isEmpty()) return
        runCatching { File(appDirectory, "capture-problems.txt").writeText(problems.toString()) }
        runCatching { shell("echo ${quote(problems.toString().take(2000))} > $SHELL_DIR/capture-problems.txt") }
    }

    private fun shell(command: String): String {
        val descriptor: ParcelFileDescriptor = instrumentation.uiAutomation.executeShellCommand(command)
        return ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { stream ->
            FileInputStream(descriptor.fileDescriptor).let { stream.readBytes().toString(Charsets.UTF_8) }
        }
    }

    private fun quote(text: String): String = "'" + text.replace("'", "") + "'"

    private const val TAG = "MorsecodeShots"
    private const val CAPTURE_TIMEOUT_MS = 4000
    private const val POLL_MS = 200L
}
