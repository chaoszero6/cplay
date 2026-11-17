package com.chaoswares.chaosplay.ui.network

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.chaoswares.chaosplay.network.JellyfinRules
import com.chaoswares.chaosplay.network.NetworkError
import com.chaoswares.chaosplay.network.NetworkRepository
import com.chaoswares.chaosplay.network.RemoteEntry
import com.chaoswares.chaosplay.network.SavedStream
import com.chaoswares.chaosplay.network.ServerProfile
import com.chaoswares.chaosplay.network.ServerType
import com.chaoswares.chaosplay.network.StreamRules
import com.chaoswares.chaosplay.network.WebDavRules
import com.chaoswares.chaosplay.player.PlayQueue
import com.chaoswares.chaosplay.player.QueueItem
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Where the Network tab is (a back stack inside the tab). */
sealed class NetDest {
    object Home : NetDest()
    data class JellyfinHome(val profileId: String) : NetDest()
    data class JellyfinFolder(val profileId: String, val parentId: String, val title: String) : NetDest()
    data class JellyfinSearch(val profileId: String, val query: String) : NetDest()
    data class Dav(val profileId: String, val url: String) : NetDest()
    /** SMB / FTP / SFTP / NFS folder; [path] is "/share/dir" style. */
    data class Share(val profileId: String, val path: String) : NetDest()
    data class Dlna(val profileId: String, val objectId: String, val title: String) : NetDest()
}

/** The add-server form (spec 4.6 "Add Jellyfin flow", "Add SMB flow"). */
data class AddForm(
    val type: ServerType,
    val address: String = "",
    val username: String = "",
    val password: String = "",
    val name: String = "",
    val trustAll: Boolean = false,
    val testing: Boolean = false,
    val error: String? = null,
    val quickConnectCode: String? = null
)

data class BrowseState(
    val loading: Boolean = false,
    val error: String? = null,
    val entries: List<RemoteEntry> = emptyList(),
    val home: NetworkRepository.JellyfinHome? = null,
    val showAllFiles: Boolean = false
)

/**
 * Network tab state: saved servers and streams, LAN discovery, the add
 * flows, and browsing Jellyfin libraries and WebDAV/Nextcloud folders.
 */
class NetworkViewModel(app: Application) : AndroidViewModel(app) {

    val repo = NetworkRepository.get(app)

    val profiles: StateFlow<List<ServerProfile>> = repo.profiles.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val streams: StateFlow<List<SavedStream>> = repo.savedStreams.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private val _stack = MutableStateFlow<List<NetDest>>(listOf(NetDest.Home))
    val stack: StateFlow<List<NetDest>> = _stack.asStateFlow()

    private val _browse = MutableStateFlow(BrowseState())
    val browse: StateFlow<BrowseState> = _browse.asStateFlow()

    private val _form = MutableStateFlow<AddForm?>(null)
    val form: StateFlow<AddForm?> = _form.asStateFlow()

    private val _discovered = MutableStateFlow<List<JellyfinRules.Discovered>>(emptyList())
    val discovered: StateFlow<List<JellyfinRules.Discovered>> = _discovered.asStateFlow()

    private val _discovering = MutableStateFlow(false)
    val discovering: StateFlow<Boolean> = _discovering.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    private var loadJob: Job? = null
    private var quickConnectJob: Job? = null

    init {
        viewModelScope.launch { repo.ensureCredentials() }
    }

    fun consumeMessage() {
        _message.value = null
    }

    val current: NetDest get() = _stack.value.last()

    // ---- navigation ----

    fun push(dest: NetDest) {
        _stack.update { it + dest }
        load()
    }

    /** Returns false when already at the tab's home (let the system handle Back). */
    fun back(): Boolean {
        if (_stack.value.size <= 1) return false
        _stack.update { it.dropLast(1) }
        load()
        return true
    }

    fun home() {
        _stack.value = listOf(NetDest.Home)
        _browse.value = BrowseState()
    }

    fun reload() = load()

    private fun load() {
        loadJob?.cancel()
        val dest = current
        if (dest is NetDest.Home) {
            _browse.value = BrowseState()
            return
        }
        _browse.update { it.copy(loading = true, error = null, entries = if (it.loading) it.entries else emptyList(), home = null) }
        loadJob = viewModelScope.launch {
            try {
                when (dest) {
                    is NetDest.JellyfinHome -> {
                        val p = profileOrThrow(dest.profileId)
                        _browse.update { it.copy(loading = false, home = repo.jellyfinHome(p)) }
                    }
                    is NetDest.JellyfinFolder -> {
                        val p = profileOrThrow(dest.profileId)
                        _browse.update { it.copy(loading = false, entries = repo.jellyfinBrowse(p, dest.parentId)) }
                    }
                    is NetDest.JellyfinSearch -> {
                        val p = profileOrThrow(dest.profileId)
                        _browse.update { it.copy(loading = false, entries = repo.jellyfinSearch(p, dest.query)) }
                    }
                    is NetDest.Dav -> {
                        val p = profileOrThrow(dest.profileId)
                        _browse.update { it.copy(loading = false, entries = repo.webDavList(p, dest.url, it.showAllFiles)) }
                    }
                    is NetDest.Share -> {
                        val p = profileOrThrow(dest.profileId)
                        _browse.update { it.copy(loading = false, entries = repo.shareList(p, dest.path, it.showAllFiles)) }
                    }
                    is NetDest.Dlna -> {
                        val p = profileOrThrow(dest.profileId)
                        _browse.update { it.copy(loading = false, entries = repo.dlnaBrowse(p, dest.objectId)) }
                    }
                    NetDest.Home -> Unit
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                _browse.update { it.copy(loading = false, error = e.message ?: "Could not load") }
            }
        }
    }

    private suspend fun profileOrThrow(id: String): ServerProfile =
        repo.profile(id) ?: throw NetworkError("This server was removed")

    fun toggleShowAllFiles() {
        _browse.update { it.copy(showAllFiles = !it.showAllFiles) }
        load()
    }

    fun openProfile(p: ServerProfile) {
        when (p.type) {
            ServerType.JELLYFIN -> push(NetDest.JellyfinHome(p.id))
            ServerType.NEXTCLOUD, ServerType.WEBDAV -> push(NetDest.Dav(p.id, p.baseUrl))
            ServerType.SMB, ServerType.FTP, ServerType.SFTP, ServerType.NFS -> push(NetDest.Share(p.id, "/"))
            ServerType.DLNA -> push(NetDest.Dlna(p.id, com.chaoswares.chaosplay.network.DlnaRules.ROOT_ID, p.name))
            else -> Unit
        }
    }

    fun search(query: String) {
        val dest = _stack.value.lastOrNull { it is NetDest.JellyfinHome || it is NetDest.JellyfinFolder || it is NetDest.JellyfinSearch }
        val profileId = when (dest) {
            is NetDest.JellyfinHome -> dest.profileId
            is NetDest.JellyfinFolder -> dest.profileId
            is NetDest.JellyfinSearch -> dest.profileId
            else -> return
        }
        if (query.isBlank()) return
        if (current is NetDest.JellyfinSearch) _stack.update { it.dropLast(1) }
        push(NetDest.JellyfinSearch(profileId, query.trim()))
    }

    /** Folder entries navigate; playable entries return a queue of their playable siblings. */
    fun open(entry: RemoteEntry, siblings: List<RemoteEntry>): PlayQueue? {
        val dest = current
        if (entry.isFolder) {
            when (dest) {
                is NetDest.Dav -> push(NetDest.Dav(dest.profileId, entry.id))
                is NetDest.JellyfinHome -> push(NetDest.JellyfinFolder(dest.profileId, entry.id, entry.name))
                is NetDest.JellyfinFolder -> push(NetDest.JellyfinFolder(dest.profileId, entry.id, entry.name))
                is NetDest.JellyfinSearch -> push(NetDest.JellyfinFolder(dest.profileId, entry.id, entry.name))
                is NetDest.Share -> push(NetDest.Share(dest.profileId, entry.id))
                is NetDest.Dlna -> push(NetDest.Dlna(dest.profileId, entry.id, entry.name))
                NetDest.Home -> Unit
            }
            return null
        }
        if (!entry.isPlayable || (entry.kind != "video" && entry.kind != "audio" && (dest is NetDest.Dav || dest is NetDest.Share))) {
            _message.value = "Only video and audio files can be played"
            return null
        }
        return queueFor(entry, siblings, startOver = false)
    }

    fun queueFor(entry: RemoteEntry, siblings: List<RemoteEntry>, startOver: Boolean): PlayQueue {
        val playable = siblings.filter { it.isPlayable && (it.kind == "video" || it.kind == "audio" || JellyfinRules.isItemKey(it.playUri)) }
            .ifEmpty { listOf(entry) }
        val index = playable.indexOfFirst { it.playUri == entry.playUri }.coerceAtLeast(0)
        return PlayQueue.of(
            playable.map { e ->
                QueueItem(
                    uri = e.playUri,
                    title = e.name,
                    durationMs = e.durationMs,
                    // Jellyfin subtitle URLs carry a token, so they are resolved at play time instead
                    subtitleUris = if (JellyfinRules.isItemKey(e.playUri)) emptyList() else e.subtitleUris,
                    resumeHintMs = if (startOver && e.playUri == entry.playUri) -1L else e.resumeMs
                )
            },
            index
        )
    }

    // ---- add server ----

    fun startAdd(type: ServerType, address: String = "") {
        quickConnectJob?.cancel()
        _form.value = AddForm(type = type, address = address)
    }

    fun updateForm(transform: (AddForm) -> AddForm) {
        _form.update { it?.let(transform) }
    }

    fun cancelAdd() {
        quickConnectJob?.cancel()
        _form.value = null
    }

    fun submitAdd(onPlayUrl: (PlayQueue) -> Unit) {
        val f = _form.value ?: return
        if (f.testing) return
        _form.value = f.copy(testing = true, error = null)
        viewModelScope.launch {
            try {
                when (f.type) {
                    ServerType.JELLYFIN -> {
                        if (f.username.isBlank()) throw NetworkError("Enter your Jellyfin user name")
                        val p = repo.jellyfinSignIn(f.address, f.username, f.password, f.trustAll)
                        _form.value = null
                        _message.value = "Signed in to ${p.name}"
                        push(NetDest.JellyfinHome(p.id))
                    }
                    ServerType.NEXTCLOUD, ServerType.WEBDAV -> {
                        val p = repo.webDavConnect(f.type, f.address, f.username, f.password, f.name, f.trustAll)
                        _form.value = null
                        _message.value = "Connected to ${p.name}"
                        push(NetDest.Dav(p.id, p.baseUrl))
                    }
                    ServerType.SMB, ServerType.FTP, ServerType.SFTP, ServerType.NFS -> {
                        val p = repo.shareConnect(f.type, f.address, f.username, f.password, f.name)
                        _form.value = null
                        _message.value = "Connected to ${p.name}"
                        push(NetDest.Share(p.id, "/"))
                    }
                    ServerType.DLNA -> {
                        val p = repo.dlnaConnect(f.address, f.name)
                        _form.value = null
                        _message.value = "Added ${p.name}"
                        push(NetDest.Dlna(p.id, com.chaoswares.chaosplay.network.DlnaRules.ROOT_ID, p.name))
                    }
                    ServerType.URL -> {
                        when (val v = StreamRules.validate(f.address)) {
                            is StreamRules.Validation.Invalid -> throw NetworkError(v.reason)
                            is StreamRules.Validation.Valid -> {
                                repo.saveStream(f.name, v.url)
                                _form.value = null
                                _message.value = "Saved"
                                onPlayUrl(PlayQueue.single(QueueItem(v.url, f.name.ifBlank { StreamRules.displayName(v.url) })))
                            }
                        }
                    }
                    else -> throw NetworkError("${f.type.label} is not supported yet")
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                _form.update { it?.copy(testing = false, error = e.message ?: "Connection failed") }
            }
        }
    }

    /** Jellyfin Quick Connect: show a code, wait until it is approved elsewhere (19.2). */
    fun startQuickConnect() {
        val f = _form.value ?: return
        quickConnectJob?.cancel()
        _form.value = f.copy(testing = true, error = null)
        quickConnectJob = viewModelScope.launch {
            try {
                val (base, qc) = repo.jellyfinQuickConnectStart(f.address)
                _form.update { it?.copy(quickConnectCode = qc.code, testing = true) }
                repeat(60) {
                    delay(3_000)
                    val profile = repo.jellyfinQuickConnectPoll(base, qc.secret)
                    if (profile != null) {
                        _form.value = null
                        _message.value = "Signed in to ${profile.name}"
                        push(NetDest.JellyfinHome(profile.id))
                        return@launch
                    }
                }
                _form.update { it?.copy(testing = false, quickConnectCode = null, error = "Quick Connect code expired") }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                _form.update { it?.copy(testing = false, quickConnectCode = null, error = e.message ?: "Quick Connect failed") }
            }
        }
    }

    // ---- saved items ----

    fun removeProfile(id: String) {
        viewModelScope.launch {
            repo.removeProfile(id)
            _message.value = "Server removed"
        }
    }

    fun removeStream(id: String) {
        viewModelScope.launch { repo.removeStream(id) }
    }

    fun saveStream(name: String, url: String) {
        viewModelScope.launch {
            repo.saveStream(name, url)
            _message.value = "Saved"
        }
    }

    fun clearAll() {
        viewModelScope.launch {
            repo.clearAll()
            home()
            _message.value = "Saved servers and passwords removed"
        }
    }

    private val _discoveredDlna = MutableStateFlow<List<NetworkRepository.DlnaFound>>(emptyList())
    val discoveredDlna: StateFlow<List<NetworkRepository.DlnaFound>> = _discoveredDlna.asStateFlow()

    /** Looks for Jellyfin (UDP 7359) and DLNA media servers (SSDP) at the same time. */
    fun discover() {
        if (_discovering.value) return
        _discovering.value = true
        viewModelScope.launch {
            val dlna = async { repo.discoverDlna() }
            _discovered.value = repo.discoverJellyfin()
            _discoveredDlna.value = dlna.await()
            _discovering.value = false
            if (_discovered.value.isEmpty() && _discoveredDlna.value.isEmpty()) {
                _message.value = "No Jellyfin or DLNA server answered on this network"
            }
        }
    }

    fun connectDlna(found: NetworkRepository.DlnaFound) {
        viewModelScope.launch {
            try {
                val p = repo.dlnaConnect(found.location, found.name)
                _message.value = "Added ${p.name}"
                push(NetDest.Dlna(p.id, com.chaoswares.chaosplay.network.DlnaRules.ROOT_ID, p.name))
            } catch (e: Exception) {
                _message.value = e.message ?: "Could not open that server"
            }
        }
    }

    /** Address of a Nextcloud share link is not supported; explain instead of failing silently. */
    fun describe(p: ServerProfile): String = when (p.type) {
        ServerType.JELLYFIN -> "${JellyfinRules.hostOf(p.baseUrl)} · ${p.username}"
        ServerType.NEXTCLOUD, ServerType.WEBDAV -> "${JellyfinRules.hostOf(p.baseUrl)} · ${p.username.ifBlank { "guest" }}" +
            if (p.baseUrl.startsWith("http://") && StreamRules.isInsecurePublic(p.baseUrl)) " · not encrypted" else ""
        ServerType.SMB, ServerType.FTP, ServerType.SFTP -> p.baseUrl.substringAfter("://") + " · " + p.username.ifBlank { "guest" }
        ServerType.NFS -> p.baseUrl.substringAfter("://")
        ServerType.DLNA -> JellyfinRules.hostOf(p.baseUrl)
        else -> p.baseUrl
    }

    fun breadcrumbs(dest: NetDest.Dav, root: String): List<Pair<String, String>> = WebDavRules.breadcrumbs(dest.url, root)
}
