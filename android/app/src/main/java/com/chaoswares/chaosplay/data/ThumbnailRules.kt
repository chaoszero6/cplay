package com.chaoswares.chaosplay.data

import java.util.concurrent.ConcurrentHashMap

/**
 * Resolves the thumbnail for a video, pure logic plus a tiny cache key.
 *
 * Source order:
 *  1. A sidecar image next to the video: `movie.mp4` finds `movie.jpg`,
 *     `movie.png`, `movie.webp`, `movie.jpeg`.
 *  2. A file generated from the video itself by [VideoThumbnailer] into the
 *     app cache (`<cacheDir>/thumbs/<id>_<mtime>.jpg`). The stamp in the name
 *     is the file's date-modified, so re-encoding a file invalidates it.
 *
 * All lookups are cheap because generated thumbs are remembered in a
 * process-wide map: a lazy grid asks for the same item many times per scroll,
 * and a ContentResolver query per request would make scrolling stutter.
 */
object ThumbnailRules {

    private val imageExtensions = listOf("jpg", "jpeg", "png", "webp")

    /** Directory that holds generated thumbnails, relative to the cache dir. */
    const val CACHE_DIR = "thumbs"

    /** Thumbnail width in pixels; a 16:9 card at 2 columns is ~700 px. */
    const val TARGET_WIDTH = 640

    /**
     * Candidate sidecar paths for a video path, best first. Returns an empty
     * list when the path has no directory or no stem.
     */
    fun sidecarCandidates(videoPath: String): List<String> {
        val p = videoPath.trim()
        if (p.isBlank()) return emptyList()
        val slash = p.lastIndexOf('/')
        if (slash <= 0) return emptyList()
        val dir = p.substring(0, slash)
        val name = p.substring(slash + 1)
        val stem = name.substringBeforeLast('.', name)
        if (stem.isBlank()) return emptyList()
        return imageExtensions.map { "$dir/$stem.$it" }
    }

    /** File name for a generated thumbnail, stamped with the video's mtime. */
    fun cacheFileName(videoId: Long, dateModifiedSec: Long): String =
        "${videoId}_${dateModifiedSec}.jpg"

    /** True when [dateModifiedSec] is recent enough that no thumb is worth writing. */
    fun needsRegeneration(thumbModifiedSec: Long, videoDateModifiedSec: Long): Boolean =
        thumbModifiedSec < videoDateModifiedSec

    /**
     * Process-wide memo of generated thumbnail paths, keyed by video id and
     * mtime. Only ever holds the resolved file path, never a bitmap, so it
     * cannot leak memory into the tens of MB.
     */
    private val generated = ConcurrentHashMap<String, String>()

    fun memoKey(videoId: Long, dateModifiedSec: Long): String = "$videoId@$dateModifiedSec"

    fun memoised(videoId: Long, dateModifiedSec: Long): String? =
        generated[memoKey(videoId, dateModifiedSec)]

    fun memoise(videoId: Long, dateModifiedSec: Long, path: String) {
        generated[memoKey(videoId, dateModifiedSec)] = path
    }

    /** Drops every memo entry; used when a scan discovers changed files. */
    fun clearMemo() = generated.clear()
}
