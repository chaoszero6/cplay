package com.chaoswares.chaosplay.network

import com.chaoswares.chaosplay.subtitles.SubtitleFormat
import com.chaoswares.chaosplay.subtitles.SubtitleSelector
import org.w3c.dom.Element
import org.w3c.dom.Node
import java.io.ByteArrayInputStream
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import javax.xml.parsers.DocumentBuilderFactory

/**
 * WebDAV and Nextcloud browsing rules (spec 19.1 priority 4: "WebDAV
 * (Nextcloud, ownCloud, many NAS)"). Uses the platform DOM parser, which
 * exists on Android and on the JVM, so the multistatus parsing is tested
 * against real Nextcloud responses without a device.
 */
object WebDavRules {

    /** Depth-1 PROPFIND asking only for what the browser shows. */
    const val PROPFIND_BODY = """<?xml version="1.0" encoding="UTF-8"?>
<d:propfind xmlns:d="DAV:" xmlns:oc="http://owncloud.org/ns" xmlns:nc="http://nextcloud.org/ns">
  <d:prop>
    <d:displayname/>
    <d:resourcetype/>
    <d:getcontentlength/>
    <d:getcontenttype/>
    <d:getlastmodified/>
    <oc:size/>
  </d:prop>
</d:propfind>"""

    val VIDEO_EXTENSIONS = setOf(
        "mp4", "m4v", "mkv", "webm", "mov", "avi", "ts", "m2ts", "mts", "3gp", "flv", "wmv",
        "mpg", "mpeg", "vob", "f4v", "divx", "ogv"
    )
    val AUDIO_EXTENSIONS = setOf("mp3", "m4a", "aac", "flac", "ogg", "opus", "wav", "wma", "mka")

    data class DavItem(
        val href: String,
        val url: String,
        val name: String,
        val isFolder: Boolean,
        val sizeBytes: Long,
        val contentType: String,
        val lastModified: String
    )

    /**
     * Builds the DAV root for a Nextcloud server. Accepts what people
     * paste: "cloud.example.com", "https://cloud.example.com/index.php",
     * "https://host/nextcloud/" or a full ".../remote.php/dav/files/user/".
     */
    fun nextcloudDavRoot(serverInput: String, username: String): String {
        val withScheme = ensureScheme(serverInput.trim())
        val existing = Regex("(?i)/remote\\.php/(dav/files/[^/]+|webdav)/?").find(withScheme)
        if (existing != null) {
            val end = existing.range.last + 1
            return withScheme.substring(0, end).trimEnd('/') + "/"
        }
        val base = withScheme
            .substringBefore("?")
            .replace(Regex("(?i)/index\\.php.*$"), "")
            .replace(Regex("(?i)/(apps|login|s)/.*$"), "")
            .trimEnd('/')
        return "$base/remote.php/dav/files/${encodeSegment(username)}/"
    }

    /** A generic WebDAV root: just make sure it is a folder URL. */
    fun webDavRoot(input: String): String = ensureScheme(input.trim()).substringBefore('#').trimEnd('/') + "/"

    fun ensureScheme(input: String): String =
        if (Regex("^[a-zA-Z][a-zA-Z0-9+.-]*://").containsMatchIn(input)) input else "https://$input"

    /** Percent-encodes one path segment (spaces as %20, not '+'). */
    fun encodeSegment(segment: String): String =
        URLEncoder.encode(segment, "UTF-8").replace("+", "%20")

    /** URL of a child inside a folder URL. */
    fun childUrl(folderUrl: String, name: String, isFolder: Boolean): String {
        val base = if (folderUrl.endsWith("/")) folderUrl else "$folderUrl/"
        return base + encodeSegment(name) + if (isFolder) "/" else ""
    }

    /** The parent folder URL, never climbing above [rootUrl]. */
    fun parentUrl(currentUrl: String, rootUrl: String): String? {
        val current = currentUrl.trimEnd('/')
        val root = rootUrl.trimEnd('/')
        if (current.length <= root.length) return null
        return current.substringBeforeLast('/') + "/"
    }

    /** Breadcrumb names from the root to [currentUrl], e.g. ["Home", "Movies", "2024"]. */
    fun breadcrumbs(currentUrl: String, rootUrl: String, rootLabel: String = "Home"): List<Pair<String, String>> {
        val crumbs = mutableListOf(rootLabel to rootUrl)
        val rel = currentUrl.removePrefix(rootUrl).trim('/')
        if (rel.isEmpty()) return crumbs
        var url = rootUrl
        for (segment in rel.split('/')) {
            url = url.trimEnd('/') + "/" + segment + "/"
            crumbs += decode(segment) to url
        }
        return crumbs
    }

    fun decode(segment: String): String =
        runCatching { URLDecoder.decode(segment.replace("+", "%2B"), "UTF-8") }.getOrDefault(segment)

    /**
     * Parses a 207 Multi-Status body. The entry for the folder itself is
     * dropped; hidden files (".foo") are skipped; folders sort before files,
     * each by name.
     */
    fun parseMultistatus(xml: String, requestUrl: String): List<DavItem> {
        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
            // No external entities: a hostile server must not read local files (XXE)
            runCatching { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
            runCatching { setFeature("http://xml.org/sax/features/external-general-entities", false) }
            isExpandEntityReferences = false
        }
        val doc = factory.newDocumentBuilder().parse(ByteArrayInputStream(xml.toByteArray(Charsets.UTF_8)))
        val request = URI(requestUrl)
        val requestPath = normalisePath(request.rawPath ?: "/")
        val items = mutableListOf<DavItem>()
        val responses = doc.getElementsByTagNameNS("DAV:", "response")
        for (i in 0 until responses.length) {
            val response = responses.item(i) as Element
            val href = childText(response, "href") ?: continue
            val hrefUri = runCatching { request.resolve(href.trim()) }.getOrNull() ?: continue
            val path = normalisePath(hrefUri.rawPath ?: continue)
            if (path == requestPath) continue
            // Only successful propstats carry real values
            var isFolder = false
            var size = 0L
            var type = ""
            var modified = ""
            var display: String? = null
            val propstats = response.getElementsByTagNameNS("DAV:", "propstat")
            for (p in 0 until propstats.length) {
                val ps = propstats.item(p) as Element
                val status = childText(ps, "status").orEmpty()
                if (status.isNotEmpty() && !status.contains(" 200")) continue
                if (ps.getElementsByTagNameNS("DAV:", "collection").length > 0) isFolder = true
                childText(ps, "getcontentlength")?.trim()?.toLongOrNull()?.let { size = it }
                if (size == 0L) {
                    val ocSize = ps.getElementsByTagNameNS("http://owncloud.org/ns", "size")
                    if (ocSize.length > 0) ocSize.item(0).textContent.trim().toLongOrNull()?.let { size = it }
                }
                childText(ps, "getcontenttype")?.let { type = it.trim() }
                childText(ps, "getlastmodified")?.let { modified = it.trim() }
                childText(ps, "displayname")?.trim()?.takeIf { it.isNotEmpty() }?.let { display = it }
            }
            if (hrefUri.rawPath.endsWith("/")) isFolder = true
            val rawName = hrefUri.rawPath.trimEnd('/').substringAfterLast('/')
            val name = display ?: decode(rawName)
            if (name.startsWith(".")) continue
            val absolute = URI(request.scheme, null, request.host, request.port, null, null, null).toString()
                .trimEnd('/') + hrefUri.rawPath
            items += DavItem(href, absolute, name, isFolder, size, type, modified)
        }
        return items.sortedWith(compareBy<DavItem>({ !it.isFolder }, { it.name.lowercase() }))
    }

    private fun normalisePath(path: String): String = decode(path).trimEnd('/').ifEmpty { "/" }

    private fun childText(parent: Element, localName: String): String? {
        val nodes = parent.getElementsByTagNameNS("DAV:", localName)
        if (nodes.length == 0) return null
        val node: Node = nodes.item(0)
        return node.textContent
    }

    fun extensionOf(name: String): String = name.substringAfterLast('.', "").lowercase()

    fun isVideo(item: DavItem): Boolean =
        extensionOf(item.name) in VIDEO_EXTENSIONS || item.contentType.startsWith("video/")

    fun isAudio(item: DavItem): Boolean =
        extensionOf(item.name) in AUDIO_EXTENSIONS || item.contentType.startsWith("audio/")

    fun isSubtitle(item: DavItem): Boolean = SubtitleFormat.isSubtitleFile(item.name)

    /**
     * What the browser shows: folders and playable files by default, every
     * file when [showAll] (spec 19.3a "show only video, audio and subtitle
     * files by default, with a show all files toggle").
     */
    fun visible(items: List<DavItem>, showAll: Boolean): List<DavItem> =
        if (showAll) items else items.filter { it.isFolder || isVideo(it) || isAudio(it) }

    /** Subtitle files in the same folder as [video], best match first (19.3 auto-detect). */
    fun sidecarSubtitles(video: DavItem, folder: List<DavItem>): List<DavItem> {
        val subs = folder.filter { !it.isFolder && isSubtitle(it) }
        val ranked = SubtitleSelector.rank(video.name, subs.map { it.name })
        val videoBase = video.name.substringBeforeLast('.').lowercase()
        return ranked
            .filter { SubtitleSelector.scoreFor(video.name, it) > 0 || it.lowercase().startsWith(videoBase) }
            .mapNotNull { name -> subs.firstOrNull { it.name == name } }
    }

    fun toRemoteEntry(item: DavItem, folder: List<DavItem>): RemoteEntry = RemoteEntry(
        id = item.url,
        name = item.name,
        isFolder = item.isFolder,
        playUri = if (item.isFolder) "" else item.url,
        sizeBytes = item.sizeBytes,
        mimeType = item.contentType,
        subtitleUris = if (isVideo(item)) sidecarSubtitles(item, folder).map { it.url } else emptyList(),
        kind = when {
            item.isFolder -> "folder"
            isVideo(item) -> "video"
            isAudio(item) -> "audio"
            isSubtitle(item) -> "subtitle"
            else -> "file"
        }
    )

    /** Friendly text for an HTTP status (spec 4.6 "Errors are friendly and specific"). */
    fun errorFor(status: Int): String = when (status) {
        401 -> "Wrong user name or password. Nextcloud with 2-factor sign-in needs an app password."
        403 -> "This account cannot open that folder"
        404 -> "Folder not found. Check the address"
        405 -> "This address is not a WebDAV folder"
        in 500..599 -> "The server had a problem ($status). Try again later"
        else -> "Server answered $status"
    }
}
