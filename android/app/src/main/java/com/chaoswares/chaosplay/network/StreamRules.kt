package com.chaoswares.chaosplay.network

import com.chaoswares.chaosplay.util.FieldCodec
import java.net.URI
import java.net.URLDecoder

/** Stream container kinds Media3 is told about explicitly. */
enum class StreamKind(val label: String, val mimeType: String?) {
    HLS("HLS", "application/x-mpegURL"),
    DASH("DASH", "application/dash+xml"),
    RTSP("RTSP", "application/x-rtsp"),
    PROGRESSIVE("File", null)
}

/** A saved network stream (spec 4.6 "Saved servers", "other URL"). */
data class SavedStream(
    val id: String,
    val name: String,
    val url: String,
    val addedAtSec: Long = 0L
)

/**
 * URL playback rules, spec section 3 "Network streams: HTTP/HTTPS, HLS,
 * DASH, RTSP, URL playback" and 19.8 "warn on plain HTTP over the
 * internet". Pure Kotlin (java.net only) so every rule is unit tested.
 */
object StreamRules {

    val SUPPORTED_SCHEMES = setOf("http", "https", "rtsp")

    sealed class Validation {
        data class Valid(val url: String) : Validation()
        data class Invalid(val reason: String) : Validation()
    }

    /**
     * Normalises user input. A bare host ("nas.local:8096/video.mp4") is
     * given https:// since "Use HTTPS whenever possible" (19.8).
     */
    fun validate(input: String): Validation {
        val trimmed = input.trim()
        if (trimmed.isEmpty()) return Validation.Invalid("Enter a link to a video or stream")
        if (trimmed.any { it.isWhitespace() }) return Validation.Invalid("Links cannot contain spaces")
        val withScheme = if (Regex("^[a-zA-Z][a-zA-Z0-9+.-]*://").containsMatchIn(trimmed)) {
            trimmed
        } else {
            "https://$trimmed"
        }
        val uri = runCatching { URI(withScheme) }.getOrNull()
            ?: return Validation.Invalid("That does not look like a link")
        val scheme = uri.scheme?.lowercase()
            ?: return Validation.Invalid("That does not look like a link")
        if (scheme !in SUPPORTED_SCHEMES) {
            return Validation.Invalid("${scheme.uppercase()} links are not supported. Use http, https or rtsp")
        }
        if (uri.host.isNullOrBlank()) return Validation.Invalid("The link has no server name")
        return Validation.Valid(withScheme)
    }

    /** Container kind from the URL; HLS/DASH need the right Media3 source. */
    fun kindOf(url: String): StreamKind {
        val lower = url.lowercase()
        if (lower.startsWith("rtsp://")) return StreamKind.RTSP
        val path = runCatching { URI(url).path.orEmpty().lowercase() }.getOrDefault(lower)
        return when {
            path.endsWith(".m3u8") || path.endsWith(".m3u") || lower.contains("format=m3u8") -> StreamKind.HLS
            path.endsWith(".mpd") || lower.contains("format=mpd") -> StreamKind.DASH
            else -> StreamKind.PROGRESSIVE
        }
    }

    /** True for http(s)/rtsp locations; false for content:// and file paths. */
    fun isNetworkUrl(location: String): Boolean {
        val scheme = location.substringBefore("://", "").lowercase()
        return scheme in SUPPORTED_SCHEMES
    }

    /**
     * Plain HTTP to a public host leaks what you watch to anyone on the
     * path (19.8). LAN addresses are fine, so they do not warn.
     */
    fun isInsecurePublic(url: String): Boolean {
        val uri = runCatching { URI(url) }.getOrNull() ?: return false
        if (uri.scheme?.lowercase() != "http") return false
        val host = uri.host ?: return false
        return !isPrivateHost(host)
    }

    fun isPrivateHost(host: String): Boolean {
        val h = host.lowercase().trim('[', ']')
        if (h == "localhost" || h.endsWith(".local") || h.endsWith(".lan") || h.endsWith(".home") ||
            h.endsWith(".internal") || !h.contains('.') && !h.contains(':')
        ) return true
        if (h == "::1" || h.startsWith("fe80:") || h.startsWith("fc") || h.startsWith("fd")) {
            return h.contains(':')
        }
        val parts = h.split('.').mapNotNull { it.toIntOrNull() }
        if (parts.size != 4 || parts.any { it !in 0..255 }) return false
        val (a, b) = parts
        return a == 10 ||
            a == 127 ||
            (a == 172 && b in 16..31) ||
            (a == 192 && b == 168) ||
            (a == 169 && b == 254) ||
            (a == 100 && b in 64..127) // CGNAT, used by Tailscale
    }

    /** Readable name: last path segment, URL-decoded, else the host. */
    fun displayName(url: String): String {
        val uri = runCatching { URI(url) }.getOrNull() ?: return url
        val segment = uri.rawPath.orEmpty().trimEnd('/').substringAfterLast('/')
        val decoded = runCatching { URLDecoder.decode(segment, "UTF-8") }.getOrDefault(segment)
        return decoded.ifBlank { uri.host ?: url }
    }

    /** Removes user:password from a URL before it is shown or logged (19.8). */
    fun redact(url: String): String {
        val uri = runCatching { URI(url) }.getOrNull() ?: return url
        if (uri.rawUserInfo == null) return url
        return url.replaceFirst(uri.rawUserInfo + "@", "")
    }

    // ---- saved list ----

    fun add(list: List<SavedStream>, stream: SavedStream): List<SavedStream> =
        list.filterNot { it.url == stream.url } + stream

    fun remove(list: List<SavedStream>, id: String): List<SavedStream> = list.filterNot { it.id == id }

    fun encode(list: List<SavedStream>): String = FieldCodec.encode(
        list.map { listOf(it.id, it.name, it.url, it.addedAtSec.toString()) }
    )

    fun decode(raw: String): List<SavedStream> = FieldCodec.decode(raw).mapNotNull { r ->
        val id = r.getOrNull(0)?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
        val url = r.getOrNull(2)?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
        SavedStream(id, r.getOrNull(1).orEmpty().ifBlank { displayName(url) }, url, r.getOrNull(3)?.toLongOrNull() ?: 0L)
    }
}
