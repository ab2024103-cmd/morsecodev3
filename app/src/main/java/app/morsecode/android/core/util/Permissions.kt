package app.morsecode.android.core.util

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import androidx.core.content.ContextCompat

/**
 * §3.5 runtime permissions — exactly the declared set, grouped by what the
 * user is actually trying to do, so every prompt can carry the rationale for
 * that one thing.
 *
 * Three rules from the specification are structural here:
 *
 *  - MANAGE_EXTERNAL_STORAGE is never in a request group. It is an optional,
 *    explained upgrade from Settings → Storage access only, never at first run
 *    and never bundled into the permission flow (§3.5, §12.3).
 *  - Battery exemption is a separate, explained prompt (§6.1 slide 2, §14.1),
 *    so it is not here either.
 *  - CAMERA does not appear anywhere: there is no QR scanner in the product
 *    (§11.5, G12).
 */
object Permissions {

    /**
     * Reading the media library. Android 13+ splits it per type; 33 also adds
     * the user-selected visual grant, which the library treats as a partial
     * read rather than a denial.
     */
    fun mediaRead(): Array<String> = when {
        Build.VERSION.SDK_INT >= 33 -> arrayOf(
            Manifest.permission.READ_MEDIA_IMAGES,
            Manifest.permission.READ_MEDIA_VIDEO,
            Manifest.permission.READ_MEDIA_AUDIO,
        )
        else -> arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
    }

    /**
     * Writing received files. From API 29 scoped storage makes MediaStore the
     * write path and no permission is needed; below 29 the legacy write
     * permission is (§12.1).
     */
    fun mediaWrite(): Array<String> =
        if (Build.VERSION.SDK_INT <= 28) arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        else emptyArray()

    /**
     * Discovery. Pre-31 the platform gates Bluetooth scanning behind fine
     * location even though no location value is ever read (§17.5); 31+ uses
     * the neverForLocation Bluetooth permissions; 33+ adds NEARBY_WIFI_DEVICES.
     */
    fun nearby(): Array<String> = when {
        Build.VERSION.SDK_INT >= 33 -> arrayOf(
            Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.BLUETOOTH_ADVERTISE,
            Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.NEARBY_WIFI_DEVICES,
        )
        Build.VERSION.SDK_INT >= 31 -> arrayOf(
            Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.BLUETOOTH_ADVERTISE,
            Manifest.permission.BLUETOOTH_CONNECT,
        )
        else -> arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
    }

    /** The transfer notification is the session's lifeline (§6.18), 33+ only. */
    fun notifications(): Array<String> =
        if (Build.VERSION.SDK_INT >= 33) arrayOf(Manifest.permission.POST_NOTIFICATIONS)
        else emptyArray()

    fun isGranted(context: Context, permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    fun allGranted(context: Context, permissions: Array<String>): Boolean =
        permissions.all { isGranted(context, it) }

    fun missing(context: Context, permissions: Array<String>): List<String> =
        permissions.filterNot { isGranted(context, it) }

    /**
     * §33+ partial media access: the user granted only selected items. The
     * library must show what it can rather than claiming the folder is empty
     * (§12.2, §20.6).
     */
    fun hasPartialMediaAccess(context: Context): Boolean =
        Build.VERSION.SDK_INT >= 34 &&
            !isGranted(context, Manifest.permission.READ_MEDIA_IMAGES) &&
            isGranted(context, "android.permission.READ_MEDIA_VISUAL_USER_SELECTED")

    // ----- All-files access (§12.3) -----------------------------------------

    /** True when the optional all-files upgrade is currently in force. */
    fun hasAllFilesAccess(): Boolean =
        Build.VERSION.SDK_INT >= 30 && Environment.isExternalStorageManager()

    /**
     * The system screen for the all-files upgrade. §12.3: a plain-language
     * rationale sheet is shown BEFORE this, it is offered only from
     * Settings → Storage access, and never at first run — this function
     * returning an intent is not permission to call it from onboarding.
     */
    fun allFilesAccessIntent(context: Context): Intent? {
        if (Build.VERSION.SDK_INT < 30) return null
        return Intent(
            Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
            Uri.parse("package:${context.packageName}"),
        )
    }
}
