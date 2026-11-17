package com.chaoswares.chaosplay.player

import com.chaoswares.chaosplay.data.VideoItem
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * LibraryTabs: the All videos | Folders | Recent | Favorites chip row, the
 * grid/list toggle, responsive columns, search, hiding, and the long-press
 * action menu required by spec section 4.1.
 */
class LibraryTabsTest {

    private fun video(
        id: Long,
        title: String = "video$id.mkv",
        folder: String = "/storage/Movies",
        dateAddedSec: Long = 1_000_000L
    ) = VideoItem(
        id = id,
        uri = "content://media/$id",
        title = title,
        path = "$folder/$title",
        folder = folder,
        durationMs = 60_000L,
        sizeBytes = 1_000L,
        dateAddedSec = dateAddedSec
    )

    // ---- the chip row itself ----

    @Test
    fun `the four chips are in the order the spec lists them`() {
        assertThat(LibraryTabs.Tab.entries.map { it.label })
            .containsExactly("All videos", "Folders", "Recent", "Favorites").inOrder()
    }

    @Test
    fun `chips cycle and wrap back to all videos`() {
        var tab = LibraryTabs.Tab.ALL
        val seen = mutableListOf(tab)
        repeat(LibraryTabs.Tab.entries.size - 1) {
            tab = tab.next()
            seen += tab
        }
        assertThat(seen.toSet()).hasSize(4)
        assertThat(tab.next()).isEqualTo(LibraryTabs.Tab.ALL)
    }

    @Test
    fun `every chip has empty-state copy`() {
        LibraryTabs.Tab.entries.forEach { tab ->
            assertThat(LibraryTabs.emptyMessage(tab)).isNotEmpty()
        }
        assertThat(LibraryTabs.emptyMessage(LibraryTabs.Tab.ALL))
            .isEqualTo("No videos found. Add a folder")
    }

    // ---- view mode ----

    @Test
    fun `view mode toggles between grid and list`() {
        assertThat(LibraryTabs.ViewMode.GRID.toggled()).isEqualTo(LibraryTabs.ViewMode.LIST)
        assertThat(LibraryTabs.ViewMode.LIST.toggled()).isEqualTo(LibraryTabs.ViewMode.GRID)
        assertThat(LibraryTabs.ViewMode.GRID.toggled().toggled())
            .isEqualTo(LibraryTabs.ViewMode.GRID)
    }

    @Test
    fun `grid is two columns on a phone and three on a tablet`() {
        assertThat(LibraryTabs.gridColumns(360)).isEqualTo(2)
        assertThat(LibraryTabs.gridColumns(411)).isEqualTo(2)
        assertThat(LibraryTabs.gridColumns(599)).isEqualTo(2)
        assertThat(LibraryTabs.gridColumns(600)).isEqualTo(3)
        assertThat(LibraryTabs.gridColumns(1280)).isEqualTo(3)
    }

    // ---- filtering ----

    @Test
    fun `all videos shows everything that is not hidden`() {
        val videos = listOf(video(1), video(2), video(3, folder = ""))
        val shown = LibraryTabs.filter(videos, LibraryTabs.Tab.ALL, nowSec = 9_999_999L)
        assertThat(shown.map { it.id }).containsExactly(1L, 2L, 3L)
    }

    @Test
    fun `hidden videos never appear in any tab`() {
        val videos = listOf(video(1), video(2), video(3))
        LibraryTabs.Tab.entries.forEach { tab ->
            val shown = LibraryTabs.filter(
                videos, tab, nowSec = 9_999_999L, hiddenIds = setOf(2L)
            )
            assertThat(shown.map { it.id }).doesNotContain(2L)
        }
    }

    @Test
    fun `folders tab keeps only videos that live in a folder`() {
        val videos = listOf(
            video(1, folder = "/storage/Movies"),
            video(2, folder = "")
        )
        val shown = LibraryTabs.filter(videos, LibraryTabs.Tab.FOLDERS, nowSec = 9_999_999L)
        assertThat(shown.map { it.id }).containsExactly(1L)
    }

    @Test
    fun `recent tab keeps only videos added inside the window`() {
        val now = 10_000_000L
        val videos = listOf(
            video(1, dateAddedSec = now - 60L),
            video(2, dateAddedSec = now - LibraryTabs.RECENT_WINDOW_SEC - 1),
            video(3, dateAddedSec = 0L)
        )
        val shown = LibraryTabs.filter(videos, LibraryTabs.Tab.RECENT, nowSec = now)
        assertThat(shown.map { it.id }).containsExactly(1L)
    }

    @Test
    fun `a video exactly at the recent window edge still counts`() {
        val now = 10_000_000L
        val edge = video(1, dateAddedSec = now - LibraryTabs.RECENT_WINDOW_SEC)
        assertThat(LibraryTabs.isRecent(edge, now)).isTrue()
    }

    @Test
    fun `a recent video with a clock skew in the future still counts`() {
        val now = 10_000_000L
        val future = video(1, dateAddedSec = now + 60L)
        assertThat(LibraryTabs.isRecent(future, now)).isTrue()
    }

    @Test
    fun `favorites tab keeps only favourite ids`() {
        val videos = listOf(video(1), video(2), video(3))
        val shown = LibraryTabs.filter(
            videos, LibraryTabs.Tab.FAVORITES, nowSec = 0L, favouriteIds = setOf(3L, 99L)
        )
        assertThat(shown.map { it.id }).containsExactly(3L)
    }

    @Test
    fun `favorites with nothing starred is empty`() {
        val videos = listOf(video(1))
        assertThat(LibraryTabs.filter(videos, LibraryTabs.Tab.FAVORITES, nowSec = 0L)).isEmpty()
    }

    // ---- search ----

    @Test
    fun `search matches titles case insensitively`() {
        val videos = listOf(
            video(1, title = "Inception.2010.mkv"),
            video(2, title = "interstellar.mkv"),
            video(3, title = "Joker.mkv")
        )
        val found = LibraryTabs.search(videos, "in")
        assertThat(found.map { it.id }).containsExactly(1L, 2L)
    }

    @Test
    fun `search ignores surrounding whitespace`() {
        val videos = listOf(video(1, title = "Joker.mkv"))
        assertThat(LibraryTabs.search(videos, "  joker  ")).hasSize(1)
    }

    @Test
    fun `blank search returns everything`() {
        val videos = listOf(video(1), video(2))
        assertThat(LibraryTabs.search(videos, "")).hasSize(2)
        assertThat(LibraryTabs.search(videos, "   ")).hasSize(2)
    }

    // ---- long press menu ----

    @Test
    fun `the long press menu carries every action the spec names`() {
        // The spec list is Play next, Add to playlist, Info, Rename, Share,
        // Delete, Hide. The menu also leads with the favourite toggle, which
        // the spec's Favorites chip implies but its action list omits.
        assertThat(LibraryTabs.menuFor(isFavourite = false).map { it.label }).containsExactly(
            "Add to favorites",
            "Play next",
            "Add to playlist",
            "Info",
            "Rename",
            "Share",
            "Delete",
            "Hide"
        ).inOrder()
    }

    @Test
    fun `the favourite row flips its wording without changing the menu order`() {
        assertThat(LibraryTabs.favouriteLabel(isFavourite = true)).isEqualTo("Remove from favorites")
        assertThat(LibraryTabs.favouriteLabel(isFavourite = false)).isEqualTo("Add to favorites")
        assertThat(LibraryTabs.menuFor(isFavourite = true))
            .containsExactlyElementsIn(LibraryTabs.menuFor(isFavourite = false))
            .inOrder()
    }
}
