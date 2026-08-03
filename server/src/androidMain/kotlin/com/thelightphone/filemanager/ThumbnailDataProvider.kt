package com.thelightphone.filemanager

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaScannerConnection
import android.media.ThumbnailUtils
import android.util.LruCache
import android.util.Size
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Path


class ThumbnailDataProvider(
    private val root: File,
    private val context: Context,
    defaultThumbnails: Map<EntryType, File>,
    readOnly: Boolean = false,
    maxCacheSizeBytes: Int = 16 * 1024 * 1024,
    private val thumbnailSizePx: Int = 512
) : FileDataProvider(root, defaultThumbnails, readOnly = readOnly) {
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
            ThumbnailUtils.createImageThumbnail(file, Size(thumbnailSizePx, thumbnailSizePx), null)
        }
    }

    override fun getVideoThumbnail(filePath: Path): Result<InputStream>? {
        return cachedThumbnail(filePath) { file ->
            ThumbnailUtils.createVideoThumbnail(file, Size(thumbnailSizePx, thumbnailSizePx), null)
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
}
