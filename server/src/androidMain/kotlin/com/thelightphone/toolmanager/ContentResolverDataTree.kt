package com.thelightphone.toolmanager

import android.content.ContentResolver
import android.content.ContentValues
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.util.Size
import com.thelightphone.toolmanager.datatree.CachingDataTree
import com.thelightphone.toolmanager.datatree.WriteCheck
import com.thelightphone.toolmanager.datatree.WriteTarget
import com.thelightphone.toolmanager.datatree.entryTypeForName
import java.io.InputStream
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

// Routes requests to a third-party tool's LightFileProvider. Instantiated by
// DiscoveredToolsBranchProvider, one per leaf declared in that tool's ClientToolManifest.
class ContentResolverDataTree(
    private val contentResolver: ContentResolver,
    private val authority: String,
    // Subpath, relative to the provider's own root
    private val basePath: Path = Paths.get("."),
    readOnly: Boolean = false,
    showHiddenFiles: Boolean = false,
    cacheTtl: Duration = 30.seconds,
    private val thumbnailSizePx: Int = 512,
    timeNow: () -> Instant = { Clock.System.now() }
) : CachingDataTree(readOnly, showHiddenFiles, cacheTtl, timeNow) {

    private fun pathToUri(path: Path): Uri {
        val pathStr = basePath.resolve(path).normalize().toString()
        val cleanPath = if (pathStr == ".") "" else pathStr
        return Uri.Builder()
            .scheme("content")
            .authority(authority)
            .path(cleanPath)
            .build()
    }

    override fun listEntries(path: Path): Result<List<Entry>> = runCatching {
        val uri = pathToUri(path)
        val dirPathStr = path.normalize().toString().let { if (it == ".") "" else it }

        val entries = mutableListOf<Entry>()
        contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val nameCol = cursor.getColumnIndexOrThrow(OpenableColumns.DISPLAY_NAME)
            val sizeCol = cursor.getColumnIndexOrThrow(OpenableColumns.SIZE)
            val isDirCol = cursor.getColumnIndexOrThrow(COLUMN_IS_DIRECTORY)
            val lastModCol = cursor.getColumnIndexOrThrow(COLUMN_LAST_MODIFIED)
            val metaCol = cursor.getColumnIndex(COLUMN_META)
            while (cursor.moveToNext()) {
                val name = cursor.getString(nameCol) ?: continue
                val isDirectory = cursor.getInt(isDirCol) == 1
                val size = if (isDirectory || cursor.isNull(sizeCol)) 0L else cursor.getLong(sizeCol)
                val lastModified = if (cursor.isNull(lastModCol)) 0L else cursor.getLong(lastModCol)
                val entryPath = if (dirPathStr.isEmpty()) name else "$dirPathStr/$name"
                val type = if (isDirectory) EntryType.Directory else entryTypeForName(name)
                val meta = if (metaCol >= 0 && !cursor.isNull(metaCol)) {
                    runCatching { decodeEntryMeta(cursor.getString(metaCol)) }.getOrNull()
                } else {
                    null
                }

                entries.add(
                    Entry(
                        type = type,
                        title = name,
                        path = entryPath,
                        lastModified = lastModified,
                        size = size,
                        meta = meta
                    )
                )
            }
        } ?: throw NoSuchElementException("Path not found: $path")

        entries
    }

    override fun openRead(filePath: Path): Result<InputStream> = runCatching {
        val uri = pathToUri(filePath)
        contentResolver.openInputStream(uri)
            ?: throw NoSuchElementException("Could not open: $filePath")
    }

    override fun openWrite(filePath: Path): Result<WriteTarget> = runCatching {
        val uri = pathToUri(filePath)
        val stream = contentResolver.openOutputStream(uri)
            ?: throw IllegalStateException("Could not open for writing: $filePath")
        WriteTarget(
            outputStream = stream,
            onRollback = { contentResolver.delete(uri, null, null) }
        )
    }

    override suspend fun getThumbnailBytes(filePath: Path, type: EntryType): Result<InputStream> {
        if (type != EntryType.Image && type != EntryType.Video) {
            return Result.failure(UnsupportedOperationException("Thumbnails not supported for $type"))
        }
        val thumbnail = runCatching {
            val uri = pathToUri(filePath)
            val extras = Bundle().apply {
                putSize(ContentResolver.EXTRA_SIZE, Size(thumbnailSizePx, thumbnailSizePx))
            }
            contentResolver.openTypedAssetFileDescriptor(uri, "image/*", extras, null)?.createInputStream()
                ?: throw NoSuchElementException("No thumbnail available: $filePath")
        }

        return if (thumbnail.isSuccess || type == EntryType.Video) thumbnail else getBytes(filePath)
    }

    override fun performCheckWrite(filePath: Path): WriteCheck {
        val uri = pathToUri(filePath)
        val cursor = runCatching {
            contentResolver.query(
                uri,
                arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE, COLUMN_IS_DIRECTORY),
                null, null, null
            )
        }.getOrElse {
            return WriteCheck.InvalidPath
        }

        // path doesn't exist
        if (cursor == null) {
            val parentPath = filePath.parent ?: Paths.get(".")
            val parentCursor = try {
                contentResolver.query(pathToUri(parentPath), arrayOf(COLUMN_IS_DIRECTORY), null, null, null)
            } catch (_: Exception) {
                null
            }
            return if (parentCursor != null) {
                parentCursor.close()
                WriteCheck.Safe
            } else {
                WriteCheck.InvalidPath
            }
        }

        return cursor.use {
            if (!it.moveToFirst()) {
                // Cursor returned but empty — shouldn't happen with the updated provider,
                // but treat as non-existent. Parent must be valid since query succeeded.
                WriteCheck.Safe
            } else {
                val isDirCol = it.getColumnIndexOrThrow(COLUMN_IS_DIRECTORY)
                val isDirectory = it.getInt(isDirCol) == 1
                if (isDirectory) {
                    WriteCheck.DirectoryExists
                } else {
                    val name = it.getString(it.getColumnIndexOrThrow(OpenableColumns.DISPLAY_NAME))
                    val sizeCol = it.getColumnIndexOrThrow(OpenableColumns.SIZE)
                    val size = if (it.isNull(sizeCol)) 0L else it.getLong(sizeCol)
                    WriteCheck.FileExists(
                        Entry(
                            type = entryTypeForName(name),
                            title = name,
                            path = filePath.toString(),
                            lastModified = 0L,
                            size = size
                        )
                    )
                }
            }
        }
    }

    override fun performDelete(filePath: Path): Result<Int> = runCatching {
        val uri = pathToUri(filePath)
        contentResolver.delete(uri, null, null)
    }

    override fun performRename(filePath: Path, newName: String): Result<Boolean> = runCatching {
        val uri = pathToUri(filePath)
        val values = ContentValues().apply {
            put(OpenableColumns.DISPLAY_NAME, newName)
        }
        contentResolver.update(uri, values, null, null) > 0
    }
}
