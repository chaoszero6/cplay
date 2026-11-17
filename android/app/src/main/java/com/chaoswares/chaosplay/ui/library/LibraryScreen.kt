package com.chaoswares.chaosplay.ui.library

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.automirrored.filled.ViewList
import androidx.compose.material.icons.filled.VideocamOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.Image
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.layout.ContentScale
import coil.compose.rememberAsyncImagePainter
import java.io.File
import androidx.compose.ui.input.nestedscroll.nestedScroll
import kotlinx.coroutines.launch
import com.chaoswares.chaosplay.data.VideoItem
import com.chaoswares.chaosplay.data.ThumbnailRules
import com.chaoswares.chaosplay.data.VideoThumbnailer
import com.chaoswares.chaosplay.player.LibraryRules
import com.chaoswares.chaosplay.player.LibraryTabs
import com.chaoswares.chaosplay.player.TimeFormat
import com.chaoswares.chaosplay.player.WatchEntry
import com.chaoswares.chaosplay.ui.theme.ChaosShape
import com.chaoswares.chaosplay.ui.theme.ChaosSpacing
import com.chaoswares.chaosplay.ui.theme.ChaosType
import com.chaoswares.chaosplay.ui.theme.HeaderTitle
import com.chaoswares.chaosplay.ui.theme.chaosTokens

/**
 * Library screen, spec section 4.1.
 *
 * Layout, top to bottom:
 *  1. Top bar: title or search field, search, sort, view toggle, overflow
 *  2. Chip row: All videos | Folders | Recent | Favorites
 *  3. Continue watching row (up to 10 cards, progress bar, remaining time)
 *  4. Grid (2 columns on phone, 3 on tablet) or list, with the states
 *     loading / empty / permission needed / error.
 */
@OptIn(
    androidx.compose.foundation.ExperimentalFoundationApi::class,
    androidx.compose.material3.ExperimentalMaterial3Api::class
)
@Composable
fun LibraryScreen(
    viewModel: LibraryViewModel,
    playlists: com.chaoswares.chaosplay.ui.playlists.PlaylistsViewModel,
    onPlay: (com.chaoswares.chaosplay.player.PlayQueue) -> Unit,
    onOpenSettings: () -> Unit
) {
    val state by viewModel.state.collectAsState()
    val tokens = chaosTokens()
    val context = LocalContext.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val snackbar = remember { androidx.compose.material3.SnackbarHostState() }
    val upNext by viewModel.upNext.collectAsState()
    val onOpenVideo: (String) -> Unit = { uri ->
        val video = state.visibleVideos.firstOrNull { it.uri == uri }
            ?: state.videos.firstOrNull { it.uri == uri }
        if (video != null) onPlay(viewModel.queueFrom(video))
    }
    val onOpenContinue: (String) -> Unit = { uri ->
        val list = state.continueWatching.map { it.first }
        list.firstOrNull { it.uri == uri }?.let { onPlay(viewModel.queueFrom(it, list)) }
    }
    var renameVideo by remember { mutableStateOf<VideoItem?>(null) }
    var deleteVideo by remember { mutableStateOf<VideoItem?>(null) }
    var addToPlaylist by remember { mutableStateOf<VideoItem?>(null) }
    fun toast(msg: String) { scope.launch { snackbar.currentSnackbarData?.dismiss(); snackbar.showSnackbar(msg) } }

    val renameConsent = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        scope.launch {
            when (val r = viewModel.onRenameConsent(result.resultCode == android.app.Activity.RESULT_OK)) {
                LibraryViewModel.FileOp.Done -> toast("Renamed")
                is LibraryViewModel.FileOp.Failed -> toast(r.message)
                else -> Unit
            }
        }
    }
    val deleteConsent = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK) {
            viewModel.refreshAfterChange()
            toast("Deleted")
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        if (grants.values.any { it }) viewModel.onPermissionGranted()
        else viewModel.onPermissionDenied()
    }

    val permissions = remember {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            arrayOf(Manifest.permission.READ_MEDIA_VIDEO, Manifest.permission.READ_MEDIA_AUDIO)
        } else {
            arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
    }

    LaunchedEffect(Unit) {
        val granted = permissions.any {
            androidx.core.content.ContextCompat.checkSelfPermission(context, it) == android.content.pm.PackageManager.PERMISSION_GRANTED
        }
        if (granted) viewModel.onPermissionGranted() else permissionLauncher.launch(permissions)
    }

    var actionVideo by remember { mutableStateOf<VideoItem?>(null) }
    var showInfo by remember { mutableStateOf<VideoItem?>(null) }
    var sortMenuOpen by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(tokens.bg)
    ) {
        LibraryTopBar(
            state = state,
            sortMenuOpen = sortMenuOpen,
            onSortMenuOpenChange = { sortMenuOpen = it },
            onQueryChange = viewModel::setQuery,
            onToggleSearch = viewModel::toggleSearch,
            onToggleView = viewModel::toggleViewMode,
            onSortSelected = {
                viewModel.setSortOrder(it)
                sortMenuOpen = false
            },
            onOpenSettings = onOpenSettings,
            onBackFromFolder = viewModel::closeFolder
        )

        ChipRow(
            selected = state.tab,
            onSelect = viewModel::selectTab,
            enabled = state.permissionRequired.not() && state.isEmpty.not()
        )

        if (upNext.isNotEmpty()) {
            Text(
                "Up next: ${upNext.size} video${if (upNext.size == 1) "" else "s"} · plays after the video you open",
                style = ChaosType.Caption,
                color = tokens.accent2,
                modifier = Modifier.padding(horizontal = ChaosSpacing.L.dp, vertical = ChaosSpacing.XS.dp)
            )
        }

        val pullState = androidx.compose.material3.pulltorefresh.rememberPullToRefreshState()
        if (pullState.isRefreshing) {
            LaunchedEffect(Unit) { viewModel.refresh() }
        }
        LaunchedEffect(state.isLoading) {
            if (!state.isLoading) pullState.endRefresh()
        }
        Box(
            modifier = Modifier
                .weight(1f)
                // The refresh indicator rests just above this box: clip it so it
                // does not show over the chips when idle
                .clipToBounds()
                .nestedScroll(pullState.nestedScrollConnection)
        ) {
        Column(modifier = Modifier.fillMaxSize()) {
            when {
                state.permissionRequired -> PermissionState(
                    onRetry = { permissionLauncher.launch(permissions) }
                )

                state.isLoading && state.videos.isEmpty() -> LoadingState()

                state.errorMessage != null -> ErrorState(
                    message = state.errorMessage!!,
                    onRetry = viewModel::refresh
                )

                state.tab == LibraryTabs.Tab.FOLDERS && state.openFolder == null -> FolderList(
                    folders = state.visibleFolders,
                    state = state,
                    onOpenFolder = viewModel::openFolder
                )

                state.visibleVideos.isEmpty() -> EmptyState(
                    message = LibraryTabs.emptyMessage(state.tab),
                    tab = state.tab,
                    searching = state.query.isNotBlank(),
                    onShowAll = { viewModel.selectTab(LibraryTabs.Tab.ALL) },
                    onRescan = viewModel::refresh
                )

                else -> VideoCollection(
                    state = state,
                    thumbnails = viewModel.thumbnails,
                    watchEntryFor = viewModel::watchEntryFor,
                    onOpenVideo = onOpenVideo,
                    onOpenContinue = onOpenContinue,
                    onLongPress = { actionVideo = it }
                )
            }
        }
        androidx.compose.material3.pulltorefresh.PullToRefreshContainer(
            state = pullState,
            modifier = Modifier.align(Alignment.TopCenter),
            containerColor = tokens.surface,
            contentColor = tokens.accent
        )
        androidx.compose.material3.SnackbarHost(snackbar, modifier = Modifier.align(Alignment.BottomCenter))
        }
    }

    actionVideo?.let { video ->
        ItemActionSheet(
            video = video,
            isFavourite = state.isFavourite(video.id),
            onDismiss = { actionVideo = null },
            onAction = { action ->
                when (action) {
                    LibraryTabs.ItemAction.FAVORITES_TOGGLE -> viewModel.toggleFavourite(video.id)
                    LibraryTabs.ItemAction.INFO -> showInfo = video
                    LibraryTabs.ItemAction.HIDE -> {
                        viewModel.hide(video.id)
                        toast("Hidden. Find it in Settings › Hidden videos")
                    }
                    LibraryTabs.ItemAction.PLAY -> onOpenVideo(video.uri)
                    LibraryTabs.ItemAction.PLAY_NEXT -> {
                        viewModel.addPlayNext(video)
                        toast("Plays next after the video you open")
                    }
                    LibraryTabs.ItemAction.ADD_TO_PLAYLIST -> addToPlaylist = video
                    LibraryTabs.ItemAction.RENAME -> renameVideo = video
                    LibraryTabs.ItemAction.SHARE -> viewModel.share(context, video)
                    LibraryTabs.ItemAction.DELETE -> deleteVideo = video
                }
                actionVideo = null
            }
        )
    }

    showInfo?.let { video ->
        InfoDialog(video = video, onDismiss = { showInfo = null })
    }

    renameVideo?.let { video ->
        RenameDialog(
            video = video,
            onDismiss = { renameVideo = null },
            onRename = { name ->
                renameVideo = null
                scope.launch {
                    when (val r = viewModel.rename(video, name)) {
                        LibraryViewModel.FileOp.Done -> toast("Renamed")
                        is LibraryViewModel.FileOp.NeedsConsent -> renameConsent.launch(
                            androidx.activity.result.IntentSenderRequest.Builder(r.sender).build()
                        )
                        is LibraryViewModel.FileOp.Failed -> toast(r.message)
                    }
                }
            }
        )
    }

    deleteVideo?.let { video ->
        AlertDialog(
            onDismissRequest = { deleteVideo = null },
            containerColor = tokens.surface,
            title = { Text("Delete video?", color = tokens.text) },
            text = { Text("\"${video.title}\" will be deleted from this device.", color = tokens.textMuted) },
            confirmButton = {
                TextButton(onClick = {
                    deleteVideo = null
                    scope.launch {
                        when (val r = viewModel.delete(video)) {
                            LibraryViewModel.FileOp.Done -> toast("Deleted")
                            is LibraryViewModel.FileOp.NeedsConsent -> deleteConsent.launch(
                                androidx.activity.result.IntentSenderRequest.Builder(r.sender).build()
                            )
                            is LibraryViewModel.FileOp.Failed -> toast(r.message)
                        }
                    }
                }) { Text("Delete", color = tokens.danger) }
            },
            dismissButton = { TextButton(onClick = { deleteVideo = null }) { Text("Cancel") } }
        )
    }

    addToPlaylist?.let { video ->
        com.chaoswares.chaosplay.ui.playlists.AddToPlaylistSheet(
            viewModel = playlists,
            items = listOf(com.chaoswares.chaosplay.data.PlaylistItem(video.uri, video.title, video.durationMs)),
            onDone = { message ->
                addToPlaylist = null
                if (message != null) toast(message)
            }
        )
    }
}

@Composable
private fun RenameDialog(video: VideoItem, onDismiss: () -> Unit, onRename: (String) -> Unit) {
    val tokens = chaosTokens()
    var text by remember { mutableStateOf(com.chaoswares.chaosplay.data.FileNameRules.stem(video.title)) }
    val check = com.chaoswares.chaosplay.data.FileNameRules.rename(video.title, text)
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = tokens.surface,
        title = { Text("Rename", color = tokens.text) },
        text = {
            androidx.compose.material3.OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                singleLine = true,
                isError = check is com.chaoswares.chaosplay.data.FileNameRules.Result.Error && text.isNotBlank(),
                supportingText = {
                    when (check) {
                        is com.chaoswares.chaosplay.data.FileNameRules.Result.Ok -> Text(check.fileName)
                        is com.chaoswares.chaosplay.data.FileNameRules.Result.Error -> Text(check.message)
                    }
                }
            )
        },
        confirmButton = {
            TextButton(
                onClick = { onRename(text) },
                enabled = check is com.chaoswares.chaosplay.data.FileNameRules.Result.Ok
            ) { Text("Rename", color = tokens.accent) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable
private fun LibraryTopBar(
    state: LibraryUiState,
    sortMenuOpen: Boolean,
    onSortMenuOpenChange: (Boolean) -> Unit,
    onQueryChange: (String) -> Unit,
    onToggleSearch: () -> Unit,
    onToggleView: () -> Unit,
    onSortSelected: (LibraryRules.SortOrder) -> Unit,
    onOpenSettings: () -> Unit,
    onBackFromFolder: () -> Unit
) {
    val tokens = chaosTokens()
    Column(modifier = Modifier.padding(horizontal = ChaosSpacing.L.dp)) {
        Spacer(Modifier.height(ChaosSpacing.S.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            // Inside a folder: the title becomes the way back out. Without
            // this the drilled-in view had no exit except switching chips.
            if (state.openFolder != null && !state.searchActive) {
                IconButton(onClick = onBackFromFolder) {
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Back to folders",
                        tint = tokens.text
                    )
                }
            }
            if (state.searchActive) {
                TextField(
                    value = state.query,
                    onValueChange = onQueryChange,
                    placeholder = { Text("Search videos") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = tokens.surface2,
                        unfocusedContainerColor = tokens.surface2,
                        focusedTextColor = tokens.text,
                        unfocusedTextColor = tokens.text
                    ),
                    modifier = Modifier.weight(1f)
                )
                IconButton(onClick = onToggleSearch) {
                    Icon(Icons.Filled.Close, contentDescription = "Close search", tint = tokens.text)
                }
            } else {
                HeaderTitle(
                    text = "CPlay",
                    color = tokens.text,
                    modifier = Modifier.weight(1f)
                )
                IconButton(onClick = onToggleSearch) {
                    Icon(Icons.Filled.Search, contentDescription = "Search", tint = tokens.text)
                }
                Box {
                    IconButton(onClick = { onSortMenuOpenChange(true) }) {
                        Icon(
                            Icons.AutoMirrored.Filled.Sort,
                            contentDescription = "Sort: ${state.sortOrder.label}",
                            tint = tokens.text
                        )
                    }
                    LibrarySortMenu(
                        expanded = sortMenuOpen,
                        current = state.sortOrder,
                        onDismiss = { onSortMenuOpenChange(false) },
                        onSelect = onSortSelected
                    )
                }
                IconButton(onClick = onToggleView) {
                    Icon(
                        imageVector = if (state.viewMode == LibraryTabs.ViewMode.GRID) {
                            Icons.AutoMirrored.Filled.ViewList
                        } else {
                            Icons.Filled.GridView
                        },
                        contentDescription = if (state.viewMode == LibraryTabs.ViewMode.GRID) {
                            "Switch to list view"
                        } else {
                            "Switch to grid view"
                        },
                        tint = tokens.text
                    )
                }
                IconButton(onClick = onOpenSettings) {
                    Icon(Icons.Filled.Settings, contentDescription = "Settings", tint = tokens.text)
                }
            }
        }
    }
}

@Composable
private fun LibrarySortMenu(
    expanded: Boolean,
    current: LibraryRules.SortOrder,
    onDismiss: () -> Unit,
    onSelect: (LibraryRules.SortOrder) -> Unit
) {
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        LibraryRules.SortOrder.entries.forEach { order ->
            DropdownMenuItem(
                text = {
                    Text(
                        text = order.label,
                        color = if (order == current) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurface
                        }
                    )
                },
                onClick = { onSelect(order) }
            )
        }
    }
}

/** All videos | Folders | Recent | Favorites, spec 4.1. */
@Composable
private fun ChipRow(
    selected: LibraryTabs.Tab,
    onSelect: (LibraryTabs.Tab) -> Unit,
    enabled: Boolean
) {
    val tokens = chaosTokens()
    LazyRow(
        contentPadding = PaddingValues(
            start = ChaosSpacing.L.dp,
            end = ChaosSpacing.L.dp,
            top = ChaosSpacing.S.dp,
            bottom = ChaosSpacing.XS.dp
        ),
        horizontalArrangement = Arrangement.spacedBy(ChaosSpacing.S.dp)
    ) {
        items(LibraryTabs.Tab.entries) { tab ->
            FilterChip(
                selected = tab == selected,
                onClick = { onSelect(tab) },
                enabled = enabled,
                label = { Text(tab.label) },
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = tokens.accent.copy(alpha = 0.18f),
                    selectedLabelColor = tokens.accent,
                    labelColor = tokens.textMuted,
                    containerColor = tokens.surface2
                ),
                shape = RoundedCornerShape(percent = 50),
                border = null
            )
        }
    }
}

@Composable
private fun VideoCollection(
    state: LibraryUiState,
    thumbnails: VideoThumbnailer,
    watchEntryFor: (String) -> WatchEntry?,
    onOpenVideo: (String) -> Unit,
    onOpenContinue: (String) -> Unit,
    onLongPress: (VideoItem) -> Unit
) {
    val configuration = LocalConfiguration.current
    val widthDp = with(LocalDensity.current) { configuration.screenWidthDp }
    val columns = LibraryTabs.gridColumns(widthDp)

    val header: @Composable () -> Unit = {
        Row(
            modifier = Modifier.padding(
                start = ChaosSpacing.L.dp,
                end = ChaosSpacing.L.dp,
                top = ChaosSpacing.M.dp
            ),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = when (state.tab) {
                    LibraryTabs.Tab.ALL -> "All videos"
                    LibraryTabs.Tab.FOLDERS -> "Folders"
                    LibraryTabs.Tab.RECENT -> "Recent"
                    LibraryTabs.Tab.FAVORITES -> "Favorites"
                },
                style = ChaosType.Heading,
                color = chaosTokens().text,
                modifier = Modifier.weight(1f)
            )
            Text(
                text = "${state.visibleVideos.size}",
                style = ChaosType.Caption,
                color = chaosTokens().textMuted
            )
        }
    }

    if (state.viewMode == LibraryTabs.ViewMode.LIST) {
        LazyColumn(
            modifier = Modifier.testTag("video_list"),
            contentPadding = PaddingValues(bottom = ChaosSpacing.XXL.dp),
            verticalArrangement = Arrangement.spacedBy(ChaosSpacing.S.dp)
        ) {
            item { header() }
            if (state.continueWatching.isNotEmpty() && state.query.isBlank()) {
                item {
                    SectionHeader("Continue watching")
                    ContinueWatchingRow(
                        items = state.continueWatching,
                        thumbnails = thumbnails,
                        onOpenVideo = onOpenContinue
                    )
                }
            }
            items(state.visibleVideos, key = { it.id }) { video ->
                VideoListRow(
                    video = video,
                    thumbnails = thumbnails,
                    watchEntry = watchEntryFor(video.uri),
                    isFavourite = state.isFavourite(video.id),
                    onClick = { onOpenVideo(video.uri) },
                    onLongPress = { onLongPress(video) }
                )
            }
        }
        return
    }

    LazyVerticalGrid(
        modifier = Modifier.testTag("video_list"),
        columns = GridCells.Fixed(columns),
        contentPadding = PaddingValues(
            start = ChaosSpacing.L.dp,
            end = ChaosSpacing.L.dp,
            bottom = ChaosSpacing.XXL.dp
        ),
        horizontalArrangement = Arrangement.spacedBy(ChaosSpacing.M.dp),
        verticalArrangement = Arrangement.spacedBy(ChaosSpacing.M.dp)
    ) {
        // The continue-watching row and the header span the full grid width
        item(span = { androidx.compose.foundation.lazy.grid.GridItemSpan(maxLineSpan) }) {
            Column {
                if (state.continueWatching.isNotEmpty() && state.query.isBlank()) {
                    Text(
                        text = "Continue watching",
                        style = ChaosType.Heading,
                        color = chaosTokens().text,
                        modifier = Modifier.padding(top = ChaosSpacing.M.dp, bottom = ChaosSpacing.S.dp)
                    )
                    ContinueWatchingRow(
                        items = state.continueWatching,
                        thumbnails = thumbnails,
                        onOpenVideo = onOpenContinue,
                        contentPadding = PaddingValues(0.dp)
                    )
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = ChaosSpacing.M.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "All videos",
                        style = ChaosType.Heading,
                        color = chaosTokens().text,
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        text = "${state.visibleVideos.size}",
                        style = ChaosType.Caption,
                        color = chaosTokens().textMuted
                    )
                }
            }
        }
        items(state.visibleVideos, key = { it.id }) { video ->
            VideoCard(
                video = video,
                thumbnails = thumbnails,
                watchEntry = watchEntryFor(video.uri),
                isFavourite = state.isFavourite(video.id),
                onClick = { onOpenVideo(video.uri) },
                onLongPress = { onLongPress(video) }
            )
        }
    }
}

@Composable
private fun SectionHeader(title: String) {
    Text(
        text = title,
        style = ChaosType.Heading,
        color = chaosTokens().text,
        modifier = Modifier.padding(
            start = ChaosSpacing.L.dp,
            end = ChaosSpacing.L.dp,
            top = ChaosSpacing.S.dp
        )
    )
}


/**
 * Real thumbnail for [video], spec 2.6 "16:9 thumbnail".
 *
 * Resolution order, all resolving to a local File:
 *  1. A sidecar image written next to the video (`clip.mp4` -> `clip.jpg`).
 *  2. A poster frame decoded from the video and cached by [VideoThumbnailer].
 * The placeholder (surface2 + centre play glyph) stays underneath and simply
 * never gets covered when a file has no decodable video stream, so an
 * audio-only or corrupt file still reads as a card instead of an empty hole.
 */
@Composable
private fun Thumbnail(
    video: VideoItem,
    thumbnails: VideoThumbnailer,
    modifier: Modifier = Modifier
) {
    val tokens = chaosTokens()
    var file by remember(video.id, video.dateModifiedSec) { mutableStateOf<File?>(null) }

    LaunchedEffect(video.id, video.dateModifiedSec) {
        val sidecar = ThumbnailRules.sidecarCandidates(video.path)
            .map { File(it) }
            .firstOrNull { it.isFile && it.length() > 0L }
        file = sidecar ?: thumbnails.thumbnailFor(
            videoId = video.id,
            uri = video.path.ifBlank { video.uri },
            dateModifiedSec = video.dateModifiedSec,
            durationMs = video.durationMs
        )
    }

    Box(modifier = modifier.background(tokens.surface2)) {
        Icon(
            Icons.Filled.PlayArrow,
            contentDescription = null,
            tint = tokens.textMuted.copy(alpha = 0.5f),
            modifier = Modifier
                .align(Alignment.Center)
                .size(32.dp)
        )
        val current = file
        if (current != null) {
            Image(
                painter = rememberAsyncImagePainter(
                    model = coil.request.ImageRequest.Builder(LocalContext.current)
                        .data(current)
                        .size(ThumbnailRules.TARGET_WIDTH, ThumbnailRules.TARGET_WIDTH * 9 / 16)
                        .crossfade(true)
                        .build()
                ),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
            // Keep the duration badge legible over a bright frame.
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            0.5f to Color.Transparent,
                            1f to Color(0x99000000)
                        )
                    )
            )
        }
    }
}

/** Up to 10 cards with a progress bar and the remaining time, spec 4.1. */
@Composable
private fun ContinueWatchingRow(
    items: List<Pair<VideoItem, WatchEntry>>,
    thumbnails: VideoThumbnailer,
    onOpenVideo: (String) -> Unit,
    contentPadding: PaddingValues = PaddingValues(horizontal = ChaosSpacing.L.dp)
) {
    val tokens = chaosTokens()
    LazyRow(
        contentPadding = contentPadding,
        horizontalArrangement = Arrangement.spacedBy(ChaosSpacing.M.dp)
    ) {
        items(items, key = { it.first.id }) { (video, entry) ->
            Card(
                colors = CardDefaults.cardColors(containerColor = tokens.surface),
                shape = RoundedCornerShape(ChaosShape.CARD.dp),
                modifier = Modifier
                    .width(200.dp)
                    .clickable { onOpenVideo(video.uri) }
            ) {
                Box(modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f)) {
                    Thumbnail(
                        video = video,
                        thumbnails = thumbnails,
                        modifier = Modifier.fillMaxSize()
                    )
                    Text(
                        text = TimeFormat.remainingShortLabel(entry.durationMs, entry.positionMs),
                        style = ChaosType.Caption,
                        color = Color.White,
                        modifier = Modifier
                            .align(Alignment.BottomStart)
                            .padding(ChaosSpacing.XS.dp)
                            .clip(RoundedCornerShape(4.dp))
                            .background(Color(0xB3000000))
                            .padding(horizontal = ChaosSpacing.XS.dp, vertical = 1.dp)
                    )
                }
                LinearProgressIndicator(
                    progress = { entry.progressFraction() },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(3.dp),
                    color = tokens.accent,
                    trackColor = tokens.surface2
                )
                Text(
                    text = video.title,
                    style = ChaosType.Body,
                    color = tokens.text,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(ChaosSpacing.S.dp)
                )
            }
        }
    }
}

/** Grid card, spec section 2.6: 16:9 thumbnail, duration, progress, tags. */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun VideoCard(
    video: VideoItem,
    thumbnails: VideoThumbnailer,
    watchEntry: WatchEntry?,
    isFavourite: Boolean,
    onClick: () -> Unit,
    onLongPress: () -> Unit
) {
    val tokens = chaosTokens()
    val hasResume = watchEntry != null &&
        LibraryRules.shouldShowResume(watchEntry.positionMs, watchEntry.durationMs)

    Card(
        colors = CardDefaults.cardColors(containerColor = tokens.surface),
        shape = RoundedCornerShape(ChaosShape.CARD.dp),
        modifier = Modifier.combinedClickable(
            onClick = onClick,
            onLongClick = onLongPress
        )
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f)
        ) {
            // Poster frame; keeps the centre play glyph when nothing decodes
            Thumbnail(
                video = video,
                thumbnails = thumbnails,
                modifier = Modifier.fillMaxSize()
            )

            // Duration badge, bottom right
            Text(
                text = TimeFormat.format(video.durationMs),
                style = ChaosType.Caption,
                color = Color.White,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(ChaosSpacing.XS.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(Color(0xB3000000))
                    .padding(horizontal = ChaosSpacing.XS.dp, vertical = 2.dp)
            )

            Row(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(ChaosSpacing.XS.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (LibraryRules.isNew(video, System.currentTimeMillis() / 1000L) && watchEntry == null) {
                    Text(
                        "NEW",
                        style = ChaosType.Label,
                        color = Color.Black,
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .background(tokens.accent2)
                            .padding(horizontal = 4.dp, vertical = 1.dp)
                    )
                }
                if (watchEntry?.isFinished() == true) {
                    Text(
                        "WATCHED",
                        style = ChaosType.Label,
                        color = Color.White,
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .background(Color(0xB3000000))
                            .padding(horizontal = 4.dp, vertical = 1.dp)
                    )
                }
                if (isFavourite) {
                    Icon(Icons.Filled.Favorite, contentDescription = "Favourite", tint = tokens.accent2, modifier = Modifier.size(16.dp))
                }
            }
        }

        if (hasResume && watchEntry != null) {
            LinearProgressIndicator(
                progress = { watchEntry.progressFraction() },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(3.dp),
                color = tokens.accent,
                trackColor = tokens.surface2
            )
        }

        Column(modifier = Modifier.padding(ChaosSpacing.S.dp)) {
            Text(
                text = video.title,
                style = ChaosType.Body,
                color = tokens.text,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = listOfNotNull(
                    video.resolutionLabel.ifBlank { null },
                    video.sizeLabel.ifBlank { null }
                ).joinToString(" · "),
                style = ChaosType.Caption,
                color = tokens.textMuted,
                maxLines = 1
            )
        }
    }
}

/** List row: thumbnail left, details right, spec 4.1. */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun VideoListRow(
    video: VideoItem,
    thumbnails: VideoThumbnailer,
    watchEntry: WatchEntry?,
    isFavourite: Boolean,
    onClick: () -> Unit,
    onLongPress: () -> Unit
) {
    val tokens = chaosTokens()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onClick, onLongClick = onLongPress)
            .background(tokens.surface)
            .padding(ChaosSpacing.S.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .width(120.dp)
                .aspectRatio(16f / 9f)
                .clip(RoundedCornerShape(ChaosShape.BUTTON.dp))
        ) {
            Thumbnail(
                video = video,
                thumbnails = thumbnails,
                modifier = Modifier.fillMaxSize()
            )
            Text(
                text = TimeFormat.format(video.durationMs),
                style = ChaosType.Caption,
                color = Color.White,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(ChaosSpacing.XS.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(Color(0xB3000000))
                    .padding(horizontal = ChaosSpacing.XS.dp, vertical = 1.dp)
            )
        }
        Spacer(Modifier.width(ChaosSpacing.M.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = video.title,
                style = ChaosType.Body,
                color = tokens.text,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = listOfNotNull(
                    video.resolutionLabel.ifBlank { null },
                    video.sizeLabel.ifBlank { null }
                ).joinToString(" · "),
                style = ChaosType.Caption,
                color = tokens.textMuted,
                maxLines = 1
            )
            if (watchEntry != null && LibraryRules.shouldShowResume(
                    watchEntry.positionMs, watchEntry.durationMs
                )
            ) {
                Spacer(Modifier.height(ChaosSpacing.XS.dp))
                LinearProgressIndicator(
                    progress = { watchEntry.progressFraction() },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(3.dp),
                    color = tokens.accent,
                    trackColor = tokens.surface2
                )
            }
        }
        if (isFavourite) {
            Icon(
                Icons.Filled.Favorite,
                contentDescription = "Favourite",
                tint = tokens.accent2,
                modifier = Modifier
                    .padding(start = ChaosSpacing.S.dp)
                    .size(16.dp)
            )
        }
    }
}

/** Folder rows with count and total size, spec 2.6 and 4.1. */
@Composable
private fun FolderList(
    folders: List<LibraryRules.FolderSummary>,
    @Suppress("UNUSED_PARAMETER") state: LibraryUiState,
    onOpenFolder: (String) -> Unit
) {
    val tokens = chaosTokens()

    if (folders.isEmpty()) {
        EmptyState(message = LibraryTabs.emptyMessage(LibraryTabs.Tab.FOLDERS), tab = LibraryTabs.Tab.FOLDERS)
        return
    }

    LazyColumn(
        modifier = Modifier.testTag("folder_list"),
        contentPadding = PaddingValues(ChaosSpacing.L.dp),
        verticalArrangement = Arrangement.spacedBy(ChaosSpacing.S.dp)
    ) {
        items(folders, key = { it.path }) { folder ->
            Card(
                colors = CardDefaults.cardColors(containerColor = tokens.surface),
                shape = RoundedCornerShape(ChaosShape.CARD.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onOpenFolder(folder.path) }
            ) {
                Row(
                    modifier = Modifier.padding(ChaosSpacing.L.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Filled.Folder,
                        contentDescription = null,
                        tint = tokens.accent
                    )
                    Spacer(Modifier.width(ChaosSpacing.M.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = folder.name,
                            style = ChaosType.Body,
                            color = tokens.text,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = "${folder.videoCount} videos · ${folder.sizeLabel}",
                            style = ChaosType.Caption,
                            color = tokens.textMuted
                        )
                    }
                }
            }
        }
    }
}

/** Skeleton cards while the first scan runs (spec 4.1 "loading skeletons"). */
@Composable
private fun LoadingState() {
    val columns = LibraryTabs.gridColumns(LocalConfiguration.current.screenWidthDp)
    Column(
        Modifier
            .fillMaxSize()
            .padding(horizontal = ChaosSpacing.L.dp, vertical = ChaosSpacing.M.dp)
            .semantics { contentDescription = "Loading videos" },
        verticalArrangement = Arrangement.spacedBy(ChaosSpacing.M.dp)
    ) {
        com.chaoswares.chaosplay.ui.theme.SkeletonBlock(Modifier.width(140.dp).height(18.dp))
        repeat(3) {
            Row(horizontalArrangement = Arrangement.spacedBy(ChaosSpacing.M.dp)) {
                repeat(columns) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        com.chaoswares.chaosplay.ui.theme.SkeletonBlock(Modifier.fillMaxWidth().aspectRatio(16f / 9f), corner = ChaosShape.CARD.dp)
                        com.chaoswares.chaosplay.ui.theme.SkeletonBlock(Modifier.fillMaxWidth(0.8f).height(12.dp))
                        com.chaoswares.chaosplay.ui.theme.SkeletonBlock(Modifier.fillMaxWidth(0.5f).height(10.dp))
                    }
                }
            }
        }
    }
}

/**
 * Empty state, spec 2.6: "friendly illustration, one-line explanation, one
 * action button" - the action is whatever helps from here.
 */
@Composable
private fun EmptyState(
    message: String,
    tab: LibraryTabs.Tab,
    searching: Boolean = false,
    onShowAll: () -> Unit = {},
    onRescan: () -> Unit = {}
) {
    val tokens = chaosTokens()
    val (icon, hint) = when {
        searching -> Icons.Filled.Search to "Try a shorter word or another spelling."
        tab == LibraryTabs.Tab.FAVORITES -> Icons.Filled.FavoriteBorder to "Long-press any video and choose Add to favorites."
        tab == LibraryTabs.Tab.RECENT -> Icons.Filled.VideocamOff to "Videos added in the last 7 days show up here."
        tab == LibraryTabs.Tab.FOLDERS -> Icons.Filled.Folder to "Folders appear once they contain videos."
        else -> Icons.Filled.VideocamOff to "Copy videos to this phone, or stream from the Network tab."
    }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(ChaosSpacing.XL.dp)
        ) {
            Box(
                Modifier
                    .size(96.dp)
                    .clip(CircleShape)
                    .background(tokens.accent.copy(alpha = 0.12f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(icon, contentDescription = null, tint = tokens.accent, modifier = Modifier.size(44.dp))
            }
            Spacer(Modifier.height(ChaosSpacing.L.dp))
            Text(message, style = ChaosType.Heading, color = tokens.text, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            Spacer(Modifier.height(ChaosSpacing.XS.dp))
            Text(hint, style = ChaosType.Body, color = tokens.textMuted, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            Spacer(Modifier.height(ChaosSpacing.L.dp))
            when {
                searching -> Unit
                tab == LibraryTabs.Tab.ALL -> androidx.compose.material3.OutlinedButton(onClick = onRescan) { Text("Scan again") }
                else -> androidx.compose.material3.OutlinedButton(onClick = onShowAll) { Text("Show all videos") }
            }
        }
    }
}

@Composable
private fun ErrorState(message: String, onRetry: () -> Unit) {
    val tokens = chaosTokens()
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(ChaosSpacing.XL.dp)
        ) {
            Text("Something went wrong", style = ChaosType.Heading, color = tokens.danger)
            Spacer(Modifier.height(ChaosSpacing.S.dp))
            Text(message, style = ChaosType.Caption, color = tokens.textMuted)
            Spacer(Modifier.height(ChaosSpacing.L.dp))
            Button(onClick = onRetry) { Text("Try again") }
        }
    }
}

@Composable
private fun PermissionState(onRetry: () -> Unit) {
    val tokens = chaosTokens()
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(ChaosSpacing.XL.dp)
        ) {
            Icon(
                Icons.Filled.GridView,
                contentDescription = null,
                tint = tokens.accent,
                modifier = Modifier.size(48.dp)
            )
            Spacer(Modifier.height(ChaosSpacing.M.dp))
            Text("Permission needed", style = ChaosType.Heading, color = tokens.text)
            Spacer(Modifier.height(ChaosSpacing.XS.dp))
            Text(
                text = "CPlay needs access to your videos to show your library. " +
                    "It never uploads anything.",
                style = ChaosType.Caption,
                color = tokens.textMuted
            )
            Spacer(Modifier.height(ChaosSpacing.L.dp))
            Button(onClick = onRetry) { Text("Grant access") }
        }
    }
}
