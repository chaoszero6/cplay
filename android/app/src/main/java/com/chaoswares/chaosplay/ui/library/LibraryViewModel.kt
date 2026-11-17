package com.chaoswares.chaosplay.ui.library

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.chaoswares.chaosplay.data.LibraryMetaStore
import com.chaoswares.chaosplay.data.MediaStoreScanner
import com.chaoswares.chaosplay.data.ResumeCodec
import com.chaoswares.chaosplay.data.SettingsStore
import com.chaoswares.chaosplay.data.VideoThumbnailer
import com.chaoswares.chaosplay.data.VideoItem
import com.chaoswares.chaosplay.player.LibraryRules
import com.chaoswares.chaosplay.player.LibraryTabs
import com.chaoswares.chaosplay.player.WatchEntry
import android.app.RecoverableSecurityException
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.IntentSender
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import com.chaoswares.chaosplay.data.FileNameRules
import com.chaoswares.chaosplay.player.PlayQueue
import com.chaoswares.chaosplay.player.QueueItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** UI state for the library screen (spec section 4.1). */
data class LibraryUiState(
    val isLoading: Boolean = true,
    val videos: List<VideoItem> = emptyList(),
    val folders: List<LibraryRules.FolderSummary> = emptyList(),
    val continueWatching: List<Pair<VideoItem, WatchEntry>> = emptyList(),
    val sortOrder: LibraryRules.SortOrder = LibraryRules.SortOrder.DATE_ADDED_DESC,
    val query: String = "",
    val searchActive: Boolean = false,
    val tab: LibraryTabs.Tab = LibraryTabs.Tab.ALL,
    val viewMode: LibraryTabs.ViewMode = LibraryTabs.ViewMode.GRID,
    val favouriteIds: Set<Long> = emptySet(),
    val hiddenIds: Set<Long> = emptySet(),
    val permissionRequired: Boolean = false,
    val errorMessage: String? = null,
    val nowSec: Long = 0L,
    /** Drilled-in folder path, null means showing the chip row contents. */
    val openFolder: String? = null
) {
    val isEmpty: Boolean = !isLoading && videos.isEmpty() && !permissionRequired

    /**
     * The chip row selection, then search, then sort - in that order so a
     * search never resurrects a video filtered out by the current chip.
     */
    val visibleVideos: List<VideoItem>
        get() {
            val folder = openFolder
            val base = if (folder != null) {
                videos.filter { it.folder == folder && it.id !in hiddenIds }
            } else {
                LibraryTabs.filter(
                    videos = videos,
                    tab = tab,
                    nowSec = nowSec,
                    favouriteIds = favouriteIds,
                    hiddenIds = hiddenIds
                )
            }
            return LibraryRules.sort(LibraryTabs.search(base, query), sortOrder)
        }

    /** Folder list for the Folders chip, with hidden files excluded. */
    val visibleFolders: List<LibraryRules.FolderSummary>
        get() = LibraryRules.folders(videos.filterNot { it.id in hiddenIds })

    /** Only folders that still contain videos, per spec 4.1. */
    fun videosIn(folderPath: String): List<VideoItem> =
        videos.filter { it.folder == folderPath && it.id !in hiddenIds }

    fun isFavourite(id: Long): Boolean = id in favouriteIds
}

/**
 * Library state holder. Owns the scan, chip selection, view mode and the
 * favourite/hidden sets so a rotation does not reload the media library.
 */
class LibraryViewModel(app: Application) : AndroidViewModel(app) {

    private val scanner = MediaStoreScanner(app)
    private val settings = SettingsStore(app)

    /**
     * Poster-frame generator for the grid. Shared with the cards so the
     * decode permit limit is process wide instead of per card.
     */
    val thumbnails = VideoThumbnailer(app)
    private val meta = LibraryMetaStore(app)

    private val _state = MutableStateFlow(LibraryUiState())
    val state: StateFlow<LibraryUiState> = _state.asStateFlow()

    private var watchEntries: List<WatchEntry> = emptyList()
    private var openFolderPath: String? = null
    private var refreshJob: kotlinx.coroutines.Job? = null

    init {
        // Load the persisted view mode before the first frame so the grid
        // does not flash as a list on launch.
        viewModelScope.launch {
            val savedView = runCatching {
                LibraryTabs.ViewMode.valueOf(settings.viewMode.first())
            }.getOrDefault(LibraryTabs.ViewMode.GRID)
            _state.value = _state.value.copy(viewMode = savedView)
        }
        observeMeta()
        refresh()
    }

    private fun observeMeta() {
        viewModelScope.launch {
            meta.favouriteIds.collect { ids ->
                _state.value = _state.value.copy(favouriteIds = ids)
            }
        }
        viewModelScope.launch {
            meta.hiddenIds.collect { ids ->
                _state.value = _state.value.copy(hiddenIds = ids)
            }
        }
    }

    /**
     * Rescans the device. MediaStore queries take hundreds of milliseconds,
     * so callers navigating back from the player must NOT await this: the
     * saved list stays on screen and updates when the scan lands.
     */
    fun refresh() {
        if (refreshJob?.isActive == true) return
        refreshJob = viewModelScope.launch {
            _state.value = _state.value.copy(isLoading = true, errorMessage = null)

            watchEntries = ResumeCodec.decode(settings.resumeRaw.first())

            val videos = runCatching { scanner.scanVideos() }
                .getOrElse { error ->
                    _state.value = _state.value.copy(
                        isLoading = false,
                        errorMessage = error.message ?: "Could not read the media library"
                    )
                    return@launch
                }

            val sortOrder = runCatching {
                LibraryRules.SortOrder.valueOf(settings.sortOrder.first())
            }.getOrDefault(LibraryRules.SortOrder.DATE_ADDED_DESC)

            _state.value = _state.value.copy(
                isLoading = false,
                videos = videos,
                folders = LibraryRules.folders(videos),
                continueWatching = buildContinueWatching(videos),
                sortOrder = sortOrder,
                nowSec = System.currentTimeMillis() / 1000L
            )

            // Drop posters for files that are gone (deleted, renamed away) so
            // the cache cannot grow without bound across scans.
            runCatching { thumbnails.prune(videos.map { it.id }.toSet()) }
        }
    }

    private fun buildContinueWatching(videos: List<VideoItem>): List<Pair<VideoItem, WatchEntry>> {
        val byUri = videos.associateBy { it.uri }
        return LibraryRules.continueWatching(watchEntries)
            .mapNotNull { entry -> byUri[entry.uri]?.let { it to entry } }
    }

    // ---- filter chips and view mode, spec 4.1 ----

    fun selectTab(tab: LibraryTabs.Tab) {
        openFolderPath = null
        _state.value = _state.value.copy(
            tab = tab, query = "", searchActive = false, openFolder = null
        )
    }

    fun toggleViewMode() {
        val next = _state.value.viewMode.toggled()
        _state.value = _state.value.copy(viewMode = next)
        viewModelScope.launch { settings.setViewMode(next.name) }
    }

    fun openFolder(path: String) {
        openFolderPath = path
        _state.value = _state.value.copy(query = "", searchActive = false, openFolder = path)
    }

    fun closeFolder() {
        openFolderPath = null
        _state.value = _state.value.copy(openFolder = null)
    }

    fun setQuery(query: String) {
        _state.value = _state.value.copy(query = query)
    }

    fun toggleSearch() {
        val active = !_state.value.searchActive
        _state.value = _state.value.copy(
            searchActive = active,
            query = if (active) _state.value.query else ""
        )
    }

    fun setSortOrder(order: LibraryRules.SortOrder) {
        _state.value = _state.value.copy(sortOrder = order)
        viewModelScope.launch { settings.setSortOrder(order.name) }
    }

    // ---- long press actions, spec 4.1 ----

    fun toggleFavourite(id: Long) {
        viewModelScope.launch { meta.toggleFavourite(id) }
    }

    fun hide(id: Long) {
        viewModelScope.launch { meta.hide(id) }
    }

    fun onPermissionDenied() {
        _state.value = _state.value.copy(isLoading = false, permissionRequired = true)
    }

    fun onPermissionGranted() {
        _state.value = _state.value.copy(permissionRequired = false)
        refresh()
    }

    /** Resume position for a video, used to draw progress bars and badges. */
    fun watchEntryFor(uri: String): WatchEntry? = watchEntries.firstOrNull { it.uri == uri }

    fun unhide(id: Long) {
        viewModelScope.launch { meta.unhide(id) }
    }

    fun unhideAll() {
        viewModelScope.launch { meta.unhideAll() }
    }

    // ---- queue building, spec 4.7 "queue" and 4.1 "Play next" ----

    private val _upNext = MutableStateFlow<List<QueueItem>>(emptyList())
    val upNext: StateFlow<List<QueueItem>> = _upNext.asStateFlow()

    fun addPlayNext(video: VideoItem) {
        _upNext.value = _upNext.value.filterNot { it.uri == video.uri } + video.toQueueItem()
    }

    /**
     * Tapping a video plays the list it was tapped in, from that video on,
     * so Next/Previous and auto-play-next walk the same list the user sees.
     * Items marked "Play next" go right after the tapped one.
     */
    fun queueFrom(video: VideoItem, list: List<VideoItem> = _state.value.visibleVideos): PlayQueue {
        val items = list.ifEmpty { listOf(video) }
        val index = items.indexOfFirst { it.id == video.id }.takeIf { it >= 0 } ?: 0
        var queue = PlayQueue.of(items.map { it.toQueueItem() }, index)
        if (items.none { it.id == video.id }) queue = PlayQueue.single(video.toQueueItem())
        _upNext.value.asReversed().forEach { queue = queue.playNext(it) }
        _upNext.value = emptyList()
        return queue
    }

    // ---- rename / delete / share, spec 7.5 "File management" ----

    sealed class FileOp {
        object Done : FileOp()
        data class NeedsConsent(val sender: IntentSender) : FileOp()
        data class Failed(val message: String) : FileOp()
    }

    /** Pending rename waiting for the system consent dialog (Android 10+). */
    private var pendingRename: Pair<VideoItem, String>? = null

    suspend fun rename(video: VideoItem, input: String): FileOp {
        val newName = when (val r = FileNameRules.rename(video.title, input)) {
            is FileNameRules.Result.Ok -> r.fileName
            is FileNameRules.Result.Error -> return FileOp.Failed(r.message)
        }
        val app = getApplication<android.app.Application>()
        val uri = Uri.parse(video.uri)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            pendingRename = video to newName
            val pi = MediaStore.createWriteRequest(app.contentResolver, listOf(uri))
            return FileOp.NeedsConsent(pi.intentSender)
        }
        return performRename(video, newName)
    }

    /** Called after the user allowed the write in the system dialog. */
    suspend fun onRenameConsent(granted: Boolean): FileOp {
        val (video, name) = pendingRename ?: return FileOp.Failed("Nothing to rename")
        pendingRename = null
        if (!granted) return FileOp.Failed("Rename cancelled")
        return performRename(video, name)
    }

    private suspend fun performRename(video: VideoItem, newName: String): FileOp = withContext(Dispatchers.IO) {
        val app = getApplication<android.app.Application>()
        val uri = Uri.parse(video.uri)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply { put(MediaStore.MediaColumns.DISPLAY_NAME, newName) }
                val rows = app.contentResolver.update(uri, values, null, null)
                if (rows <= 0) return@withContext FileOp.Failed("The file could not be renamed")
            } else {
                val from = java.io.File(video.path)
                val to = java.io.File(from.parentFile, newName)
                if (to.exists()) return@withContext FileOp.Failed("A file with that name already exists")
                if (!from.renameTo(to)) return@withContext FileOp.Failed("The file could not be renamed")
                MediaScannerConnection.scanFile(app, arrayOf(from.absolutePath, to.absolutePath), null, null)
            }
            refreshAfterChange()
            FileOp.Done
        } catch (e: RecoverableSecurityException) {
            pendingRename = video to newName
            FileOp.NeedsConsent(e.userAction.actionIntent.intentSender)
        } catch (e: Exception) {
            FileOp.Failed(e.message ?: "The file could not be renamed")
        }
    }

    suspend fun delete(video: VideoItem): FileOp = withContext(Dispatchers.IO) {
        val app = getApplication<android.app.Application>()
        val uri = Uri.parse(video.uri)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // The system shows its own "Allow CPlay to delete?" dialog
            val pi = MediaStore.createDeleteRequest(app.contentResolver, listOf(uri))
            return@withContext FileOp.NeedsConsent(pi.intentSender)
        }
        try {
            val rows = app.contentResolver.delete(uri, null, null)
            if (rows <= 0 && video.path.isNotBlank()) java.io.File(video.path).delete()
            refreshAfterChange()
            FileOp.Done
        } catch (e: RecoverableSecurityException) {
            FileOp.NeedsConsent(e.userAction.actionIntent.intentSender)
        } catch (e: Exception) {
            FileOp.Failed(e.message ?: "The file could not be deleted")
        }
    }

    fun refreshAfterChange() {
        viewModelScope.launch {
            kotlinx.coroutines.delay(300)
            refreshJob?.cancel()
            refresh()
        }
    }

    fun share(context: Context, video: VideoItem) {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = video.mimeType.ifBlank { "video/*" }
            putExtra(Intent.EXTRA_STREAM, Uri.parse(video.uri))
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        runCatching { context.startActivity(Intent.createChooser(intent, "Share video").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }
}

fun VideoItem.toQueueItem(): QueueItem = QueueItem(uri = uri, title = title, durationMs = durationMs)
