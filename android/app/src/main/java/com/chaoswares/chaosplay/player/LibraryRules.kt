package com.chaoswares.chaosplay.player

import com.chaoswares.chaosplay.data.VideoItem

/**
 * Library grouping, sorting and resume rules, spec section 4.1:
 * "Folder view shows only folders that contain videos, with counts",
 * "Different colors for last-watched and finished videos",
 * "Resume position for every video", "new tag for recently added files",
 * "Continue watching row at the top".
 */
object LibraryRules {

    /** A folder containing videos, shown in the Folders tab. */
    data class FolderSummary(
        val path: String,
        val name: String,
        val videoCount: Int,
        val totalBytes: Long
    ) {
        val sizeLabel: String get() = VideoItem.formatSize(totalBytes)
    }

    enum class SortOrder(val label: String) {
        DATE_ADDED_DESC("Newest first"),
        DATE_ADDED_ASC("Oldest first"),
        TITLE_ASC("Title (A-Z)"),
        TITLE_DESC("Title (Z-A)"),
        DURATION_DESC("Longest first"),
        SIZE_DESC("Largest first");

        fun next(): SortOrder = entries[(ordinal + 1) % entries.size]
    }

    /** A video is "new" if it was added within this window (spec 7.5). */
    const val NEW_TAG_WINDOW_SEC = 7L * 24L * 60L * 60L

    /** Videos finished at or past this fraction of their duration. */
    const val FINISHED_THRESHOLD = 0.95f

    /** Below this fraction we count the video as not started. */
    const val STARTED_THRESHOLD = 0.01f

    /** A resume point is only useful if it is this far from both ends. */
    const val MIN_RESUME_MS = 5_000L

    /** Continue-watching row shows up to this many cards (spec 4.1: 10). */
    const val CONTINUE_WATCHING_LIMIT = 10

    fun sort(videos: List<VideoItem>, order: SortOrder): List<VideoItem> {
        val comparator = when (order) {
            SortOrder.DATE_ADDED_DESC -> compareByDescending<VideoItem> { it.dateAddedSec }
            SortOrder.DATE_ADDED_ASC -> compareBy<VideoItem> { it.dateAddedSec }
            SortOrder.TITLE_ASC -> compareBy { it.title.lowercase() }
            SortOrder.TITLE_DESC -> compareByDescending { it.title.lowercase() }
            SortOrder.DURATION_DESC -> compareByDescending { it.durationMs }
            SortOrder.SIZE_DESC -> compareByDescending { it.sizeBytes }
        }
        return videos.sortedWith(comparator.thenBy { it.id })
    }

    /**
     * Groups videos by folder, keeping only folders that actually contain
     * videos, sorted by name.
     */
    fun folders(videos: List<VideoItem>): List<FolderSummary> {
        return videos
            .groupBy { it.folder }
            .map { (path, items) ->
                FolderSummary(
                    path = path,
                    name = folderName(path),
                    videoCount = items.size,
                    totalBytes = items.sumOf { it.sizeBytes }
                )
            }
            .sortedBy { it.name.lowercase() }
    }

    /** Last path component, tolerating trailing slashes. */
    fun folderName(path: String): String {
        val trimmed = path.trimEnd('/')
        if (trimmed.isEmpty()) return path
        return trimmed.substringAfterLast('/').ifEmpty { trimmed }
    }

    fun isNew(video: VideoItem, nowSec: Long): Boolean {
        if (video.dateAddedSec <= 0L) return false
        return (nowSec - video.dateAddedSec) <= NEW_TAG_WINDOW_SEC
    }

    /**
     * Continue-watching list: videos started but not finished, most recent
     * first, capped at [limit] (spec 4.1 says up to 10 cards).
     */
    fun continueWatching(
        entries: List<WatchEntry>,
        limit: Int = CONTINUE_WATCHING_LIMIT
    ): List<WatchEntry> {
        return entries
            .filter { !it.isFinished() && it.isStarted() }
            .sortedByDescending { it.lastPlayedAtSec }
            .take(limit)
    }

    /**
     * The position playback should start from.
     * Returns 0 when there is nothing saved, or when the saved point is
     * too close to either end to be worth resuming from.
     */
    fun startPositionMs(savedMs: Long, durationMs: Long): Long {
        if (savedMs <= 0L) return 0L
        if (durationMs <= 0L) return savedMs
        if (savedMs < MIN_RESUME_MS) return 0L
        val remaining = durationMs - savedMs
        if (remaining < MIN_RESUME_MS) return 0L
        return savedMs
    }

    /** Videos finished but not rewatched should not show a resume badge. */
    fun shouldShowResume(savedMs: Long, durationMs: Long): Boolean {
        return startPositionMs(savedMs, durationMs) > 0L
    }
}

/**
 * Saved playback progress for one item.
 * Mirrors what a Room row would hold; kept Android-free for testing.
 */
data class WatchEntry(
    val uri: String,
    val positionMs: Long,
    val durationMs: Long,
    val lastPlayedAtSec: Long,
    val playCount: Int = 0
) {
    fun progressFraction(): Float {
        if (durationMs <= 0L) return 0f
        return (positionMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f)
    }

    fun isFinished(): Boolean =
        durationMs > 0L && progressFraction() >= LibraryRules.FINISHED_THRESHOLD

    fun isStarted(): Boolean =
        durationMs > 0L && progressFraction() >= LibraryRules.STARTED_THRESHOLD
}