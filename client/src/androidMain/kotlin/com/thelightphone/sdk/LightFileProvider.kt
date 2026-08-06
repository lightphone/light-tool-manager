package com.thelightphone.sdk

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Binder
import android.os.ParcelFileDescriptor
import android.os.Process
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import com.thelightphone.filemanager.COLUMN_IS_DIRECTORY
import com.thelightphone.filemanager.COLUMN_LAST_MODIFIED
import java.io.File
import java.nio.file.Files

/**
 * Exposes this app's `<filesDir>/shared` directory to the PhotoBrowser server's
 * `ContentResolverFileTree`, which this mirrors column-for-column and call-for-call.
 *
 * To use this, declare it in the consuming app's own manifest with a unique authority (this
 * library intentionally declares no `<provider>` of its own, since the authority must be unique
 * per app):
 *
 * ```xml
 * <provider
 *     android:name="com.thelightphone.sdk.LightFileProvider"
 *     android:authorities="${applicationId}.lightfileprovider"
 *     android:exported="true" />
 * ```
 */
class LightFileProvider : ContentProvider() {

    internal companion object {
        const val SHARED_DIR = "shared"
    }

    // Only the PhotoBrowser server (a system app) may call this — it exposes another app's
    // private storage, so this is load-bearing access control, not a convenience check.
    private fun checkCaller() {
        if (Binder.getCallingUid() != Process.SYSTEM_UID) {
            throw SecurityException("Access denied")
        }
    }

    private fun root(): File = File(context!!.filesDir, SHARED_DIR).canonicalFile

    private fun resolveFile(uri: Uri): File {
        val path = uri.path?.removePrefix("/").orEmpty()
        val root = root()
        val file = if (path.isEmpty()) root else File(root, path).canonicalFile
        // Path.startsWith is component-aware; String.startsWith is not, and would also let a
        // sibling like ".../shared-other" pass a ".../shared" prefix check.
        if (!file.toPath().startsWith(root.toPath())) {
            throw SecurityException("Path traversal not allowed")
        }
        return file
    }

    override fun onCreate(): Boolean = true

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        checkCaller()
        val file = resolveFile(uri)
        val pfdMode = when (mode) {
            "r" -> ParcelFileDescriptor.MODE_READ_ONLY
            "w" -> ParcelFileDescriptor.MODE_WRITE_ONLY or ParcelFileDescriptor.MODE_CREATE or ParcelFileDescriptor.MODE_TRUNCATE
            "rw" -> ParcelFileDescriptor.MODE_READ_WRITE or ParcelFileDescriptor.MODE_CREATE
            else -> throw IllegalArgumentException("Unsupported mode: $mode")
        }
        return ParcelFileDescriptor.open(file, pfdMode)
    }

    // Two distinct modes, matching how ContentResolverFileTree actually calls this:
    //  - projection == null: list this path's children, one row per child (browsing a
    //    directory via listEntries).
    //  - projection != null: describe this path itself, a single row (checking whether a
    //    specific file/directory already exists before a write). Returns null if the path
    //    doesn't exist at all, so the caller can fall back to checking its parent the same way.
    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?
    ): Cursor? {
        checkCaller()
        val file = resolveFile(uri)
        if (!file.exists()) return null

        val columns = projection ?: arrayOf(
            OpenableColumns.DISPLAY_NAME,
            OpenableColumns.SIZE,
            COLUMN_IS_DIRECTORY,
            COLUMN_LAST_MODIFIED
        )
        val rows = if (projection == null && file.isDirectory) {
            file.listFiles { child -> !Files.isSymbolicLink(child.toPath()) }.orEmpty().toList()
        } else {
            listOf(file)
        }

        val cursor = MatrixCursor(columns)
        for (row in rows) {
            cursor.addRow(columns.map { column -> columnValue(row, column) })
        }
        return cursor
    }

    private fun columnValue(file: File, column: String): Any? = when (column) {
        OpenableColumns.DISPLAY_NAME -> file.name
        OpenableColumns.SIZE -> if (file.isDirectory) null else file.length()
        COLUMN_IS_DIRECTORY -> if (file.isDirectory) 1 else 0
        COLUMN_LAST_MODIFIED -> file.lastModified()
        else -> null
    }

    override fun getType(uri: Uri): String? {
        val path = uri.path ?: return null
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(path.substringAfterLast('.', ""))
    }

    // Writes go through openFile(mode = "w"/"rw"), not insert() — nothing in
    // ContentResolverFileTree's contract calls this.
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int {
        checkCaller()
        val file = resolveFile(uri)
        if (!file.exists()) return 0
        var count = 0
        file.walkBottomUp().forEach { if (it.delete()) count++ }
        return count
    }

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?
    ): Int {
        checkCaller()
        val newName = values?.getAsString(OpenableColumns.DISPLAY_NAME) ?: return 0
        val file = resolveFile(uri)
        if (!file.exists()) return 0
        val dest = File(file.parentFile, newName)
        if (dest.exists()) return 0
        return if (file.renameTo(dest)) 1 else 0
    }
}
