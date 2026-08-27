package com.thelightphone.toolmanager

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.os.Process
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import java.io.File
import java.nio.file.Files
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * EXPERIMENTAL
 * Exposes this tools's `<filesDir>/shared` directory to the ToolManager server's
 * `ContentResolverFileTree`
 */
class LightFileProvider : ContentProvider() {

    companion object {
        const val SHARED_DIR = "shared"

        // Client tool should set this.
        @Volatile
        var manifest: () -> ClientToolManifest? = { null }

        // Client tool sets this to react to writes/deletes/renames under its shared/ directory
        @Volatile
        var onToolManagerDataUpdate: (() -> Unit)? = null

        @Volatile
        var dataUpdateDebounceDuration: Duration = 3.seconds

        private val debounceHandler = Handler(Looper.getMainLooper())
        private val fireChange = Runnable { onToolManagerDataUpdate?.invoke() }
    }

    private fun notifyChanged() {
        if (onToolManagerDataUpdate == null) return
        debounceHandler.removeCallbacks(fireChange)
        debounceHandler.postDelayed(fireChange, dataUpdateDebounceDuration.inWholeMilliseconds)
    }

    // Ensure that client is the SDK server (LightOS)
    // we might want to make this optional?
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

        if (mode == "r") {
            return ParcelFileDescriptor.open(file, pfdMode)
        }

        // if file was opened for write, notify when closed
        return ParcelFileDescriptor.open(file, pfdMode, Handler(Looper.getMainLooper())) { error ->
            if (error == null) notifyChanged()
        }
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

    // Writes go through openFile(mode = "w"/"rw"), not insert()
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
        checkCaller()
        if (method != METHOD_GET_MANIFEST) return null
        val tree = manifest.invoke() ?: return null
        return Bundle().apply { putString(RESULT_MANIFEST, tree.encode()) }
    }

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int {
        checkCaller()
        val file = resolveFile(uri)
        if (file == root()) {
            throw SecurityException("Refusing to delete the shared root directory")
        }
        if (!file.exists()) return 0
        var count = 0
        file.walkBottomUp().forEach { if (it.delete()) count++ }
        if (count > 0) notifyChanged()
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
        if (file == root()) {
            throw SecurityException("Refusing to rename the shared root directory")
        }
        if (!file.exists()) return 0
        val dest = File(file.parentFile, newName)
        if (dest.exists()) return 0
        val renamed = file.renameTo(dest)
        if (renamed) notifyChanged()
        return if (renamed) 1 else 0
    }
}
