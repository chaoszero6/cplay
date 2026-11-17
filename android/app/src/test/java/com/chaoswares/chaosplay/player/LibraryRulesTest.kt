package com.chaoswares.chaosplay.player

import com.chaoswares.chaosplay.data.VideoItem
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * LibraryRules: sorting, folder grouping, new/finished tagging, resume
 * decisions and the continue-watching list.
 */
class LibraryRulesTest {

    private fun video(
        id: Long,
        title: String,
        folder: String = "/storage/Movies",
        durationMs: Long = 600_000L,
        sizeBytes: Long = 1_000_000L,
        dateAddedSec: Long = 1_000_000L
    ) = VideoItem(
        id = id,
        uri = "content://media/$id",
        title = title,
        path = "$folder/$title",
        folder = folder,
        durationMs = durationMs,
        sizeBytes = sizeBytes,
        dateAddedSec = dateAddedSec
    )

    // ---- sorting ----

    @Test
    fun `sort by newest puts the highest date first`() {
        val videos = listOf(
            video(1, "a.mkv", dateAddedSec = 100L),
            video(2, "b.mkv", dateAddedSec = 300L),
            video(3, "c.mkv", dateAddedSec = 200L)
        )
        val sorted = LibraryRules.sort(videos, LibraryRules.SortOrder.DATE_ADDED_DESC)
        assertThat(sorted.map { it.id }).containsExactly(2L, 3L, 1L).inOrder()
    }

    @Test
    fun `sort by oldest puts the lowest date first`() {
        val videos = listOf(
            video(1, "a.mkv", dateAddedSec = 100L),
            video(2, "b.mkv", dateAddedSec = 300L)
        )
        val sorted = LibraryRules.sort(videos, LibraryRules.SortOrder.DATE_ADDED_ASC)
        assertThat(sorted.map { it.id }).containsExactly(1L, 2L).inOrder()
    }

    @Test
    fun `title sorting is case insensitive`() {
        val videos = listOf(
            video(1, "Zebra.mkv"),
            video(2, "apple.mkv"),
            video(3, "Mango.mkv")
        )
        val sorted = LibraryRules.sort(videos, LibraryRules.SortOrder.TITLE_ASC)
        assertThat(sorted.map { it.title })
            .containsExactly("apple.mkv", "Mango.mkv", "Zebra.mkv").inOrder()
    }

    @Test
    fun `title sorting descending reverses`() {
        val videos = listOf(video(1, "apple.mkv"), video(2, "zebra.mkv"))
        val sorted = LibraryRules.sort(videos, LibraryRules.SortOrder.TITLE_DESC)
        assertThat(sorted.map { it.title })
            .containsExactly("zebra.mkv", "apple.mkv").inOrder()
    }

    @Test
    fun `duration and size sorting put the largest first`() {
        val videos = listOf(
            video(1, "a.mkv", durationMs = 100L, sizeBytes = 500L),
            video(2, "b.mkv", durationMs = 900L, sizeBytes = 100L),
            video(3, "c.mkv", durationMs = 500L, sizeBytes = 900L)
        )
        assertThat(
            LibraryRules.sort(videos, LibraryRules.SortOrder.DURATION_DESC).map { it.id }
        ).containsExactly(2L, 3L, 1L).inOrder()

        assertThat(
            LibraryRules.sort(videos, LibraryRules.SortOrder.SIZE_DESC).map { it.id }
        ).containsExactly(3L, 1L, 2L).inOrder()
    }

    @Test
    fun `sorting a tie falls back to id so order is stable`() {
        val videos = listOf(
            video(2, "same.mkv", dateAddedSec = 100L),
            video(1, "same.mkv", dateAddedSec = 100L)
        )
        val sorted = LibraryRules.sort(videos, LibraryRules.SortOrder.DATE_ADDED_DESC)
        assertThat(sorted.map { it.id }).containsExactly(1L, 2L).inOrder()
    }

    @Test
    fun `sort order cycles through every option`() {
        var order = LibraryRules.SortOrder.DATE_ADDED_DESC
        val seen = mutableListOf(order)
        repeat(LibraryRules.SortOrder.entries.size - 1) {
            order = order.next()
            seen += order
        }
        assertThat(seen.toSet()).hasSize(LibraryRules.SortOrder.entries.size)
        assertThat(order.next()).isEqualTo(LibraryRules.SortOrder.DATE_ADDED_DESC)
    }

    // ---- folders ----

    @Test
    fun `folders only include ones with videos and count them`() {
        val videos = listOf(
            video(1, "a.mkv", folder = "/storage/Movies"),
            video(2, "b.mkv", folder = "/storage/Movies"),
            video(3, "c.mkv", folder = "/storage/Shows")
        )
        val folders = LibraryRules.folders(videos)
        assertThat(folders).hasSize(2)
        assertThat(folders.map { it.name })
            .containsExactly("Movies", "Shows").inOrder()
        assertThat(folders.first { it.name == "Movies" }.videoCount).isEqualTo(2)
    }

    @Test
    fun `folder totals sum the file sizes`() {
        val videos = listOf(
            video(1, "a.mkv", folder = "/m", sizeBytes = 1_500L),
            video(2, "b.mkv", folder = "/m", sizeBytes = 2_500L)
        )
        val folder = LibraryRules.folders(videos).single()
        assertThat(folder.totalBytes).isEqualTo(4_000L)
    }

    @Test
    fun `no videos means no folders`() {
        assertThat(LibraryRules.folders(emptyList())).isEmpty()
    }

    @Test
    fun `folder name handles trailing slashes and roots`() {
        assertThat(LibraryRules.folderName("/storage/Movies")).isEqualTo("Movies")
        assertThat(LibraryRules.folderName("/storage/Movies/")).isEqualTo("Movies")
        assertThat(LibraryRules.folderName("Movies")).isEqualTo("Movies")
        assertThat(LibraryRules.folderName("/")).isEqualTo("/")
    }

    // ---- new tag ----

    @Test
    fun `videos added within the window are new`() {
        val now = 1_000_000L
        val fresh = video(1, "a.mkv", dateAddedSec = now - 3_600L)
        assertThat(LibraryRules.isNew(fresh, now)).isTrue()
    }

    @Test
    fun `videos older than the window are not new`() {
        val now = 1_000_000L
        val old = video(1, "a.mkv", dateAddedSec = now - LibraryRules.NEW_TAG_WINDOW_SEC - 1)
        assertThat(LibraryRules.isNew(old, now)).isFalse()
    }

    @Test
    fun `a video exactly at the window edge is still new`() {
        val now = 1_000_000L
        val edge = video(1, "a.mkv", dateAddedSec = now - LibraryRules.NEW_TAG_WINDOW_SEC)
        assertThat(LibraryRules.isNew(edge, now)).isTrue()
    }

    @Test
    fun `a video with no date is never new`() {
        val unknown = video(1, "a.mkv", dateAddedSec = 0L)
        assertThat(LibraryRules.isNew(unknown, 1_000_000L)).isFalse()
    }

    // ---- resume ----

    @Test
    fun `resume uses the saved position when it is mid-video`() {
        assertThat(LibraryRules.startPositionMs(60_000L, 600_000L)).isEqualTo(60_000L)
    }

    @Test
    fun `resume restarts when there is nothing saved`() {
        assertThat(LibraryRules.startPositionMs(0L, 600_000L)).isEqualTo(0L)
        assertThat(LibraryRules.startPositionMs(-1L, 600_000L)).isEqualTo(0L)
    }

    @Test
    fun `resume restarts when the saved point is right at the beginning`() {
        assertThat(LibraryRules.startPositionMs(1_000L, 600_000L)).isEqualTo(0L)
        assertThat(LibraryRules.startPositionMs(4_999L, 600_000L)).isEqualTo(0L)
    }

    @Test
    fun `resume restarts when the saved point is right at the end`() {
        // 4 s from the end is too close to be worth resuming into
        assertThat(LibraryRules.startPositionMs(596_000L, 600_000L)).isEqualTo(0L)
    }

    @Test
    fun `resume works when duration is unknown`() {
        assertThat(LibraryRules.startPositionMs(60_000L, 0L)).isEqualTo(60_000L)
    }

    @Test
    fun `resume badge shows only for a usable position`() {
        assertThat(LibraryRules.shouldShowResume(60_000L, 600_000L)).isTrue()
        assertThat(LibraryRules.shouldShowResume(0L, 600_000L)).isFalse()
        assertThat(LibraryRules.shouldShowResume(599_000L, 600_000L)).isFalse()
    }

    // ---- watch entries ----

    @Test
    fun `finished is true at or past the finish threshold`() {
        val finished = WatchEntry("u", positionMs = 950L, durationMs = 1_000L, lastPlayedAtSec = 1L)
        assertThat(finished.isFinished()).isTrue()
        val almost = WatchEntry("u", positionMs = 900L, durationMs = 1_000L, lastPlayedAtSec = 1L)
        assertThat(almost.isFinished()).isFalse()
    }

    @Test
    fun `started is false right at the beginning`() {
        val justStarted = WatchEntry("u", 5L, 1_000L, 1L)
        assertThat(justStarted.isStarted()).isFalse()
        val started = WatchEntry("u", 20L, 1_000L, 1L)
        assertThat(started.isStarted()).isTrue()
    }

    @Test
    fun `progress fraction is safe with unknown duration`() {
        val unknown = WatchEntry("u", 100L, 0L, 1L)
        assertThat(unknown.progressFraction()).isEqualTo(0f)
        assertThat(unknown.isFinished()).isFalse()
        assertThat(unknown.isStarted()).isFalse()
    }

    // ---- continue watching ----

    @Test
    fun `continue watching excludes finished and unstarted items`() {
        val entries = listOf(
            WatchEntry("a", 50L, 100L, 500L),   // started, not finished
            WatchEntry("b", 100L, 100L, 900L),  // finished
            WatchEntry("c", 0L, 100L, 700L)     // never started
        )
        val result = LibraryRules.continueWatching(entries)
        assertThat(result.map { it.uri }).containsExactly("a")
    }

    @Test
    fun `continue watching puts the most recent first`() {
        val entries = listOf(
            WatchEntry("a", 50L, 100L, 100L),
            WatchEntry("b", 50L, 100L, 900L),
            WatchEntry("c", 50L, 100L, 500L)
        )
        val result = LibraryRules.continueWatching(entries)
        assertThat(result.map { it.uri }).containsExactly("b", "c", "a").inOrder()
    }

    @Test
    fun `continue watching is capped at the limit`() {
        val entries = (1..20).map {
            WatchEntry("u$it", 50L, 100L, it.toLong())
        }
        val result = LibraryRules.continueWatching(entries, limit = 10)
        assertThat(result).hasSize(10)
        // Highest timestamps survive
        assertThat(result.first().uri).isEqualTo("u20")
    }

    @Test
    fun `continue watching of an empty list is empty`() {
        assertThat(LibraryRules.continueWatching(emptyList())).isEmpty()
    }
}