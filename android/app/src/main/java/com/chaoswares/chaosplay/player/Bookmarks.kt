package com.chaoswares.chaosplay.player

import com.chaoswares.chaosplay.util.FieldCodec

/** A saved point in a video, spec section 3: "Bookmarks / favorite points in a video". */
data class Bookmark(
    val uri: String,
    val positionMs: Long,
    val label: String,
    val createdAtSec: Long = 0L
)

object BookmarkRules {

    /** Two bookmarks closer than this are the same point; the second press is ignored. */
    const val DEDUPE_WINDOW_MS = 1_500L

    const val MAX_PER_VIDEO = 100

    /** Bookmarks of one video, earliest first. */
    fun forUri(all: List<Bookmark>, uri: String): List<Bookmark> =
        all.filter { it.uri == uri }.sortedBy { it.positionMs }

    /** Default label: the timestamp, e.g. "Bookmark at 12:34". */
    fun defaultLabel(positionMs: Long): String = "Bookmark at ${TimeFormat.format(positionMs)}"

    /**
     * Adds a bookmark unless one already sits within [DEDUPE_WINDOW_MS],
     * keeping at most [MAX_PER_VIDEO] per video (oldest dropped).
     */
    fun add(all: List<Bookmark>, bookmark: Bookmark): List<Bookmark> {
        val position = bookmark.positionMs.coerceAtLeast(0L)
        val sameVideo = all.filter { it.uri == bookmark.uri }
        if (sameVideo.any { kotlin.math.abs(it.positionMs - position) < DEDUPE_WINDOW_MS }) return all
        val label = bookmark.label.trim().ifEmpty { defaultLabel(position) }
        var result = all + bookmark.copy(positionMs = position, label = label)
        val mine = result.filter { it.uri == bookmark.uri }
        if (mine.size > MAX_PER_VIDEO) {
            val oldest = mine.minByOrNull { it.createdAtSec }
            result = result - oldest!!
        }
        return result
    }

    fun remove(all: List<Bookmark>, uri: String, positionMs: Long): List<Bookmark> =
        all.filterNot { it.uri == uri && it.positionMs == positionMs }

    fun rename(all: List<Bookmark>, uri: String, positionMs: Long, label: String): List<Bookmark> =
        all.map {
            if (it.uri == uri && it.positionMs == positionMs) {
                it.copy(label = label.trim().ifEmpty { defaultLabel(positionMs) })
            } else it
        }

    /** Next bookmark strictly after [positionMs] (+ a small slack), or null. */
    fun nextAfter(bookmarks: List<Bookmark>, positionMs: Long): Bookmark? =
        bookmarks.sortedBy { it.positionMs }.firstOrNull { it.positionMs > positionMs + 500L }

    /** Bookmark before [positionMs] (with slack so a press right after a jump goes further back). */
    fun previousBefore(bookmarks: List<Bookmark>, positionMs: Long): Bookmark? =
        bookmarks.sortedBy { it.positionMs }.lastOrNull { it.positionMs < positionMs - 2_000L }

    fun encode(all: List<Bookmark>): String = FieldCodec.encode(
        all.map { listOf(it.uri, it.positionMs.toString(), it.label, it.createdAtSec.toString()) }
    )

    fun decode(raw: String): List<Bookmark> = FieldCodec.decode(raw).mapNotNull { r ->
        val uri = r.getOrNull(0)?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
        val position = r.getOrNull(1)?.toLongOrNull() ?: return@mapNotNull null
        Bookmark(uri, position, r.getOrNull(2).orEmpty(), r.getOrNull(3)?.toLongOrNull() ?: 0L)
    }
}
