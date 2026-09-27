package app.morsecode.android.core.logging

import android.content.Context
import android.util.Log
import app.morsecode.android.core.util.Ids
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Diagnostics ring buffer (§16).
 *
 * §16.1 Morsecode's own lines only — never an unfiltered dump of the device.
 * §16.2 Lines go to a persistent in-app file, not only to logcat.
 * §16.3 Export is ONE .txt headed "Morsecode <version> (<versionCode>)"
 *       containing the buffer plus every stored crash report.
 * §16.6 Crash reports are stored locally and NEVER uploaded.
 *
 * API-23 safe: no java.nio.file, no streams, no Java-8 default collection
 * methods (§3.2).
 */
class LogStore(context: Context) {

    enum class Level { INFO, WARN, ERROR }

    /** One rendered log line. `timeMs` is wall-clock milliseconds. */
    class Line(val timeMs: Long, val level: Level, val message: String) {
        fun clockTime(): String = CLOCK.format(Date(timeMs))
        fun render(): String = "${STAMP.format(Date(timeMs))} ${level.name.padEnd(5)} $message"
    }

    private val appContext = context.applicationContext
    private val lock = Any()
    private val buffer = ArrayList<Line>(RING_CAPACITY)

    private val logDir: File by lazy { File(appContext.filesDir, "logs").apply { mkdirs() } }
    private val logFile: File by lazy { File(logDir, "morsecode.log") }
    private val crashDir: File by lazy { File(appContext.filesDir, "crash").apply { mkdirs() } }

    /** Set by the "Clear" action so older lines from sources we do not own are filtered (§16.2). */
    @Volatile
    var clearedAtMs: Long = 0L
        private set

    fun i(message: String) = add(Level.INFO, message)

    fun w(message: String) = add(Level.WARN, message)

    fun e(message: String) = add(Level.ERROR, message)

    fun add(level: Level, message: String) {
        val line = Line(System.currentTimeMillis(), level, message)
        synchronized(lock) {
            if (buffer.size >= RING_CAPACITY) buffer.removeAt(0)
            buffer.add(line)
        }
        when (level) {
            Level.INFO -> Log.i(TAG, message)
            Level.WARN -> Log.w(TAG, message)
            Level.ERROR -> Log.e(TAG, message)
        }
        appendToFile(line)
    }

    /** Newest-last snapshot for the log viewer (§6.14). */
    fun snapshot(): List<Line> = synchronized(lock) { ArrayList(buffer) }

    fun clear() {
        synchronized(lock) {
            buffer.clear()
            clearedAtMs = System.currentTimeMillis()
        }
        logFile.delete()
        i("Log cleared")
    }

    /**
     * §6.13's "Crash reports · Local · never uploaded" switch is read HERE,
     * before anything is written. A stored-but-unread preference is a defect,
     * so the setting owns the behaviour rather than merely describing it.
     */
    var crashCaptureEnabled: () -> Boolean = { true }

    fun recordCrash(thread: Thread, error: Throwable) {
        if (!crashCaptureEnabled()) return
        val writer = StringWriter()
        PrintWriter(writer).use { error.printStackTrace(it) }
        val stamp = FILE_STAMP.format(Date())
        val body = buildString {
            append(Ids.logHeader(versionName(), versionCode())).append('\n')
            append("Crash at ").append(STAMP.format(Date())).append('\n')
            append("Thread: ").append(thread.name).append('\n')
            append("Device: ").append(android.os.Build.MANUFACTURER).append(' ')
                .append(android.os.Build.MODEL).append(" · API ")
                .append(android.os.Build.VERSION.SDK_INT).append('\n').append('\n')
            append(writer.toString())
        }
        File(crashDir, "crash-$stamp.txt").writeText(body)
        e("Crash captured: ${error.javaClass.simpleName}: ${error.message}")
    }

    fun crashReports(): List<File> {
        val files = crashDir.listFiles()
        val out = ArrayList<File>()
        if (files != null) {
            for (f in files) if (f.isFile) out.add(f)
        }
        // Manual sort — no Java-8 Comparator default methods below API 24 (§3.2).
        java.util.Collections.sort(out, java.util.Comparator { a, b -> a.name.compareTo(b.name) })
        return out
    }

    /** The exported .txt body (§16.3). */
    fun exportText(versionName: String = versionName(), versionCode: Int = versionCode()): String {
        val sb = StringBuilder()
        sb.append(Ids.logHeader(versionName, versionCode)).append('\n')
        sb.append("Exported ").append(STAMP.format(Date())).append('\n')
        sb.append("--------------------------------------------------------------\n")
        for (line in snapshot()) sb.append(line.render()).append('\n')
        val crashes = crashReports()
        if (crashes.isNotEmpty()) {
            sb.append('\n').append("CRASH REPORTS (").append(crashes.size).append(")\n")
            for (f in crashes) {
                sb.append("--------------------------------------------------------------\n")
                sb.append(f.name).append('\n')
                sb.append(f.readText()).append('\n')
            }
        }
        return sb.toString()
    }

    private fun appendToFile(line: Line) {
        try {
            if (logFile.exists() && logFile.length() > MAX_FILE_BYTES) {
                val rolled = File(logDir, "morsecode.log.1")
                rolled.delete()
                logFile.renameTo(rolled)
            }
            logFile.appendText(line.render() + "\n")
        } catch (io: java.io.IOException) {
            Log.w(TAG, "Log file write failed: ${io.message}")
        }
    }

    private fun versionName(): String = try {
        appContext.packageManager.getPackageInfo(appContext.packageName, 0).versionName
            ?: Ids.VERSION_NAME
    } catch (e: Exception) {
        Ids.VERSION_NAME
    }

    @Suppress("DEPRECATION")
    private fun versionCode(): Int = try {
        val info = appContext.packageManager.getPackageInfo(appContext.packageName, 0)
        if (android.os.Build.VERSION.SDK_INT >= 28) info.longVersionCode.toInt() else info.versionCode
    } catch (e: Exception) {
        Ids.VERSION_CODE
    }

    companion object {
        private const val TAG = "Morsecode"
        private const val RING_CAPACITY = 2000
        private const val MAX_FILE_BYTES = 512L * 1024L

        private val CLOCK = SimpleDateFormat("HH:mm:ss", Locale.US)
        private val STAMP = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
        private val FILE_STAMP = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US)
    }
}
