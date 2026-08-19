package com.thelightphone.toolmanager

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.media.MediaScannerConnection
import android.media.ThumbnailUtils
import android.os.Build
import android.provider.MediaStore
import android.util.LruCache
import android.util.Size
import com.thelightphone.toolmanager.EntryType.*
import com.thelightphone.toolmanager.datatree.FileDataTree
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Path
import java.util.Locale
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds


class ThumbnailDataTree(
    private val root: File,
    private val context: Context,
    private val logger: Logger,
    defaultThumbnails: Map<EntryType, File>,
    readOnly: Boolean = false,
    maxCacheSizeBytes: Int = 16 * 1024 * 1024,
    private val thumbnailSizePx: Int = 512
) : FileDataTree(root, defaultThumbnails, readOnly = readOnly) {
    private val uploadedFiles = mutableSetOf<Path>()

    private val cache = object : LruCache<String, ByteArray>(maxCacheSizeBytes) {
        override fun sizeOf(key: String, value: ByteArray): Int = value.size
    }

    private fun Bitmap.toJpegBytes(): ByteArray {
        val buffer = ByteArrayOutputStream()
        compress(Bitmap.CompressFormat.JPEG, 80, buffer)
        recycle()
        return buffer.toByteArray()
    }

    private fun cachedThumbnail(filePath: Path, generate: (File) -> Bitmap): Result<InputStream>? {
        val file = root.resolve(filePath.toString()).takeIf { it.exists() } ?: return null
        val key = filePath.normalize().toString()
        cache.get(key)?.let {
            return Result.success(ByteArrayInputStream(it))
        }
        return runCatching {
            val bytes = generate(file).toJpegBytes()
            cache.put(key, bytes)
            ByteArrayInputStream(bytes)
        }
    }

    override fun getImageThumbnail(filePath: Path): Result<InputStream>? {
        return cachedThumbnail(filePath) { file ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ThumbnailUtils.createImageThumbnail(file, Size(thumbnailSizePx, thumbnailSizePx), null)
            } else {
                val bitmap = BitmapFactory.decodeFile(file.path)
                    ?: throw IOException("Failed to decode image: ${file.path}")
                ThumbnailUtils.extractThumbnail(bitmap, thumbnailSizePx, thumbnailSizePx)
            }
        }
    }

    override fun getVideoThumbnail(filePath: Path): Result<InputStream>? {
        return cachedThumbnail(filePath) { file ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ThumbnailUtils.createVideoThumbnail(file, Size(thumbnailSizePx, thumbnailSizePx), null)
            } else {
                @Suppress("DEPRECATION")
                ThumbnailUtils.createVideoThumbnail(file.path, MediaStore.Video.Thumbnails.MINI_KIND)!!
            }
        }
    }

    override suspend fun notify(directoryPath: Path) {
        super.notify(directoryPath)
        if (uploadedFiles.isEmpty()) return
        MediaScannerConnection.scanFile(
            context,
            uploadedFiles.map { root.resolve(it.toString()).absolutePath }.toTypedArray(),
            null
        ) { _, _ ->
            uploadedFiles.clear()
        }
    }

    override suspend fun <T> writeBytes(
        filePath: Path,
        block: suspend (OutputStream) -> T
    ): Result<T> {
        return super.writeBytes(filePath, block).also {
            if (it.isSuccess) {
                uploadedFiles.add(filePath)
            }
        }
    }

    private fun Entry.fetchVideoMeta(): Map<String, String> {
        val duration = MediaMetadataRetriever().use { mmr ->
            try {
                mmr.setDataSource(path)
                mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
            } catch (e: Exception) {
                logger.reportError("ThumbnailFileTree", e, "Error fetching video metadata")
                null
            }?.milliseconds
        }
        // if it failed, still populate the cache so we don't keep looking for it
        if (duration == null) return emptyMap()
        return mapOf(
            MetaKeys.DURATION to duration.format()
        )
    }

    override fun getMeta(entry: Entry): Map<String, String>? {
        return when (entry.type) {
            Directory, GenericFile, Audio, Image, Text -> null
            Video -> entry.fetchVideoMeta()
        }
    }
}

private fun Duration.format(locale: Locale = Locale.US): String =
    toComponents { days, hours, minutes, seconds, _ ->
        when {
            days > 0 -> String.format(locale, "%d:%02d:%02d:%02d", days, hours, minutes, seconds)
            hours > 0 -> String.format(locale, "%02d:%02d:%02d", hours, minutes, seconds)
            else -> String.format(locale, "%02d:%02d", minutes, seconds)
        }
    }
