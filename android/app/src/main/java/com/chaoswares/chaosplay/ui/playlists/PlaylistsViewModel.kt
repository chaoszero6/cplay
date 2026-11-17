package com.chaoswares.chaosplay.ui.playlists

import android.app.Application
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.chaoswares.chaosplay.data.LibraryMetaStore
import com.chaoswares.chaosplay.data.M3u
import com.chaoswares.chaosplay.data.Playlist
import com.chaoswares.chaosplay.data.PlaylistItem
import com.chaoswares.chaosplay.data.PlaylistRules
import com.chaoswares.chaosplay.data.ResumeCodec
import com.chaoswares.chaosplay.data.SettingsStore
import com.chaoswares.chaosplay.data.VideoItem
import com.chaoswares.chaosplay.network.StreamRules
import com.chaoswares.chaosplay.player.PlayQueue
import com.chaoswares.chaosplay.player.WatchEntry
import com.chaoswares.chaosplay.subtitles.SubtitleEncodingDetector
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

/** Playlists and history, spec 4.7. */
class PlaylistsViewModel(app: Application) : AndroidViewModel(app) {

    private val meta = LibraryMetaStore(app)
    private val settings = SettingsStore(app)

    val playlists: StateFlow<List<Playlist>> =
        meta.playlists.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val history: StateFlow<List<WatchEntry>> =
        settings.resumeRaw.map { ResumeCodec.decode(it) }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /**
     * The playlist open in the detail view. Kept here, not in composition
     * state: the whole tab host leaves composition while a video plays, and
     * coming back must land on the same playlist.
     */
    val openPlaylistId = kotlinx.coroutines.flow.MutableStateFlow<String?>(null)

    /** History reads the resume table live; nothing to do, kept for call sites. */
    fun refreshHistory() = Unit

    fun create(name: String, then: (String) -> Unit = {}) {
        val id = UUID.randomUUID().toString()
        viewModelScope.launch {
            meta.updatePlaylists { PlaylistRules.create(it, name, id, System.currentTimeMillis() / 1000) }
            then(id)
        }
    }

    fun rename(id: String, name: String) = edit { PlaylistRules.rename(it, id, name) }
    fun delete(id: String) = edit { PlaylistRules.delete(it, id) }
    fun addItems(id: String, items: List<PlaylistItem>) = edit { PlaylistRules.addItems(it, id, items) }
    fun removeItem(id: String, index: Int) = edit { PlaylistRules.removeItem(it, id, index) }
    fun move(id: String, from: Int, to: Int) = edit { PlaylistRules.moveItem(it, id, from, to) }

    private fun edit(transform: (List<Playlist>) -> List<Playlist>) {
        viewModelScope.launch { meta.updatePlaylists(transform) }
    }

    fun queueFor(playlist: Playlist, startIndex: Int = 0, shuffle: Boolean = false): PlayQueue {
        val q = PlayQueue.of(playlist.items.map { it.toQueueItem() }, startIndex)
        return if (shuffle) q.withShuffle(true, System.currentTimeMillis()) else q
    }

    fun clearHistory(then: () -> Unit) {
        viewModelScope.launch {
            settings.setResumeRaw("")
            then()
        }
    }

    /**
     * Imports an M3U/M3U8 file. Local entries are matched against the
     * device library (path, then file name); URLs are kept as streams;
     * entries for files not on this device are skipped and counted.
     */
    fun importM3u(uri: Uri, library: List<VideoItem>, onResult: (String) -> Unit) {
        val app = getApplication<Application>()
        viewModelScope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    val bytes = app.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: error("Cannot open the file")
                    val text = SubtitleEncodingDetector.decode(bytes)
                    val name = app.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                        ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
                        ?.substringBeforeLast('.') ?: "Imported"
                    val entries = M3u.parse(text)
                    var skipped = 0
                    val items = entries.mapNotNull { e ->
                        if (M3u.isUrl(e.location)) {
                            PlaylistItem(e.location, e.title ?: StreamRules.displayName(e.location), (e.durationSec ?: 0) * 1000)
                        } else {
                            val match = M3u.resolve(e, library, { it.path }, { it.uri })
                            if (match == null) skipped++
                            match?.let { PlaylistItem(it.uri, e.title ?: it.title, it.durationMs) }
                        }
                    }
                    Triple(name, items, skipped)
                }
            }
            result.onSuccess { (name, items, skipped) ->
                if (items.isEmpty()) {
                    onResult(if (skipped > 0) "None of the $skipped files in that playlist are on this device" else "That playlist is empty")
                    return@onSuccess
                }
                val id = UUID.randomUUID().toString()
                meta.updatePlaylists {
                    val created = PlaylistRules.create(it, name, id, System.currentTimeMillis() / 1000)
                    PlaylistRules.addItems(created, id, items)
                }
                onResult("Imported ${items.size} item${if (items.size == 1) "" else "s"}" + if (skipped > 0) ", $skipped not on this device" else "")
            }.onFailure { onResult("Import failed: ${it.message}") }
        }
    }

    fun exportM3u(playlist: Playlist, target: Uri, library: List<VideoItem>, onResult: (String) -> Unit) {
        val app = getApplication<Application>()
        viewModelScope.launch {
            val ok = runCatching {
                withContext(Dispatchers.IO) {
                    // Export real paths where we know them so other players can open the list
                    val byUri = library.associateBy { it.uri }
                    val items = playlist.items.map { item ->
                        val path = byUri[item.uri]?.path?.takeIf { it.isNotBlank() }
                        if (path != null) item.copy(uri = path) else item
                    }
                    app.contentResolver.openOutputStream(target, "wt")?.use {
                        it.write(M3u.write(items).toByteArray(Charsets.UTF_8))
                    } ?: error("Cannot write the file")
                }
            }.isSuccess
            onResult(if (ok) "Exported ${playlist.items.size} items" else "Export failed")
        }
    }
}
