package com.chaoswares.chaosplay.network

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.chaoswares.chaosplay.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.UUID
import javax.net.ssl.SSLException

private val Context.networkStore by preferencesDataStore(name = "chaosplay_network")

/** A failure with a message written for people, not for logs (spec 4.6). */
class NetworkError(message: String) : Exception(message)

/**
 * Network sources, spec section 19.9 "one source interface": Jellyfin,
 * Nextcloud/WebDAV and saved stream URLs. Screens and the player talk to
 * this class only; every HTTP call goes through [NetHttp.client].
 */
class NetworkRepository private constructor(private val context: Context) {

    init {
        RemoteFsPool.appContext = context.applicationContext
    }

    private val secrets = SecretStore(context)
    private val json = "application/json".toMediaType()

    private object Keys {
        val PROFILES = stringPreferencesKey("server_profiles")
        val STREAMS = stringPreferencesKey("saved_streams")
        val DEVICE_ID = stringPreferencesKey("device_id")
    }

    val profiles: Flow<List<ServerProfile>> =
        context.networkStore.data.map { ServerProfiles.decode(it[Keys.PROFILES] ?: "") }

    val savedStreams: Flow<List<SavedStream>> =
        context.networkStore.data.map { StreamRules.decode(it[Keys.STREAMS] ?: "") }

    @Volatile private var credentialsLoaded = false

    /**
     * Registers stored credentials with [NetHttp] so playback and browsing
     * of saved servers work right after a cold start.
     */
    suspend fun ensureCredentials() {
        if (credentialsLoaded) return
        for (p in profiles.first()) registerCredentials(p)
        credentialsLoaded = true
    }

    private suspend fun registerCredentials(p: ServerProfile) {
        NetHttp.trustHost(JellyfinRules.hostOf(p.baseUrl), p.trustAllCerts)
        when (p.type) {
            ServerType.NEXTCLOUD, ServerType.WEBDAV -> {
                val pw = secrets.get(SecretStore.passwordKey(p.id))
                if (p.username.isNotBlank() && pw != null) {
                    NetHttp.authorise(originOf(p.baseUrl), mapOf("Authorization" to Base64Lite.basicAuth(p.username, pw)))
                }
            }
            ServerType.JELLYFIN -> {
                val token = secrets.get(SecretStore.tokenKey(p.id))
                if (token != null) {
                    NetHttp.authorise(p.baseUrl + "/", mapOf("Authorization" to JellyfinRules.authHeader(deviceId(), BuildConfig.VERSION_NAME, token)))
                }
            }
            else -> Unit
        }
    }

    /** Scheme + host + port: Nextcloud serves files and previews under the same origin. */
    private fun originOf(url: String): String {
        val uri = java.net.URI(url)
        val port = if (uri.port > 0) ":${uri.port}" else ""
        return "${uri.scheme}://${uri.host}$port/"
    }

    suspend fun deviceId(): String {
        val prefs = context.networkStore.data.first()
        prefs[Keys.DEVICE_ID]?.let { return it }
        val id = "chaosplay-" + UUID.randomUUID().toString()
        context.networkStore.edit { it[Keys.DEVICE_ID] = id }
        return id
    }

    suspend fun profile(id: String): ServerProfile? = profiles.first().firstOrNull { it.id == id }

    private suspend fun saveProfile(profile: ServerProfile) {
        context.networkStore.edit { prefs ->
            val list = ServerProfiles.decode(prefs[Keys.PROFILES] ?: "")
            prefs[Keys.PROFILES] = ServerProfiles.encode(ServerProfiles.add(list, profile))
        }
    }

    suspend fun removeProfile(id: String) {
        val p = profile(id)
        RemoteFsPool.drop(id)
        context.networkStore.edit { prefs ->
            val list = ServerProfiles.decode(prefs[Keys.PROFILES] ?: "")
            prefs[Keys.PROFILES] = ServerProfiles.encode(ServerProfiles.remove(list, id))
        }
        secrets.remove(SecretStore.passwordKey(id))
        if (p?.type == ServerType.JELLYFIN) {
            // Best effort: end the server-side session so the token stops working
            runCatching { post(p.baseUrl + "/Sessions/Logout", "", p) }
        }
        secrets.remove(SecretStore.tokenKey(id))
        p?.let {
            NetHttp.forget(originOf(it.baseUrl))
            NetHttp.forget(it.baseUrl + "/")
        }
    }

    /** Settings "Clear saved servers and credentials" (19.8). */
    suspend fun clearAll() {
        for (p in profiles.first()) removeProfile(p.id)
        context.networkStore.edit { it.remove(Keys.STREAMS) }
        secrets.clearAll()
    }

    // ---- saved stream URLs ----

    suspend fun saveStream(name: String, url: String) {
        context.networkStore.edit { prefs ->
            val list = StreamRules.decode(prefs[Keys.STREAMS] ?: "")
            val stream = SavedStream(
                id = UUID.randomUUID().toString(),
                name = name.trim().ifBlank { StreamRules.displayName(url) },
                url = url,
                addedAtSec = System.currentTimeMillis() / 1000
            )
            prefs[Keys.STREAMS] = StreamRules.encode(StreamRules.add(list, stream))
        }
    }

    suspend fun removeStream(id: String) {
        context.networkStore.edit { prefs ->
            val list = StreamRules.decode(prefs[Keys.STREAMS] ?: "")
            prefs[Keys.STREAMS] = StreamRules.encode(StreamRules.remove(list, id))
        }
    }

    // ---- HTTP helpers ----

    private suspend fun execute(request: Request): String = withContext(Dispatchers.IO) {
        try {
            NetHttp.client.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                if (!response.isSuccessful && response.code != 207) {
                    throw HttpStatus(response.code, body)
                }
                body
            }
        } catch (e: HttpStatus) {
            throw e
        } catch (e: UnknownHostException) {
            throw NetworkError("Server not found. Check the address and your Wi-Fi")
        } catch (e: SocketTimeoutException) {
            throw NetworkError("Server unreachable. Check Wi-Fi or VPN")
        } catch (e: SSLException) {
            throw NetworkError("Secure connection failed. If this server uses a self-signed certificate, tick \"Trust this server's certificate\"")
        } catch (e: java.net.ConnectException) {
            throw NetworkError("Server unreachable. Check Wi-Fi, the port, or that the server is running")
        } catch (e: IOException) {
            throw NetworkError("Connection problem: ${e.message ?: "network error"}")
        }
    }

    private class HttpStatus(val code: Int, val body: String) : IOException("HTTP $code")

    private suspend fun jfHeaders(p: ServerProfile?, builder: Request.Builder): Request.Builder {
        val token = p?.let { secrets.get(SecretStore.tokenKey(it.id)) }
        val header = JellyfinRules.authHeader(deviceId(), BuildConfig.VERSION_NAME, token)
        return builder.header("Authorization", header).header("X-Emby-Authorization", header)
            .header("Accept", "application/json")
    }

    private suspend fun get(url: String, p: ServerProfile?): String =
        execute(jfHeaders(p, Request.Builder().url(url)).get().build())

    private suspend fun post(url: String, body: String, p: ServerProfile?): String =
        execute(jfHeaders(p, Request.Builder().url(url)).post(body.toRequestBody(json)).build())

    // ---- Jellyfin ----

    /** Address check before sign-in: "Testing connection..." in the add flow (4.6). */
    suspend fun jellyfinProbe(address: String): JellyfinRules.ServerInfo {
        val base = JellyfinRules.normaliseServerUrl(address)
        if (base.isBlank()) throw NetworkError("Enter the server address")
        val body = try {
            get("$base/System/Info/Public", null)
        } catch (e: HttpStatus) {
            throw NetworkError(JellyfinRules.errorFor(e.code))
        }
        return JellyfinRules.parsePublicInfo(body) ?: throw NetworkError("That address is not a Jellyfin server")
    }

    /** Signs in with username and password; returns the saved profile. */
    suspend fun jellyfinSignIn(address: String, username: String, password: String, trustAll: Boolean = false): ServerProfile {
        val base = JellyfinRules.normaliseServerUrl(address)
        NetHttp.trustHost(JellyfinRules.hostOf(base), trustAll)
        val info = jellyfinProbe(base)
        val body = try {
            post("$base/Users/AuthenticateByName", JellyfinRules.authBody(username.trim(), password), null)
        } catch (e: HttpStatus) {
            throw NetworkError(JellyfinRules.errorFor(e.code))
        }
        val auth = JellyfinRules.parseAuth(body) ?: throw NetworkError("Sign-in failed: unexpected server reply")
        return storeJellyfin(base, info, auth, trustAll)
    }

    private suspend fun storeJellyfin(
        base: String,
        info: JellyfinRules.ServerInfo,
        auth: JellyfinRules.AuthResult,
        trustAll: Boolean
    ): ServerProfile {
        val existing = profiles.first().firstOrNull {
            it.type == ServerType.JELLYFIN && it.baseUrl == base && it.userId == auth.userId
        }
        val profile = ServerProfile(
            id = existing?.id ?: UUID.randomUUID().toString(),
            type = ServerType.JELLYFIN,
            name = "${info.name} · ${auth.userName}",
            baseUrl = base,
            username = auth.userName,
            userId = auth.userId,
            addedAtSec = System.currentTimeMillis() / 1000,
            trustAllCerts = trustAll
        )
        secrets.put(SecretStore.tokenKey(profile.id), auth.token)
        saveProfile(profile)
        registerCredentials(profile)
        return profile
    }

    /** Quick Connect, step 1: returns the code to approve on another device. */
    suspend fun jellyfinQuickConnectStart(address: String): Pair<String, JellyfinRules.QuickConnect> {
        val base = JellyfinRules.normaliseServerUrl(address)
        jellyfinProbe(base)
        val body = try {
            post("$base/QuickConnect/Initiate", "", null)
        } catch (e: HttpStatus) {
            throw NetworkError(if (e.code == 401 || e.code == 403) "Quick Connect is turned off on this server" else JellyfinRules.errorFor(e.code))
        }
        val qc = JellyfinRules.parseQuickConnect(body) ?: throw NetworkError("Quick Connect is not available")
        return base to qc
    }

    /** Quick Connect, step 2: poll until approved, then exchange the secret for a token. */
    suspend fun jellyfinQuickConnectPoll(base: String, secret: String): ServerProfile? {
        val state = JellyfinRules.parseQuickConnect(get("$base/QuickConnect/Connect?Secret=$secret", null))
        if (state?.authenticated != true) return null
        val info = jellyfinProbe(base)
        val body = post("$base/Users/AuthenticateWithQuickConnect", JellyfinRules.quickConnectAuthBody(secret), null)
        val auth = JellyfinRules.parseAuth(body) ?: throw NetworkError("Quick Connect failed")
        return storeJellyfin(base, info, auth, false)
    }

    data class JellyfinHome(
        val resume: List<RemoteEntry>,
        val nextUp: List<RemoteEntry>,
        val libraries: List<RemoteEntry>,
        val latest: List<RemoteEntry>
    )

    private suspend fun jfItems(p: ServerProfile, url: String): List<RemoteEntry> {
        val token = secrets.get(SecretStore.tokenKey(p.id)) ?: throw NetworkError("Signed out. Sign in to this server again")
        val body = try {
            get(url, p)
        } catch (e: HttpStatus) {
            throw NetworkError(if (e.code == 401) "Your sign-in expired. Remove and add the server again" else JellyfinRules.errorFor(e.code))
        }
        return JellyfinRules.parseItems(body).map { JellyfinRules.toRemoteEntry(it, p.baseUrl, p.id, token) }
    }

    suspend fun jellyfinHome(p: ServerProfile): JellyfinHome {
        ensureCredentials()
        val libraries = jfItems(p, JellyfinRules.viewsUrl(p.baseUrl, p.userId))
        val resume = runCatching { jfItems(p, JellyfinRules.resumeUrl(p.baseUrl, p.userId)) }.getOrDefault(emptyList())
        val nextUp = runCatching { jfItems(p, JellyfinRules.nextUpUrl(p.baseUrl, p.userId)) }.getOrDefault(emptyList())
        val latest = runCatching { jfItems(p, JellyfinRules.latestUrl(p.baseUrl, p.userId)) }.getOrDefault(emptyList())
        return JellyfinHome(resume, nextUp, libraries, latest)
    }

    suspend fun jellyfinBrowse(p: ServerProfile, parentId: String): List<RemoteEntry> =
        jfItems(p, JellyfinRules.itemsUrl(p.baseUrl, p.userId, parentId))

    suspend fun jellyfinSearch(p: ServerProfile, query: String): List<RemoteEntry> =
        if (query.isBlank()) emptyList() else jfItems(p, JellyfinRules.searchUrl(p.baseUrl, p.userId, query.trim()))

    /** What the player needs for a jellyfin:// key. */
    data class JellyfinPlayback(
        val streamUrl: String,
        val transcodeUrl: String,
        val itemId: String,
        val mediaSourceId: String?,
        val playSessionId: String,
        val subtitleUrls: List<Pair<String, String>>,
        val title: String,
        val resumeMs: Long,
        val profile: ServerProfile
    )

    suspend fun jellyfinResolve(key: String): JellyfinPlayback {
        ensureCredentials()
        val (serverId, itemId) = JellyfinRules.parseItemKey(key) ?: throw NetworkError("Unknown Jellyfin item")
        val p = profile(serverId) ?: throw NetworkError("This Jellyfin server was removed")
        val token = secrets.get(SecretStore.tokenKey(p.id)) ?: throw NetworkError("Signed out of ${p.name}")
        val item = try {
            JellyfinRules.parseItemJson(get(JellyfinRules.itemUrl(p.baseUrl, p.userId, itemId), p))
        } catch (e: HttpStatus) {
            throw NetworkError(JellyfinRules.errorFor(e.code))
        } ?: throw NetworkError("Item not found on the server")
        val session = UUID.randomUUID().toString().replace("-", "")
        val subs = item.mediaSourceId?.let { ms ->
            item.externalSubtitles.map { (index, label) ->
                label.ifBlank { "Subtitle $index" } to JellyfinRules.subtitleUrl(p.baseUrl, item.id, ms, index, token)
            }
        } ?: emptyList()
        return JellyfinPlayback(
            streamUrl = JellyfinRules.directStreamUrl(p.baseUrl, item.id, token, item.mediaSourceId),
            transcodeUrl = JellyfinRules.transcodeUrl(p.baseUrl, item.id, token, session, item.mediaSourceId),
            itemId = item.id,
            mediaSourceId = item.mediaSourceId,
            playSessionId = session,
            subtitleUrls = subs,
            title = item.displayTitle,
            resumeMs = item.positionMs,
            profile = p
        )
    }

    enum class Report(val path: String) { START("/Sessions/Playing"), PROGRESS("/Sessions/Playing/Progress"), STOP("/Sessions/Playing/Stopped") }

    /** Playback reporting so resume and watched state sync with other Jellyfin apps (19.2). */
    suspend fun jellyfinReport(playback: JellyfinPlayback, kind: Report, positionMs: Long, paused: Boolean) {
        runCatching {
            post(
                playback.profile.baseUrl + kind.path,
                JellyfinRules.playbackReportBody(playback.itemId, positionMs, paused, playback.playSessionId, playback.mediaSourceId),
                playback.profile
            )
        }
    }

    /** UDP broadcast discovery on port 7359 (19.2), ~2 s budget. */
    suspend fun discoverJellyfin(timeoutMs: Int = 2_000): List<JellyfinRules.Discovered> = withContext(Dispatchers.IO) {
        val found = linkedMapOf<String, JellyfinRules.Discovered>()
        runCatching {
            DatagramSocket().use { socket ->
                socket.broadcast = true
                socket.soTimeout = 400
                val message = JellyfinRules.DISCOVERY_MESSAGE.toByteArray()
                socket.send(DatagramPacket(message, message.size, InetAddress.getByName("255.255.255.255"), JellyfinRules.DISCOVERY_PORT))
                val deadline = System.currentTimeMillis() + timeoutMs
                val buffer = ByteArray(4096)
                while (System.currentTimeMillis() < deadline) {
                    val packet = DatagramPacket(buffer, buffer.size)
                    try {
                        socket.receive(packet)
                    } catch (e: SocketTimeoutException) {
                        continue
                    }
                    JellyfinRules.parseDiscovery(String(packet.data, 0, packet.length))?.let { found[it.address] = it }
                }
            }
        }
        found.values.toList()
    }

    // ---- WebDAV / Nextcloud ----

    /**
     * Tests the share and saves it (spec 4.6 "Add SMB flow" shape:
     * address, credentials, test, save). Returns the saved profile.
     */
    suspend fun webDavConnect(
        type: ServerType,
        address: String,
        username: String,
        password: String,
        name: String,
        trustAll: Boolean
    ): ServerProfile {
        val root = if (type == ServerType.NEXTCLOUD) {
            if (username.isBlank()) throw NetworkError("Enter your Nextcloud user name")
            WebDavRules.nextcloudDavRoot(address, username.trim())
        } else {
            WebDavRules.webDavRoot(address)
        }
        val validation = StreamRules.validate(root)
        if (validation is StreamRules.Validation.Invalid) throw NetworkError(validation.reason)
        NetHttp.trustHost(JellyfinRules.hostOf(root), trustAll)
        val auth = if (username.isNotBlank()) Base64Lite.basicAuth(username.trim(), password) else null
        // Probe before saving so a typo never lands in the saved list
        propfind(root, auth)
        val profile = ServerProfile(
            id = UUID.randomUUID().toString(),
            type = type,
            name = name.trim().ifBlank { "${type.label} · ${JellyfinRules.hostOf(root)}" },
            baseUrl = root,
            username = username.trim(),
            addedAtSec = System.currentTimeMillis() / 1000,
            trustAllCerts = trustAll
        )
        if (username.isNotBlank()) secrets.put(SecretStore.passwordKey(profile.id), password)
        saveProfile(profile)
        registerCredentials(profile)
        return profile
    }

    private suspend fun propfind(url: String, auth: String?): List<WebDavRules.DavItem> {
        val builder = Request.Builder().url(url)
            .method("PROPFIND", WebDavRules.PROPFIND_BODY.toRequestBody("application/xml; charset=utf-8".toMediaType()))
            .header("Depth", "1")
        if (auth != null) builder.header("Authorization", auth)
        val body = try {
            execute(builder.build())
        } catch (e: HttpStatus) {
            throw NetworkError(WebDavRules.errorFor(e.code))
        }
        return try {
            withContext(Dispatchers.Default) { WebDavRules.parseMultistatus(body, url) }
        } catch (e: Exception) {
            throw NetworkError("This address answered, but not as a WebDAV folder")
        }
    }

    /** Lists a folder; returns the entries to show and the raw folder (for sidecars). */
    suspend fun webDavList(p: ServerProfile, folderUrl: String, showAll: Boolean): List<RemoteEntry> {
        ensureCredentials()
        val items = propfind(folderUrl, null)
        return WebDavRules.visible(items, showAll).map { WebDavRules.toRemoteEntry(it, items) }
    }

    /** Downloads a subtitle or small text file with the right credentials. */
    suspend fun download(url: String, maxBytes: Int = 4 * 1024 * 1024): ByteArray = withContext(Dispatchers.IO) {
        ensureCredentials()
        val request = Request.Builder().url(url).get().build()
        try {
            NetHttp.client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw NetworkError("Download failed (${response.code})")
                val body = response.body ?: throw NetworkError("Empty download")
                val bytes = body.byteStream().readNBytesCompat(maxBytes)
                bytes
            }
        } catch (e: NetworkError) {
            throw e
        } catch (e: IOException) {
            throw NetworkError("Download failed: ${e.message}")
        }
    }

    // ---- SMB / FTP / SFTP / NFS (spec 19.3) ----

    /**
     * Validates the address, connects, lists the root and saves the share
     * (spec 4.6 "Add SMB flow": host, credentials, test, then browse).
     */
    suspend fun shareConnect(
        type: ServerType,
        address: String,
        username: String,
        password: String,
        name: String
    ): ServerProfile = withContext(Dispatchers.IO) {
        val (base, host) = when (type) {
            ServerType.SMB -> {
                val p = SmbAddress.parse(address) ?: throw NetworkError("Enter the server as \\\\nas\\Share, nas/Share or an IP address")
                SmbAddress.format(p) to p.host
            }
            ServerType.FTP, ServerType.SFTP -> {
                val p = FtpAddress.parse(address, defaultSftp = type == ServerType.SFTP)
                    ?: throw NetworkError("Enter the server as host, host:port or ${if (type == ServerType.SFTP) "sftp" else "ftp"}://host/folder")
                val fixed = p.copy(sftp = type == ServerType.SFTP)
                FtpAddress.format(fixed) to p.host
            }
            ServerType.NFS -> {
                val p = NfsAddress.parse(address) ?: throw NetworkError("Enter the export as nas:/volume1/video")
                NfsAddress.format(p) to p.host
            }
            else -> throw NetworkError("${type.label} is not a file share")
        }
        val profile = ServerProfile(
            id = UUID.randomUUID().toString(),
            type = type,
            name = name.trim().ifBlank { "${type.label.substringBefore(' ')} · $host" },
            baseUrl = base,
            username = username.trim(),
            addedAtSec = System.currentTimeMillis() / 1000
        )
        val login = ShareLogin(profile, password.takeIf { it.isNotEmpty() })
        // Test before saving so a typo never lands in the saved list
        val fs = try {
            RemoteFsPool.create(login)
        } catch (e: NetworkError) {
            throw e
        }
        try {
            fs.list("/")
        } catch (e: Exception) {
            runCatching { fs.close() }
            throw NetworkError(RemoteFsPool.friendly(e, type))
        }
        runCatching { fs.close() }
        if (password.isNotEmpty()) secrets.put(SecretStore.passwordKey(profile.id), password)
        saveProfile(profile)
        profile
    }

    /** Profile + password for the data source (runs on Media3's loader thread). */
    fun shareLoginBlocking(profileId: String): ShareLogin = kotlinx.coroutines.runBlocking {
        val p = profile(profileId) ?: throw NetworkError("This server was removed")
        ShareLogin(p, secrets.get(SecretStore.passwordKey(p.id)))
    }

    suspend fun shareList(p: ServerProfile, path: String, showAll: Boolean): List<RemoteEntry> = withContext(Dispatchers.IO) {
        val login = ShareLogin(p, secrets.get(SecretStore.passwordKey(p.id)))
        val scheme = RemoteUris.schemeFor(p.type) ?: throw NetworkError("Not a file share")
        val files = RemoteFsPool.withRetry(login) { it.list(path) }
        RemoteFileRules.visible(files, showAll).map { f ->
            RemoteFileRules.toEntry(f, files) { RemoteUris.build(scheme, p.id, it) }
        }
    }

    /** Reads a small share file (a subtitle) fully. */
    suspend fun readShare(uri: String, maxBytes: Int = 4 * 1024 * 1024): ByteArray = withContext(Dispatchers.IO) {
        val parsed = RemoteUris.parse(uri) ?: throw NetworkError("Bad location")
        val login = shareLoginBlocking(parsed.profileId)
        RemoteFsPool.withRetry(login) { fs ->
            fs.open(parsed.path, 0).use { it.readNBytesCompat(maxBytes) }
        }
    }

    // ---- DLNA / UPnP (spec 19.4) ----

    data class DlnaFound(val location: String, val name: String)

    /** SSDP search for media servers (~3 s). Needs a multicast lock on Wi-Fi. */
    suspend fun discoverDlna(timeoutMs: Int = 3_000): List<DlnaFound> = withContext(Dispatchers.IO) {
        val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? android.net.wifi.WifiManager
        val lock = runCatching { wifi?.createMulticastLock("chaosplay-ssdp")?.apply { setReferenceCounted(false); acquire() } }.getOrNull()
        val locations = linkedSetOf<String>()
        try {
            DatagramSocket().use { socket ->
                socket.soTimeout = 400
                val msg = DlnaRules.searchRequest().toByteArray()
                val group = InetAddress.getByName(DlnaRules.SSDP_ADDRESS)
                repeat(2) { socket.send(DatagramPacket(msg, msg.size, group, DlnaRules.SSDP_PORT)) }
                val deadline = System.currentTimeMillis() + timeoutMs
                val buf = ByteArray(4096)
                while (System.currentTimeMillis() < deadline) {
                    val packet = DatagramPacket(buf, buf.size)
                    try {
                        socket.receive(packet)
                    } catch (e: SocketTimeoutException) {
                        continue
                    }
                    DlnaRules.parseSsdp(String(packet.data, 0, packet.length))?.let { locations += it.location }
                }
            }
        } catch (_: Exception) {
        } finally {
            runCatching { lock?.release() }
        }
        locations.mapNotNull { loc ->
            runCatching { dlnaDevice(loc) }.getOrNull()?.let { DlnaFound(loc, it.name) }
        }
    }

    private suspend fun dlnaDevice(location: String): DlnaRules.Device {
        val xml = try {
            execute(Request.Builder().url(location).get().build())
        } catch (e: HttpStatus) {
            throw NetworkError("The device answered ${e.code}")
        }
        return try {
            DlnaRules.parseDescription(xml, location)
        } catch (e: Exception) {
            null
        } ?: throw NetworkError("That device is not a DLNA media server")
    }

    /** Saves a media server from its description URL (or "host:port" of a common server). */
    suspend fun dlnaConnect(input: String, name: String = ""): ServerProfile {
        val raw = input.trim()
        if (raw.isEmpty()) throw NetworkError("Enter the server's description URL")
        val location = if (raw.startsWith("http")) raw else "http://$raw"
        val device = try {
            dlnaDevice(location)
        } catch (e: NetworkError) {
            // "nas:8200" without a path: try MiniDLNA's and common defaults
            if (Regex("^https?://[^/]+/?$").matches(location)) {
                listOf("rootDesc.xml", "description.xml", "dmr/description.xml").firstNotNullOfOrNull { path ->
                    runCatching { dlnaDevice(location.trimEnd('/') + "/" + path) }.getOrNull()
                } ?: throw e
            } else throw e
        }
        val profile = ServerProfile(
            id = UUID.randomUUID().toString(),
            type = ServerType.DLNA,
            name = name.trim().ifBlank { device.name },
            baseUrl = location,
            userId = device.controlUrl,
            addedAtSec = System.currentTimeMillis() / 1000
        )
        saveProfile(profile)
        return profile
    }

    /** Lists a container, following paging until everything is in (capped). */
    suspend fun dlnaBrowse(p: ServerProfile, objectId: String, max: Int = 2_000): List<RemoteEntry> {
        val control = p.userId.ifBlank { dlnaDevice(p.baseUrl).controlUrl }
        val all = mutableListOf<DlnaRules.DlnaObject>()
        var start = 0
        while (all.size < max) {
            val body = DlnaRules.browseBody(objectId, start, DlnaRules.PAGE)
            val request = Request.Builder().url(control)
                .post(body.toRequestBody("text/xml; charset=\"utf-8\"".toMediaType()))
                .header("SOAPAction", DlnaRules.SOAP_ACTION)
                .build()
            val xml = try {
                execute(request)
            } catch (e: HttpStatus) {
                // UPnP faults come back as HTTP 500 with a SOAP body
                if (e.body.contains("Fault")) e.body else throw NetworkError("The server answered ${e.code}")
            }
            val page = try {
                withContext(Dispatchers.Default) { DlnaRules.parseBrowseResponse(xml) }
            } catch (e: IllegalStateException) {
                throw NetworkError(e.message ?: "Could not list this folder")
            } catch (e: Exception) {
                throw NetworkError("The server's answer could not be read")
            }
            all += page.items
            start += page.returned
            if (page.returned <= 0 || start >= page.total) break
        }
        return DlnaRules.toEntries(all, p.id)
    }

    companion object {
        @Volatile private var instance: NetworkRepository? = null

        fun get(context: Context): NetworkRepository =
            instance ?: synchronized(this) {
                instance ?: NetworkRepository(context.applicationContext).also { instance = it }
            }
    }
}

private fun java.io.InputStream.readNBytesCompat(max: Int): ByteArray {
    val out = java.io.ByteArrayOutputStream()
    val buffer = ByteArray(16 * 1024)
    var total = 0
    while (true) {
        val n = read(buffer)
        if (n < 0) break
        total += n
        if (total > max) throw NetworkError("File is too large for a subtitle")
        out.write(buffer, 0, n)
    }
    return out.toByteArray()
}
