package com.chaoswares.chaosplay.data

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.util.Size
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

/**
 * Extracts a poster frame for a video and caches it as a JPEG.
 *
 * Why this exists: MediaStore does not create thumbnails for files it did not
 * index through its own scanner (files copied over MTP, adb or downloaded
 * straight into a folder often have none), so asking MediaStore for a thumb
 * returns nothing for exactly the files a user is likely to have. Decoding
 * the first meaningful frame ourselves always produces an image.
 *
 * Cost control:
 *  - frames are decoded sequentially behind a permit limit, so a grid of 60
 *    cards cannot start 60 decoders at once,
 *  - only one retriever is reused per batch (constructing one opens the
 *    container parser each time),
 *  - the result is written to the cache dir, so the second visit is a file
 *    read rather than a decode,
 *  - the seek point is 10% into the file (capped at 30 s) rather than 0 s,
 *    because the first frame of phone video is usually black or a pocket
 *    shot of the lens cap.
 */
class VideoThumbnailer(private val context: Context) {

    private val dir: File by lazy {
        File(context.cacheDir, ThumbnailRules.CACHE_DIR).apply { mkdirs() }
    }

    /** Two concurrent decoders: enough to keep a fast scroll fed, low enough to stay smooth. */
    private val permits = Semaphore(permits = 2)

    /**
     * Returns a File for the thumbnail of [videoId]/[uri], generating it if
     * needed. Returns null when the frame cannot be decoded (audio-only file,
     * unsupported container, corrupt file) so the UI can keep its placeholder.
     */
    suspend fun thumbnailFor(
        videoId: Long,
        uri: String,
        dateModifiedSec: Long,
        durationMs: Long
    ): File? = withContext(Dispatchers.IO) {
        ThumbnailRules.memoised(videoId, dateModifiedSec)?.let { path ->
            File(path).takeIf { it.isFile && it.length() > 0L }?.let { return@withContext it }
        }

        val out = File(dir, ThumbnailRules.cacheFileName(videoId, dateModifiedSec))
        if (out.isFile && out.length() > 0L) {
            ThumbnailRules.memoise(videoId, dateModifiedSec, out.absolutePath)
            return@withContext out
        }

        permits.withPermit {
            // Another card may have produced it while we waited for a permit.
            if (out.isFile && out.length() > 0L) {
                ThumbnailRules.memoise(videoId, dateModifiedSec, out.absolutePath)
                return@withPermit out
            }
            val bitmap = decodePosterFrame(uri, durationMs) ?: return@withPermit null
            runCatching {
                FileOutputStream(out).use { stream ->
                    bitmap.compress(Bitmap.CompressFormat.JPEG, 82, stream)
                }
            }.getOrElse {
                out.delete()
                return@withPermit null
            }
            bitmap.recycle()
            ThumbnailRules.memoise(videoId, dateModifiedSec, out.absolutePath)
            out
        }
    }

    fun decodePosterFrame(uri: String, durationMs: Long): Bitmap? {
        val retriever = MediaMetadataRetriever()
        return try {
            // App-private file: setDataSource(path) is cheaper and does not
            // need the URI to be resolvable through the app's grants. The
            // MediaStore content URI is only used when there is no path.
            val path = if (uri.startsWith("/")) uri else null
            if (path != null) {
                retriever.setDataSource(path)
            } else {
                retriever.setDataSource(context, Uri.parse(uri))
            }
            val seekUs = posterSeekUs(durationMs)
            // API 27+ can ask for a scaled frame, which decodes straight into
            // a small bitmap instead of a full 4K frame then scaling it down.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                retriever.getScaledFrameAtTime(
                    seekUs,
                    MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
                    ThumbnailRules.TARGET_WIDTH,
                    ThumbnailRules.TARGET_WIDTH * 9 / 16
                )
                    ?: retriever.getFrameAtTime(seekUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
            } else {
                retriever.getFrameAtTime(seekUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
            }
        } catch (_: Exception) {
            null
        } finally {
            runCatching { retriever.release() }
        }
    }

    /** Poster seek point: 10% in, capped at 30 s, 0 for very short clips. */
    fun posterSeekUs(durationMs: Long): Long {
        if (durationMs <= 0L) return 0L
        val target = (durationMs * 0.10).toLong().coerceAtMost(30_000L)
        return target * 1_000L
    }

    /** Removes cached thumbs whose source file no longer exists. */
    suspend fun prune(validIds: Set<Long>) = withContext(Dispatchers.IO) {
        dir.listFiles()?.forEach { file ->
            val id = file.name.substringBefore('_').toLongOrNull()
            if (id == null || id !in validIds) file.delete()
        }
        val keep = validIds.map { it.toString() }.toSet()
        ThumbnailRules.clearMemo()
        // Re-memoise what survived so the next library visit stays a file read.
        dir.listFiles()?.forEach { file ->
            val id = file.name.substringBefore('_').toLongOrNull() ?: return@forEach
            val stamp = file.name.substringAfter('_').substringBefore('.').toLongOrNull() ?: return@forEach
            if (id.toString() in keep) ThumbnailRules.memoise(id, stamp, file.absolutePath)
        }
        Unit
    }

    /** Size hint for Coil so it never upscales a cached poster. */
    fun sizeHint(): Size = Size(ThumbnailRules.TARGET_WIDTH, ThumbnailRules.TARGET_WIDTH * 9 / 16)
}
