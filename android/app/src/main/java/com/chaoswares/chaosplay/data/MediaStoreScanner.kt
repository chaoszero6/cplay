package com.chaoswares.chaosplay.data

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Scans the device for video files via MediaStore, spec section 7.5:
 * "Folder list showing only folders that contain videos", "Fast SD card scan".
 *
 * The scan runs off the main thread, as required by the performance
 * checklist in section 7.6.
 */
class MediaStoreScanner(private val context: Context) {

    suspend fun scanVideos(): List<VideoItem> = withContext(Dispatchers.IO) {
        val collection = MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        val projection = arrayOf(
            MediaStore.Video.Media._ID,
            MediaStore.Video.Media.DISPLAY_NAME,
            MediaStore.Video.Media.DATA,
            MediaStore.Video.Media.BUCKET_DISPLAY_NAME,
            MediaStore.Video.Media.BUCKET_ID,
            MediaStore.Video.Media.DURATION,
            MediaStore.Video.Media.SIZE,
            MediaStore.Video.Media.WIDTH,
            MediaStore.Video.Media.HEIGHT,
            MediaStore.Video.Media.DATE_ADDED,
            MediaStore.Video.Media.DATE_MODIFIED,
            MediaStore.Video.Media.MIME_TYPE
        )

        val results = mutableListOf<VideoItem>()
        runCatching {
            context.contentResolver.query(
                collection, projection, null, null,
                "${MediaStore.Video.Media.DATE_ADDED} DESC"
            )?.use { cursor ->
                val idCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
                val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME)
                val dataCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DATA)
                val durationCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DURATION)
                val sizeCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.SIZE)
                val widthCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.WIDTH)
                val heightCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.HEIGHT)
                val dateCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DATE_ADDED)
                val modifiedCol = cursor.getColumnIndex(MediaStore.Video.Media.DATE_MODIFIED)
                val mimeCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.MIME_TYPE)


                while (cursor.moveToNext()) {
                    val id = cursor.getLong(idCol)
                    val path = cursor.getString(dataCol).orEmpty()
                    val folder = path.substringBeforeLast('/', "")
                    val uri = ContentUris.withAppendedId(collection, id).toString()

                    results += VideoItem(
                        id = id,
                        uri = uri,
                        title = cursor.getString(nameCol).orEmpty(),
                        path = path,
                        folder = folder,
                        durationMs = cursor.getLong(durationCol),
                        sizeBytes = cursor.getLong(sizeCol),
                        width = cursor.getInt(widthCol),
                        height = cursor.getInt(heightCol),
                        dateAddedSec = cursor.getLong(dateCol),
                        dateModifiedSec = if (modifiedCol >= 0) cursor.getLong(modifiedCol) else 0L,
                        mimeType = cursor.getString(mimeCol).orEmpty()
                    )
                }
            }
        }
        results
    }

    /** Resolves a single URI to an item, used when opening from another app. */
    suspend fun itemForUri(uri: String): VideoItem? = withContext(Dispatchers.IO) {
        if (!uri.startsWith("content://")) return@withContext null
        runCatching {
            context.contentResolver.query(Uri.parse(uri), null, null, null, null)?.use { cursor ->
                if (!cursor.moveToFirst()) return@withContext null
                val idCol = cursor.getColumnIndex(MediaStore.Video.Media._ID)
                val nameCol = cursor.getColumnIndex(MediaStore.Video.Media.DISPLAY_NAME)
                val dataCol = cursor.getColumnIndex(MediaStore.Video.Media.DATA)
                val durationCol = cursor.getColumnIndex(MediaStore.Video.Media.DURATION)
                val sizeCol = cursor.getColumnIndex(MediaStore.Video.Media.SIZE)
                val modifiedCol = cursor.getColumnIndex(MediaStore.Video.Media.DATE_MODIFIED)
                val path = cursor.getString(dataCol).orEmpty()
                VideoItem(
                    id = if (idCol >= 0) cursor.getLong(idCol) else 0L,
                    uri = uri,
                    title = if (nameCol >= 0) cursor.getString(nameCol).orEmpty() else "Video",
                    path = path,
                    folder = path.substringBeforeLast('/', ""),
                    durationMs = if (durationCol >= 0) cursor.getLong(durationCol) else 0L,
                    sizeBytes = if (sizeCol >= 0) cursor.getLong(sizeCol) else 0L,
                    dateModifiedSec = if (modifiedCol >= 0) cursor.getLong(modifiedCol) else 0L
                )
            }
        }.getOrNull()
    }
}