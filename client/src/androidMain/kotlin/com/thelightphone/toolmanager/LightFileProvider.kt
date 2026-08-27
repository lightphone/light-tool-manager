package com.thelightphone.toolmanager

import android.content.ContentProvider
import android.content.ContentResolver
import android.content.ContentValues
import android.content.res.AssetFileDescriptor
import android.database.Cursor
import android.database.MatrixCursor
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.media.ThumbnailUtils
import android.net.Uri
import android.os.Binder
import android.os.Build
import android.os.Bundle
import android.os.CancellationSignal
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.os.Process
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.util.LruCache
import android.util.Size
import android.webkit.MimeTypeMap
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileNotFoundException
import java.nio.file.Files
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
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

        private const val DEFAULT_THUMBNAIL_SIZE_PX = 512
        private const val THUMBNAIL_CACHE_SIZE_BYTES = 16 * 1024 * 1024

        private val thumbnailCache = object : LruCache<String, ByteArray>(THUMBNAIL_CACHE_SIZE_BYTES) {
            override fun sizeOf(key: String, value: ByteArray): Int = value.size
        }
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

    private fun mimeTypeOf(file: File): String? =
        MimeTypeMap.getSingleton().getMimeTypeFromExtension(file.extension.lowercase())

    private fun isImageFile(file: File) = mimeTypeOf(file)?.startsWith("image/") == true
    private fun isVideoFile(file: File) = mimeTypeOf(file)?.startsWith("video/") == true

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

    // Real thumbnails for images and video, generated here since only this process has direct
    // filesystem access to the source file — the server side (ContentResolverDataTree) just
    // calls openTypedAssetFileDescriptor(uri, "image/*", ...) and streams back whatever we
    // return here, the same mechanism ContentResolver.loadThumbnail() itself uses.
    override fun openTypedAssetFile(
        uri: Uri,
        mimeTypeFilter: String,
        opts: Bundle?,
        signal: CancellationSignal?
    ): AssetFileDescriptor {
        checkCaller()
        if (mimeTypeFilter != "image/*" && mimeTypeFilter != "*/*") {
            throw FileNotFoundException("Unsupported mime type filter: $mimeTypeFilter")
        }
        val file = resolveFile(uri)
        if (!file.isFile) {
            throw FileNotFoundException("Not a file: $uri")
        }
        val sizePx = extractSizePx(opts)
        val bytes = cachedThumbnail(file, sizePx)
            ?: throw FileNotFoundException("Could not generate a thumbnail for: $uri")

        // Write-then-unlink: the fd keeps the underlying inode readable after delete(), so this
        // leaves no temp file behind without needing pipe/thread plumbing for a one-shot JPEG.
        val tempFile = File.createTempFile("thumb", ".jpg", context!!.cacheDir)
        tempFile.writeBytes(bytes)
        val pfd = ParcelFileDescriptor.open(tempFile, ParcelFileDescriptor.MODE_READ_ONLY)
        tempFile.delete()
        return AssetFileDescriptor(pfd, 0, bytes.size.toLong())
    }

    private fun extractSizePx(opts: Bundle?): Int {
        // Size isn't Parcelable — Bundle has dedicated put/getSize methods for it.
        val size = opts?.getSize(ContentResolver.EXTRA_SIZE)
        return size?.let { maxOf(it.width, it.height) } ?: DEFAULT_THUMBNAIL_SIZE_PX
    }

    private fun cachedThumbnail(file: File, sizePx: Int): ByteArray? {
        val key = "${file.absolutePath}:$sizePx:${file.lastModified()}"
        thumbnailCache.get(key)?.let { return it }
        val bitmap = generateThumbnail(file, sizePx) ?: return null
        val bytes = bitmap.toJpegBytes()
        thumbnailCache.put(key, bytes)
        return bytes
    }

    private fun Bitmap.toJpegBytes(): ByteArray {
        val buffer = ByteArrayOutputStream()
        compress(Bitmap.CompressFormat.JPEG, 80, buffer)
        recycle()
        return buffer.toByteArray()
    }

    private fun generateThumbnail(file: File, sizePx: Int): Bitmap? = runCatching {
        when {
            isVideoFile(file) -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ThumbnailUtils.createVideoThumbnail(file, Size(sizePx, sizePx), null)
            } else {
                @Suppress("DEPRECATION")
                ThumbnailUtils.createVideoThumbnail(file.path, MediaStore.Video.Thumbnails.MINI_KIND)
            }

            isImageFile(file) -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ThumbnailUtils.createImageThumbnail(file, Size(sizePx, sizePx), null)
            } else {
                val bitmap = BitmapFactory.decodeFile(file.path)
                    ?: throw FileNotFoundException("Failed to decode image: ${file.path}")
                ThumbnailUtils.extractThumbnail(bitmap, sizePx, sizePx)
            }

            else -> null
        }
    }.getOrNull()

    private fun videoDurationMs(file: File): Long? {
        val mmr = MediaMetadataRetriever()
        return try {
            mmr.setDataSource(file.path)
            mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
        } catch (_: Exception) {
            null
        } finally {
            // release(), not close()/use{} — close() was only added in API 29, and this provider
            // supports minSdk 26.
            mmr.release()
        }
    }

    // Generic hook: whatever this provider wants to attach to an entry (currently just video
    // duration) goes through COLUMN_META as a single JSON-encoded map, rather than growing the
    // cursor contract with one dedicated column per metadata kind.
    private fun metaFor(file: File): Map<String, String>? {
        if (file.isDirectory) return null
        val meta = mutableMapOf<String, String>()
        if (isVideoFile(file)) {
            videoDurationMs(file)?.let { meta[MetaKeys.DURATION] = it.milliseconds.formatClock() }
        }
        return meta.ifEmpty { null }
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
            COLUMN_LAST_MODIFIED,
            COLUMN_META
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
        COLUMN_META -> metaFor(file)?.let { encodeEntryMeta(it) }
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
