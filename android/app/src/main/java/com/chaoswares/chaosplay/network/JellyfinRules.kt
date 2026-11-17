package com.chaoswares.chaosplay.network

import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.net.URLEncoder

/**
 * Jellyfin REST API rules (spec 19.2), written against the documented
 * endpoints rather than the SDK so the app stays small:
 *
 *  - POST /Users/AuthenticateByName            sign in -> AccessToken, User.Id
 *  - GET  /Users/{u}/Views                     libraries
 *  - GET  /Users/{u}/Items/Resume              continue watching
 *  - GET  /Shows/NextUp                        next up
 *  - GET  /Users/{u}/Items/Latest              recently added
 *  - GET  /Users/{u}/Items?ParentId=..         browse a library / season
 *  - GET  /Videos/{id}/stream?static=true      direct play
 *  - GET  /Videos/{id}/master.m3u8             server transcode over HLS
 *  - POST /Sessions/Playing[/Progress|/Stopped] playback reporting (resume sync)
 *  - UDP 7359 "Who is JellyfinServer?"         LAN discovery
 *
 * JSON handling is in here (org.json) so it is unit tested with real
 * server payloads.
 */
object JellyfinRules {

    const val CLIENT_NAME = "CPlay"
    const val DISCOVERY_PORT = 7359
    const val DISCOVERY_MESSAGE = "Who is JellyfinServer?"
    const val SCHEME = "jellyfin://"
    private const val TICKS_PER_MS = 10_000L

    fun ticksToMs(ticks: Long): Long = ticks / TICKS_PER_MS
    fun msToTicks(ms: Long): Long = ms * TICKS_PER_MS

    /**
     * Normalises a server address. No scheme: a bare host on Jellyfin's
     * default port 8096 is plain http (that is how the server ships),
     * anything else gets https (19.8 "Use HTTPS whenever possible").
     */
    fun normaliseServerUrl(input: String): String {
        val trimmed = input.trim().trimEnd('/')
        if (trimmed.isEmpty()) return ""
        val withScheme = when {
            Regex("^[a-zA-Z][a-zA-Z0-9+.-]*://").containsMatchIn(trimmed) -> trimmed
            Regex(":8096(/|$)").containsMatchIn(trimmed) -> "http://$trimmed"
            else -> "https://$trimmed"
        }
        // People paste the web UI address: ".../web/index.html#!/home.html"
        return withScheme.substringBefore('#').replace(Regex("(?i)/web(/.*)?$"), "").trimEnd('/')
    }

    /** The MediaBrowser authorization header value; [token] is added once signed in. */
    fun authHeader(deviceId: String, version: String, token: String? = null, device: String = "Android"): String {
        fun q(v: String) = v.replace("\"", "'")
        val parts = mutableListOf(
            "Client=\"${q(CLIENT_NAME)}\"",
            "Device=\"${q(device)}\"",
            "DeviceId=\"${q(deviceId)}\"",
            "Version=\"${q(version)}\""
        )
        if (!token.isNullOrBlank()) parts += "Token=\"${q(token)}\""
        return "MediaBrowser " + parts.joinToString(", ")
    }

    fun authBody(username: String, password: String): String =
        JSONObject().put("Username", username).put("Pw", password).toString()

    data class AuthResult(val token: String, val userId: String, val userName: String, val serverId: String)

    fun parseAuth(json: String): AuthResult? = runCatching {
        val o = JSONObject(json)
        val token = o.optString("AccessToken").takeIf { it.isNotBlank() } ?: return null
        val user = o.optJSONObject("User") ?: return null
        AuthResult(
            token = token,
            userId = user.optString("Id"),
            userName = user.optString("Name"),
            serverId = o.optString("ServerId").ifBlank { user.optString("ServerId") }
        )
    }.getOrNull()

    data class ServerInfo(val name: String, val version: String, val id: String)

    /** GET /System/Info/Public: proves the address is a Jellyfin server before sign-in. */
    fun parsePublicInfo(json: String): ServerInfo? = runCatching {
        val o = JSONObject(json)
        val id = o.optString("Id")
        if (id.isBlank() && o.optString("ProductName").isBlank()) return null
        ServerInfo(o.optString("ServerName").ifBlank { "Jellyfin" }, o.optString("Version"), id)
    }.getOrNull()

    /** Quick Connect: POST /QuickConnect/Initiate -> {Secret, Code}; poll /QuickConnect/Connect?Secret=. */
    data class QuickConnect(val secret: String, val code: String, val authenticated: Boolean)

    fun parseQuickConnect(json: String): QuickConnect? = runCatching {
        val o = JSONObject(json)
        val secret = o.optString("Secret").takeIf { it.isNotBlank() } ?: return null
        QuickConnect(secret, o.optString("Code"), o.optBoolean("Authenticated", false))
    }.getOrNull()

    fun quickConnectAuthBody(secret: String): String = JSONObject().put("Secret", secret).toString()

    // ---- URLs ----

    private fun enc(v: String) = URLEncoder.encode(v, "UTF-8")

    fun viewsUrl(base: String, userId: String) = "$base/Users/${enc(userId)}/Views"

    fun resumeUrl(base: String, userId: String, limit: Int = 12) =
        "$base/Users/${enc(userId)}/Items/Resume?Limit=$limit&MediaTypes=Video&Fields=$FIELDS&EnableImageTypes=Primary,Thumb,Backdrop"

    fun nextUpUrl(base: String, userId: String, limit: Int = 12) =
        "$base/Shows/NextUp?UserId=${enc(userId)}&Limit=$limit&Fields=$FIELDS"

    fun latestUrl(base: String, userId: String, limit: Int = 16) =
        "$base/Users/${enc(userId)}/Items/Latest?Limit=$limit&Fields=$FIELDS&IncludeItemTypes=Movie,Episode,Video"

    fun itemsUrl(base: String, userId: String, parentId: String, startIndex: Int = 0, limit: Int = 200) =
        "$base/Users/${enc(userId)}/Items?ParentId=${enc(parentId)}&SortBy=SortName&SortOrder=Ascending" +
            "&Fields=$FIELDS&StartIndex=$startIndex&Limit=$limit"

    fun searchUrl(base: String, userId: String, query: String) =
        "$base/Users/${enc(userId)}/Items?SearchTerm=${enc(query)}&Recursive=true&Limit=50" +
            "&IncludeItemTypes=Movie,Series,Episode,Video&Fields=$FIELDS"

    fun itemUrl(base: String, userId: String, itemId: String) =
        "$base/Users/${enc(userId)}/Items/${enc(itemId)}"

    private const val FIELDS = "Overview,MediaSources,ProductionYear,RunTimeTicks,UserData,ParentId"

    fun imageUrl(base: String, itemId: String, tag: String?, maxWidth: Int = 400, type: String = "Primary"): String? {
        if (tag.isNullOrBlank()) return null
        return "$base/Items/${enc(itemId)}/Images/$type?maxWidth=$maxWidth&tag=${enc(tag)}&quality=85"
    }

    /** Direct play: the original file, seekable with range requests. */
    fun directStreamUrl(base: String, itemId: String, token: String, mediaSourceId: String? = null): String {
        val ms = mediaSourceId?.takeIf { it.isNotBlank() } ?: itemId
        return "$base/Videos/${enc(itemId)}/stream?static=true&MediaSourceId=${enc(ms)}&api_key=${enc(token)}"
    }

    /** Server transcode over HLS for files the device cannot decode (19.2 "Playback decisions"). */
    fun transcodeUrl(
        base: String,
        itemId: String,
        token: String,
        playSessionId: String,
        mediaSourceId: String? = null,
        maxBitrate: Int = 8_000_000
    ): String {
        val ms = mediaSourceId?.takeIf { it.isNotBlank() } ?: itemId
        return "$base/Videos/${enc(itemId)}/master.m3u8?MediaSourceId=${enc(ms)}&api_key=${enc(token)}" +
            "&PlaySessionId=${enc(playSessionId)}&VideoCodec=h264&AudioCodec=aac,mp3" +
            "&TranscodingMaxAudioChannels=2&SegmentContainer=ts&MaxStreamingBitrate=$maxBitrate" +
            "&DeviceId=${enc(CLIENT_NAME)}"
    }

    fun subtitleUrl(base: String, itemId: String, mediaSourceId: String, streamIndex: Int, token: String, format: String = "srt") =
        "$base/Videos/${enc(itemId)}/${enc(mediaSourceId)}/Subtitles/$streamIndex/Stream.$format?api_key=${enc(token)}"

    // ---- stable keys: tokens never reach history, resume or logs ----

    fun itemKey(serverId: String, itemId: String) = "$SCHEME$serverId/$itemId"

    fun isItemKey(uri: String) = uri.startsWith(SCHEME)

    /** (serverId, itemId) from a jellyfin:// key. */
    fun parseItemKey(uri: String): Pair<String, String>? {
        if (!isItemKey(uri)) return null
        val rest = uri.removePrefix(SCHEME)
        val server = rest.substringBefore('/')
        val item = rest.substringAfter('/', "")
        if (server.isBlank() || item.isBlank()) return null
        return server to item
    }

    // ---- items ----

    data class Item(
        val id: String,
        val name: String,
        val type: String,
        val isFolder: Boolean,
        val collectionType: String,
        val runTimeMs: Long,
        val positionMs: Long,
        val played: Boolean,
        val primaryImageTag: String?,
        val seriesName: String,
        val seasonNumber: Int?,
        val episodeNumber: Int?,
        val year: Int?,
        val overview: String,
        val mediaSourceId: String?,
        val container: String,
        val externalSubtitles: List<Pair<Int, String>>
    ) {
        val isPlayable: Boolean get() = !isFolder && type in PLAYABLE_TYPES

        /** "S01E02 · Pilot" for episodes, "Title (2019)" for movies. */
        val displayTitle: String
            get() = when {
                type == "Episode" && episodeNumber != null ->
                    "S%02dE%02d · %s".format(seasonNumber ?: 0, episodeNumber, name)
                year != null && type == "Movie" -> "$name ($year)"
                else -> name
            }
    }

    val PLAYABLE_TYPES = setOf("Movie", "Episode", "Video", "MusicVideo", "Trailer", "Audio", "TvChannel")

    fun parseItems(json: String): List<Item> = runCatching {
        val trimmed = json.trim()
        // /Items/Latest returns a bare array; everything else wraps it in {"Items": [...]}
        val array = if (trimmed.startsWith("[")) JSONArray(trimmed) else JSONObject(trimmed).optJSONArray("Items")
            ?: return emptyList()
        (0 until array.length()).mapNotNull { i -> array.optJSONObject(i)?.let { parseItem(it) } }
    }.getOrDefault(emptyList())

    fun parseItemJson(json: String): Item? = runCatching { parseItem(JSONObject(json)) }.getOrNull()

    fun parseItem(o: JSONObject): Item? {
        val id = o.optString("Id").takeIf { it.isNotBlank() } ?: return null
        val userData = o.optJSONObject("UserData")
        val sources = o.optJSONArray("MediaSources")
        val source = sources?.optJSONObject(0)
        val externalSubs = mutableListOf<Pair<Int, String>>()
        source?.optJSONArray("MediaStreams")?.let { streams ->
            for (s in 0 until streams.length()) {
                val st = streams.optJSONObject(s) ?: continue
                if (st.optString("Type") == "Subtitle" && st.optBoolean("IsExternal", false) &&
                    st.optBoolean("IsTextSubtitleStream", true)
                ) {
                    val label = st.optString("DisplayTitle").ifBlank { st.optString("Language") }
                    externalSubs += st.optInt("Index") to label
                }
            }
        }
        return Item(
            id = id,
            name = o.optString("Name").ifBlank { "Untitled" },
            type = o.optString("Type"),
            isFolder = o.optBoolean("IsFolder", false),
            collectionType = o.optString("CollectionType"),
            runTimeMs = ticksToMs(o.optLong("RunTimeTicks", 0L)),
            positionMs = ticksToMs(userData?.optLong("PlaybackPositionTicks", 0L) ?: 0L),
            played = userData?.optBoolean("Played", false) ?: false,
            primaryImageTag = o.optJSONObject("ImageTags")?.optString("Primary")?.takeIf { it.isNotBlank() },
            seriesName = o.optString("SeriesName"),
            seasonNumber = if (o.has("ParentIndexNumber")) o.optInt("ParentIndexNumber") else null,
            episodeNumber = if (o.has("IndexNumber")) o.optInt("IndexNumber") else null,
            year = if (o.has("ProductionYear")) o.optInt("ProductionYear") else null,
            overview = o.optString("Overview"),
            mediaSourceId = source?.optString("Id")?.takeIf { it.isNotBlank() },
            container = source?.optString("Container").orEmpty(),
            externalSubtitles = externalSubs
        )
    }

    fun toRemoteEntry(item: Item, base: String, serverId: String, token: String): RemoteEntry {
        val subs = if (item.mediaSourceId != null) {
            item.externalSubtitles.map { (index, _) -> subtitleUrl(base, item.id, item.mediaSourceId, index, token) }
        } else emptyList()
        return RemoteEntry(
            id = item.id,
            name = item.displayTitle,
            isFolder = !item.isPlayable,
            playUri = if (item.isPlayable) itemKey(serverId, item.id) else "",
            durationMs = item.runTimeMs,
            imageUrl = imageUrl(base, item.id, item.primaryImageTag),
            resumeMs = item.positionMs,
            played = item.played,
            subtitle = listOfNotNull(
                item.seriesName.takeIf { it.isNotBlank() && item.type == "Episode" },
                item.year?.toString()?.takeIf { item.type != "Episode" },
                item.type.takeIf { it.isNotBlank() && item.isFolder && item.collectionType.isBlank() }
            ).joinToString(" · "),
            overview = item.overview,
            subtitleUris = subs,
            kind = item.collectionType.ifBlank { item.type }
        )
    }

    // ---- playback reporting (19.2 "report start, progress and stop") ----

    fun playbackReportBody(
        itemId: String,
        positionMs: Long,
        isPaused: Boolean,
        playSessionId: String,
        mediaSourceId: String?
    ): String = JSONObject()
        .put("ItemId", itemId)
        .put("MediaSourceId", mediaSourceId ?: itemId)
        .put("PositionTicks", msToTicks(positionMs.coerceAtLeast(0L)))
        .put("IsPaused", isPaused)
        .put("CanSeek", true)
        .put("PlaySessionId", playSessionId)
        .put("PlayMethod", "DirectPlay")
        .toString()

    /** Reports are throttled: Jellyfin needs one every ~10 s, not every frame. */
    const val PROGRESS_INTERVAL_MS = 10_000L

    fun shouldReportProgress(lastReportAtMs: Long, nowMs: Long, stateChanged: Boolean): Boolean =
        stateChanged || nowMs - lastReportAtMs >= PROGRESS_INTERVAL_MS

    // ---- discovery ----

    data class Discovered(val address: String, val id: String, val name: String)

    fun parseDiscovery(json: String): Discovered? = runCatching {
        val o = JSONObject(json)
        val address = o.optString("Address").takeIf { it.isNotBlank() } ?: return null
        Discovered(address.trimEnd('/'), o.optString("Id"), o.optString("Name").ifBlank { "Jellyfin" })
    }.getOrNull()

    /** Friendly message for an HTTP status during sign-in or browsing. */
    fun errorFor(status: Int): String = when (status) {
        400 -> "The server rejected the request"
        401 -> "Wrong user name or password"
        403 -> "This user is not allowed to use this server"
        404 -> "Not a Jellyfin server at this address"
        in 500..599 -> "The server had a problem ($status). Try again later"
        else -> "Server answered $status"
    }

    fun hostOf(url: String): String = runCatching { URI(url).host.orEmpty() }.getOrDefault("")
}
