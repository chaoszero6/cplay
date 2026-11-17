package com.chaoswares.chaosplay.player

import com.chaoswares.chaosplay.data.VideoItem

/**
 * Library filter tabs and view modes, spec section 4.1:
 * "Tabs or chips: All videos | Folders | Recent | Favorites",
 * "Top: search, sort, view toggle (grid/list), overflow menu",
 * "Play next, Add to playlist, Info, Rename, Share, Delete, Hide".
 *
 * All pure Kotlin so every rule is unit testable without an emulator.
 */
object LibraryTabs {

    /** The chip row across the top of the library. */
    enum class Tab(val label: String) {
        ALL("All videos"),
        FOLDERS("Folders"),
        RECENT("Recent"),
        FAVORITES("Favorites");

        fun next(): Tab = entries[(ordinal + 1) % entries.size]
    }

    /** Grid or list, toggled from the top bar (spec section 4.1). */
    enum class ViewMode(val label: String) {
        GRID("Grid"),
        LIST("List");

        fun toggled(): ViewMode = if (this == GRID) LIST else GRID
    }

    /** How many columns the grid uses on a phone (spec: 2 on phone). */
    const val PHONE_GRID_COLUMNS = 2

    /** Tablets and wide landscape screens (spec: 3 to 5 on tablet). */
    const val TABLET_GRID_COLUMNS = 3

    /** Width in dp above which the grid switches to the tablet column count. */
    const val TABLET_WIDTH_DP = 600

    /** "Recent" shows videos added within this window (7 days, spec 7.5). */
    const val RECENT_WINDOW_SEC = 7L * 24L * 60L * 60L

    /** Long-press / right-click menu entries, spec section 4.1 behaviour. */
    enum class ItemAction(val label: String) {
        PLAY("Play"),
        FAVORITES_TOGGLE("Add to favorites"),
        PLAY_NEXT("Play next"),
        ADD_TO_PLAYLIST("Add to playlist"),
        INFO("Info"),
        RENAME("Rename"),
        SHARE("Share"),
        DELETE("Delete"),
        HIDE("Hide")
    }

    /**
     * The long-press menu as shown to the user. [ItemAction.PLAY] is the
     * tap action and is not repeated in the menu; the favourite row picks
     * its wording from [favouriteLabel].
     */
    @Suppress("UNUSED_PARAMETER")
    fun menuFor(isFavourite: Boolean): List<ItemAction> = listOf(
        ItemAction.FAVORITES_TOGGLE,
        ItemAction.PLAY_NEXT,
        ItemAction.ADD_TO_PLAYLIST,
        ItemAction.INFO,
        ItemAction.RENAME,
        ItemAction.SHARE,
        ItemAction.DELETE,
        ItemAction.HIDE
    )

    /** Label for the favourite row, which flips with the current state. */
    fun favouriteLabel(isFavourite: Boolean): String =
        if (isFavourite) "Remove from favorites" else "Add to favorites"

    /**
     * Grid columns for the current width: 2 on a phone, 3 once we cross the
     * tablet breakpoint. Never returns less than 1.
     */
    fun gridColumns(widthDp: Int): Int {
        return if (widthDp >= TABLET_WIDTH_DP) TABLET_GRID_COLUMNS else PHONE_GRID_COLUMNS
    }

    /**
     * Applies a chip selection to the video list.
     *
     * - ALL: everything
     * - FOLDERS: handled by the caller as a folder list; returns the videos
     *   that belong to folders (i.e. all of them with a known folder)
     * - RECENT: added inside [RECENT_WINDOW_SEC] relative to [nowSec]
     * - FAVORITES: ids present in [favouriteIds]
     */
    fun filter(
        videos: List<VideoItem>,
        tab: Tab,
        nowSec: Long,
        favouriteIds: Set<Long> = emptySet(),
        hiddenIds: Set<Long> = emptySet()
    ): List<VideoItem> {
        val visible = videos.filterNot { it.id in hiddenIds }
        return when (tab) {
            Tab.ALL -> visible
            Tab.FOLDERS -> visible.filter { it.folder.isNotBlank() }
            Tab.RECENT -> visible.filter { isRecent(it, nowSec) }
            Tab.FAVORITES -> visible.filter { it.id in favouriteIds }
        }
    }

    /** True when a video was added inside the recent window. */
    fun isRecent(video: VideoItem, nowSec: Long): Boolean {
        if (video.dateAddedSec <= 0L) return false
        val age = nowSec - video.dateAddedSec
        // A timestamp slightly in the future means clock skew or a file
        // copied with a preserved mtime. Treat it as new rather than
        // silently dropping it from the Recent tab.
        return age <= RECENT_WINDOW_SEC
    }

    /** Search matches the title, case insensitively. */
    fun search(videos: List<VideoItem>, query: String): List<VideoItem> {
        if (query.isBlank()) return videos
        return videos.filter { it.title.contains(query.trim(), ignoreCase = true) }
    }

    /** Empty-state copy per tab, spec section 4.1 states. */
    fun emptyMessage(tab: Tab): String = when (tab) {
        Tab.ALL -> "No videos found. Add a folder"
        Tab.FOLDERS -> "No folders with videos yet"
        Tab.RECENT -> "Nothing new in the last 7 days"
        Tab.FAVORITES -> "No favorites yet. Long press a video to add one"
    }
}
