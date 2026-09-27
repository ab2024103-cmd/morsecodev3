package app.morsecode.android.core.storage

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import app.morsecode.android.core.logging.LogStore

/**
 * §12.1 STORAGE ACCESS FRAMEWORK: general files, folders, SD cards and
 * USB-OTG are reached through `ACTION_OPEN_DOCUMENT_TREE` + `DocumentFile`.
 *
 * Every grant is persisted IMMEDIATELY with `takePersistableUriPermission`, so
 * nothing is ever re-asked (§12.1). The authoritative list of grants is the
 * platform's own, read back through `persistedUriPermissions` — keeping a
 * private copy in preferences would drift the moment the user revoked one from
 * system settings.
 */
class SafStore(context: Context, private val logStore: LogStore? = null) {

    private val appContext = context.applicationContext

    /** The intent Settings → Storage access → "+ Add a folder" launches. */
    fun openTreeIntent(initialUri: Uri? = null): Intent {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
        intent.addFlags(
            Intent.FLAG_GRANT_READ_URI_PERMISSION or
                Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION,
        )
        if (initialUri != null && android.os.Build.VERSION.SDK_INT >= 26) {
            intent.putExtra("android.provider.extra.INITIAL_URI", initialUri)
        }
        return intent
    }

    /**
     * Persists a freshly granted tree. Called from the activity result the
     * moment the user picks a folder — not later, not on next use.
     */
    fun persist(treeUri: Uri): Boolean = try {
        appContext.contentResolver.takePersistableUriPermission(
            treeUri,
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
        )
        logStore?.i("SAF tree granted: ${displayName(treeUri)}")
        true
    } catch (e: SecurityException) {
        logStore?.w("SAF grant could not be persisted: ${e.javaClass.simpleName}")
        false
    }

    /** Settings → Storage access lists these, each with a Remove action (§6.13). */
    fun grantedTrees(): List<Uri> = appContext.contentResolver.persistedUriPermissions
        .filter { it.isReadPermission }
        .map { it.uri }

    fun release(treeUri: Uri) {
        try {
            appContext.contentResolver.releasePersistableUriPermission(
                treeUri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
            logStore?.i("SAF tree released: ${displayName(treeUri)}")
        } catch (e: SecurityException) {
            logStore?.w("SAF release failed: ${e.javaClass.simpleName}")
        }
    }

    fun hasGrantFor(treeUri: Uri): Boolean = grantedTrees().any { it == treeUri }

    fun documentFor(treeUri: Uri): DocumentFile? =
        DocumentFile.fromTreeUri(appContext, treeUri)

    /** A human label for a tree URI; falls back to the last path segment. */
    fun displayName(treeUri: Uri): String =
        documentFor(treeUri)?.name ?: treeUri.lastPathSegment ?: treeUri.toString()
}
