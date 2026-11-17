package com.chaoswares.chaosplay.ui.playlists

import androidx.activity.compose.BackHandler
import androidx.compose.animation.togetherWith
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.chaoswares.chaosplay.data.Playlist
import com.chaoswares.chaosplay.data.PlaylistItem
import com.chaoswares.chaosplay.data.PlaylistRules
import com.chaoswares.chaosplay.data.VideoItem
import com.chaoswares.chaosplay.network.JellyfinRules
import com.chaoswares.chaosplay.network.StreamRules
import com.chaoswares.chaosplay.player.LibraryRules
import com.chaoswares.chaosplay.player.PlayQueue
import com.chaoswares.chaosplay.player.QueueItem
import com.chaoswares.chaosplay.player.TimeFormat
import com.chaoswares.chaosplay.player.WatchEntry
import com.chaoswares.chaosplay.ui.theme.ChaosShape
import com.chaoswares.chaosplay.ui.theme.ChaosSpacing
import com.chaoswares.chaosplay.ui.theme.ChaosType
import com.chaoswares.chaosplay.ui.theme.HeaderTitle
import com.chaoswares.chaosplay.ui.theme.chaosTokens
import kotlinx.coroutines.launch

/**
 * Playlists / History tab, spec section 4.7:
 * "Playlists: create, reorder, import/export M3U" and
 * "History: grouped by day; clear history button with confirmation".
 */
@Composable
fun PlaylistsScreen(
    viewModel: PlaylistsViewModel,
    videos: List<VideoItem>,
    onPlay: (PlayQueue) -> Unit,
    onHistoryCleared: () -> Unit
) {
    val tokens = chaosTokens()
    val playlists by viewModel.playlists.collectAsState()
    val watchEntries by viewModel.history.collectAsState()
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    fun toast(msg: String) { scope.launch { snackbar.showSnackbar(msg) } }

    val openId by viewModel.openPlaylistId.collectAsState()
    var creating by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf<Playlist?>(null) }
    var deleting by remember { mutableStateOf<Playlist?>(null) }
    var confirmClear by remember { mutableStateOf(false) }
    var exporting by remember { mutableStateOf<Playlist?>(null) }

    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) viewModel.importM3u(uri, videos) { toast(it) }
    }
    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("audio/x-mpegurl")) { uri ->
        val p = exporting
        if (uri != null && p != null) viewModel.exportM3u(p, uri, videos) { toast(it) }
        exporting = null
    }

    val openNow = playlists.firstOrNull { it.id == openId }
    Box(Modifier.fillMaxSize().background(tokens.bg)) {
      androidx.compose.animation.AnimatedContent(
        targetState = openNow,
        contentKey = { it?.id },
        transitionSpec = {
            val dir = if (targetState != null) 1 else -1
            (androidx.compose.animation.slideInHorizontally(androidx.compose.animation.core.tween(220)) { w -> dir * w / 4 } +
                androidx.compose.animation.fadeIn(androidx.compose.animation.core.tween(220)))
                .togetherWith(androidx.compose.animation.fadeOut(androidx.compose.animation.core.tween(150)))
        },
        label = "playlistNav"
      ) { open ->
        if (open != null) {
            BackHandler { viewModel.openPlaylistId.value = null }
            PlaylistDetail(
                playlist = open,
                onBack = { viewModel.openPlaylistId.value = null },
                onPlay = { index, shuffle -> onPlay(viewModel.queueFor(open, index, shuffle)) },
                onMove = { from, to -> viewModel.move(open.id, from, to) },
                onRemove = { viewModel.removeItem(open.id, it) },
                onRename = { renaming = open },
                onExport = { exporting = open; exportLauncher.launch("${open.name}.m3u8") },
                onDelete = { deleting = open }
            )
        } else {
            val byUri = remember(videos) { videos.associateBy { it.uri } }
            val history = remember(watchEntries, byUri) {
                watchEntries
                    .filter { it.isStarted() }
                    .filter { byUri.containsKey(it.uri) || StreamRules.isNetworkUrl(it.uri) || JellyfinRules.isItemKey(it.uri) }
                    .sortedByDescending { it.lastPlayedAtSec }
            }
            val grouped = remember(history) { HistoryGroups.group(history) }

            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = ChaosSpacing.XXL.dp),
                verticalArrangement = Arrangement.spacedBy(ChaosSpacing.S.dp)
            ) {
                item {
                    Row(
                        modifier = Modifier
                            .statusBarsPadding()
                            .padding(start = ChaosSpacing.L.dp, end = ChaosSpacing.S.dp, top = ChaosSpacing.L.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        HeaderTitle("Playlists & History", color = tokens.text, modifier = Modifier.weight(1f))
                        if (history.isNotEmpty()) {
                            IconButton(onClick = { confirmClear = true }) {
                                Icon(Icons.Filled.DeleteSweep, contentDescription = "Clear history", tint = tokens.textMuted)
                            }
                        }
                    }
                }
                item {
                    Row(
                        modifier = Modifier.padding(horizontal = ChaosSpacing.L.dp),
                        horizontalArrangement = Arrangement.spacedBy(ChaosSpacing.S.dp)
                    ) {
                        Button(onClick = { creating = true }) {
                            Icon(Icons.Filled.Add, contentDescription = null)
                            Spacer(Modifier.width(4.dp))
                            Text("New playlist")
                        }
                        OutlinedButton(onClick = { importLauncher.launch(arrayOf("audio/x-mpegurl", "audio/mpegurl", "application/vnd.apple.mpegurl", "application/x-mpegurl", "*/*")) }) {
                            Icon(Icons.Filled.FileDownload, contentDescription = null)
                            Spacer(Modifier.width(4.dp))
                            Text("Import M3U")
                        }
                    }
                }
                if (playlists.isEmpty()) {
                    item {
                        EmptyCard(
                            "No playlists yet",
                            "Create one here, or long-press any video › Add to playlist."
                        )
                    }
                }
                items(playlists, key = { "p-${it.id}" }) { playlist ->
                    PlaylistCard(playlist = playlist, onOpen = { viewModel.openPlaylistId.value = playlist.id }, onPlay = {
                        if (playlist.items.isNotEmpty()) onPlay(viewModel.queueFor(playlist)) else toast("This playlist is empty")
                    })
                }

                item {
                    Text(
                        "History",
                        style = ChaosType.Heading,
                        color = tokens.text,
                        modifier = Modifier.padding(start = ChaosSpacing.L.dp, top = ChaosSpacing.L.dp)
                    )
                }
                if (history.isEmpty()) {
                    item { EmptyCard("No watch history yet", "Videos you play show up here, newest first.") }
                }
                grouped.forEach { group ->
                    item(key = "h-${group.label}") {
                        Text(
                            group.label,
                            style = ChaosType.Caption,
                            color = tokens.accent,
                            modifier = Modifier.padding(start = ChaosSpacing.L.dp, top = ChaosSpacing.S.dp)
                        )
                    }
                    items(group.entries, key = { "e-${it.uri}" }) { entry ->
                        val video = byUri[entry.uri]
                        val title = video?.title ?: if (JellyfinRules.isItemKey(entry.uri)) "Jellyfin video" else StreamRules.displayName(entry.uri)
                        HistoryRow(title = title, entry = entry, onClick = {
                            onPlay(PlayQueue.single(QueueItem(entry.uri, if (JellyfinRules.isItemKey(entry.uri)) "" else title, entry.durationMs)))
                        })
                    }
                }
            }
        }
      }
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter))
    }

    if (creating) {
        NameDialog("New playlist", "", "Create", onDismiss = { creating = false }) { name ->
            creating = false
            viewModel.create(name) { id -> viewModel.openPlaylistId.value = id }
        }
    }
    renaming?.let { p ->
        NameDialog("Rename playlist", p.name, "Save", onDismiss = { renaming = null }) { name ->
            renaming = null
            viewModel.rename(p.id, name)
        }
    }
    deleting?.let { p ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            containerColor = tokens.surface,
            title = { Text("Delete \"${p.name}\"?", color = tokens.text) },
            text = { Text("The videos stay on your device.", color = tokens.textMuted) },
            confirmButton = {
                TextButton(onClick = { viewModel.delete(p.id); deleting = null; viewModel.openPlaylistId.value = null }) { Text("Delete", color = tokens.danger) }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Cancel") } }
        )
    }
    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            containerColor = tokens.surface,
            shape = RoundedCornerShape(ChaosShape.CARD.dp),
            title = { Text("Clear history?", style = ChaosType.Heading, color = tokens.text) },
            text = {
                Text(
                    "This removes your watch history and resume positions. The video files themselves are not touched.",
                    style = ChaosType.Body,
                    color = tokens.textMuted
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmClear = false
                    viewModel.clearHistory(onHistoryCleared)
                }) { Text("Clear", color = tokens.danger) }
            },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Cancel", color = tokens.accent) } }
        )
    }
}

@Composable
private fun PlaylistCard(playlist: Playlist, onOpen: () -> Unit, onPlay: () -> Unit) {
    val tokens = chaosTokens()
    Card(
        colors = CardDefaults.cardColors(containerColor = tokens.surface),
        shape = RoundedCornerShape(ChaosShape.CARD.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = ChaosSpacing.L.dp)
            .clickable(onClick = onOpen)
    ) {
        Row(Modifier.padding(ChaosSpacing.M.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.AutoMirrored.Filled.QueueMusic, contentDescription = null, tint = tokens.accent)
            Spacer(Modifier.width(ChaosSpacing.M.dp))
            Column(Modifier.weight(1f)) {
                Text(playlist.name, style = ChaosType.Body, color = tokens.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    "${playlist.items.size} item${if (playlist.items.size == 1) "" else "s"}" +
                        if (playlist.totalDurationMs > 0) " · ${TimeFormat.format(playlist.totalDurationMs)}" else "",
                    style = ChaosType.Caption,
                    color = tokens.textMuted
                )
            }
            IconButton(onClick = onPlay) { Icon(Icons.Filled.PlayArrow, contentDescription = "Play ${playlist.name}", tint = tokens.accent) }
        }
    }
}

@Composable
private fun PlaylistDetail(
    playlist: Playlist,
    onBack: () -> Unit,
    onPlay: (Int, Boolean) -> Unit,
    onMove: (Int, Int) -> Unit,
    onRemove: (Int) -> Unit,
    onRename: () -> Unit,
    onExport: () -> Unit,
    onDelete: () -> Unit
) {
    val tokens = chaosTokens()
    var menu by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier
                .statusBarsPadding()
                .padding(horizontal = ChaosSpacing.XS.dp, vertical = ChaosSpacing.S.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = tokens.text) }
            Column(Modifier.weight(1f)) {
                Text(playlist.name, style = ChaosType.Title, color = tokens.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("${playlist.items.size} items", style = ChaosType.Caption, color = tokens.textMuted)
            }
            Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, contentDescription = "Playlist options", tint = tokens.text) }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text("Rename") }, onClick = { menu = false; onRename() })
                    DropdownMenuItem(text = { Text("Export as M3U") }, onClick = { menu = false; onExport() })
                    DropdownMenuItem(text = { Text("Delete playlist") }, onClick = { menu = false; onDelete() })
                }
            }
        }
        Row(Modifier.padding(horizontal = ChaosSpacing.L.dp), horizontalArrangement = Arrangement.spacedBy(ChaosSpacing.S.dp)) {
            Button(onClick = { onPlay(0, false) }, enabled = playlist.items.isNotEmpty()) {
                Icon(Icons.Filled.PlayArrow, contentDescription = null)
                Spacer(Modifier.width(4.dp))
                Text("Play all")
            }
            OutlinedButton(onClick = { onPlay(0, true) }, enabled = playlist.items.size > 1) {
                Icon(Icons.Filled.Shuffle, contentDescription = null)
                Spacer(Modifier.width(4.dp))
                Text("Shuffle")
            }
        }
        if (playlist.items.isEmpty()) {
            EmptyCard("Empty playlist", "Long-press a video in the library and choose Add to playlist.")
        }
        LazyColumn(contentPadding = PaddingValues(vertical = ChaosSpacing.S.dp)) {
            itemsIndexed(playlist.items) { index, item ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable { onPlay(index, false) }
                        .padding(start = ChaosSpacing.L.dp, end = ChaosSpacing.XS.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("${index + 1}", style = ChaosType.Caption, color = tokens.textMuted, modifier = Modifier.width(24.dp))
                    Column(Modifier.weight(1f)) {
                        Text(item.title.ifBlank { item.uri.substringAfterLast('/') }, style = ChaosType.Body, color = tokens.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            listOfNotNull(
                                item.durationMs.takeIf { it > 0 }?.let { TimeFormat.format(it) },
                                if (StreamRules.isNetworkUrl(item.uri)) "Stream" else null
                            ).joinToString(" · "),
                            style = ChaosType.Caption,
                            color = tokens.textMuted
                        )
                    }
                    IconButton(onClick = { onMove(index, index - 1) }, enabled = index > 0) {
                        Icon(Icons.Filled.KeyboardArrowUp, contentDescription = "Move up", tint = tokens.textMuted)
                    }
                    IconButton(onClick = { onMove(index, index + 1) }, enabled = index < playlist.items.lastIndex) {
                        Icon(Icons.Filled.KeyboardArrowDown, contentDescription = "Move down", tint = tokens.textMuted)
                    }
                    IconButton(onClick = { onRemove(index) }) {
                        Icon(Icons.Filled.Close, contentDescription = "Remove from playlist", tint = tokens.textMuted)
                    }
                }
            }
        }
    }
}

/**
 * "Add to playlist" from the library long-press menu: pick a playlist or
 * create a new one in place.
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun AddToPlaylistSheet(viewModel: PlaylistsViewModel, items: List<PlaylistItem>, onDone: (String?) -> Unit) {
    val tokens = chaosTokens()
    val playlists by viewModel.playlists.collectAsState()
    var creating by remember { mutableStateOf(false) }
    ModalBottomSheet(onDismissRequest = { onDone(null) }, containerColor = tokens.surface) {
        Column(Modifier.navigationBarsPadding().padding(bottom = ChaosSpacing.L.dp)) {
            Text("Add to playlist", style = ChaosType.Heading, color = tokens.text, modifier = Modifier.padding(horizontal = ChaosSpacing.L.dp))
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable { creating = true }
                    .padding(horizontal = ChaosSpacing.L.dp, vertical = ChaosSpacing.M.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.AutoMirrored.Filled.PlaylistAdd, contentDescription = null, tint = tokens.accent)
                Spacer(Modifier.width(ChaosSpacing.M.dp))
                Text("New playlist…", style = ChaosType.Body, color = tokens.accent)
            }
            playlists.forEach { p ->
                val already = items.all { PlaylistRules.contains(p, it.uri) }
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable {
                            viewModel.addItems(p.id, items)
                            onDone(if (already) "Already in ${p.name}" else "Added to ${p.name}")
                        }
                        .padding(horizontal = ChaosSpacing.L.dp, vertical = ChaosSpacing.M.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.AutoMirrored.Filled.QueueMusic, contentDescription = null, tint = tokens.textMuted)
                    Spacer(Modifier.width(ChaosSpacing.M.dp))
                    Text(p.name, style = ChaosType.Body, color = tokens.text, modifier = Modifier.weight(1f))
                    Text("${p.items.size}", style = ChaosType.Caption, color = tokens.textMuted)
                    if (already) {
                        Spacer(Modifier.width(ChaosSpacing.S.dp))
                        Icon(Icons.Filled.Check, contentDescription = "Already added", tint = tokens.success)
                    }
                }
            }
        }
    }
    if (creating) {
        NameDialog("New playlist", "", "Create", onDismiss = { creating = false }) { name ->
            creating = false
            viewModel.create(name) { id ->
                viewModel.addItems(id, items)
                onDone("Added to $name")
            }
        }
    }
}

@Composable
private fun NameDialog(title: String, initial: String, confirm: String, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    val tokens = chaosTokens()
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = tokens.surface,
        title = { Text(title, color = tokens.text) },
        text = { OutlinedTextField(value = text, onValueChange = { text = it }, singleLine = true, label = { Text("Name") }) },
        confirmButton = {
            TextButton(onClick = { onConfirm(text) }, enabled = PlaylistRules.cleanName(text) != null) { Text(confirm, color = tokens.accent) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable
private fun EmptyCard(title: String, body: String) {
    val tokens = chaosTokens()
    Card(
        colors = CardDefaults.cardColors(containerColor = tokens.surface),
        shape = RoundedCornerShape(ChaosShape.CARD.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = ChaosSpacing.L.dp)
    ) {
        Row(Modifier.padding(ChaosSpacing.L.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.DateRange, contentDescription = null, tint = tokens.textMuted)
            Spacer(Modifier.width(ChaosSpacing.M.dp))
            Column {
                Text(title, style = ChaosType.Body, color = tokens.text)
                Text(body, style = ChaosType.Caption, color = tokens.textMuted)
            }
        }
    }
}

@Composable
private fun HistoryRow(title: String, entry: WatchEntry, onClick: () -> Unit) {
    val tokens = chaosTokens()
    val hasResume = LibraryRules.shouldShowResume(entry.positionMs, entry.durationMs)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = ChaosSpacing.L.dp, vertical = ChaosSpacing.XS.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .width(88.dp)
                .aspectRatio(16f / 9f)
                .background(tokens.surface2, RoundedCornerShape(6.dp)),
            contentAlignment = Alignment.Center
        ) {
            Icon(Icons.Filled.PlayArrow, contentDescription = null, tint = tokens.textMuted, modifier = Modifier.size(20.dp))
            Text(
                TimeFormat.format(entry.durationMs),
                style = ChaosType.Caption,
                color = Color.White,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .background(Color(0xB3000000))
                    .padding(horizontal = 3.dp)
            )
        }
        Spacer(Modifier.width(ChaosSpacing.M.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = ChaosType.Body, color = tokens.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                if (hasResume) "${TimeFormat.format(entry.positionMs)} · ${TimeFormat.remainingShortLabel(entry.durationMs, entry.positionMs)}" else "Watched",
                style = ChaosType.Caption,
                color = tokens.textMuted
            )
            if (hasResume) {
                Spacer(Modifier.height(ChaosSpacing.XS.dp))
                LinearProgressIndicator(
                    progress = { entry.progressFraction() },
                    modifier = Modifier.fillMaxWidth().height(3.dp),
                    color = tokens.accent,
                    trackColor = tokens.surface2
                )
            }
        }
    }
}

/** One day of history, newest first. */
data class HistoryGroup(val label: String, val entries: List<WatchEntry>)

/**
 * Groups watch entries into Today / Yesterday / the calendar date, per
 * spec 4.7 "History: grouped by day". Pure (no Android) so it is testable.
 */
object HistoryGroups {

    private const val DAY_SEC = 24L * 60L * 60L

    fun group(entries: List<WatchEntry>, nowSec: Long): List<HistoryGroup> {
        val today = nowSec / DAY_SEC
        return entries
            .groupBy { it.lastPlayedAtSec / DAY_SEC }
            .map { (day, list) -> HistoryGroup(labelFor(today, day), list.sortedByDescending { it.lastPlayedAtSec }) }
            .sortedByDescending { it.entries.first().lastPlayedAtSec }
    }

    fun group(entries: List<WatchEntry>): List<HistoryGroup> = group(entries, System.currentTimeMillis() / 1000L)

    fun labelFor(todayDay: Long, entryDay: Long): String {
        val diff = todayDay - entryDay
        return when {
            diff <= 0L -> "Today"
            diff == 1L -> "Yesterday"
            diff < 7L -> "$diff days ago"
            else -> formatDate(entryDay * DAY_SEC)
        }
    }

    private fun formatDate(epochSec: Long): String = runCatching {
        val fmt = java.text.SimpleDateFormat("d MMM yyyy", java.util.Locale.getDefault())
        fmt.format(java.util.Date(epochSec * 1000L))
    }.getOrElse { "Day ${epochSec / DAY_SEC}" }
}

