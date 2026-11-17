package com.chaoswares.chaosplay.network

import org.w3c.dom.Document
import org.w3c.dom.Element
import java.io.ByteArrayInputStream
import java.net.URI
import javax.xml.parsers.DocumentBuilderFactory

/**
 * DLNA / UPnP media server client rules (spec 19.4 "discover UPnP/DLNA
 * media servers using SSDP and list their content"): SSDP search and
 * replies, the device description, the ContentDirectory Browse SOAP call
 * and DIDL-Lite results. Pure (java.net + DOM) so it is unit tested
 * against real server payloads.
 */
object DlnaRules {

    const val SSDP_ADDRESS = "239.255.255.250"
    const val SSDP_PORT = 1900
    const val MEDIA_SERVER = "urn:schemas-upnp-org:device:MediaServer:1"
    const val CONTENT_DIRECTORY = "urn:schemas-upnp-org:service:ContentDirectory:1"
    const val ROOT_ID = "0"
    const val PAGE = 200

    fun searchRequest(mx: Int = 2): String =
        "M-SEARCH * HTTP/1.1\r\n" +
            "HOST: $SSDP_ADDRESS:$SSDP_PORT\r\n" +
            "MAN: \"ssdp:discover\"\r\n" +
            "MX: $mx\r\n" +
            "ST: $MEDIA_SERVER\r\n" +
            "USER-AGENT: Android/1 UPnP/1.1 CPlay/1.2\r\n\r\n"

    data class SsdpReply(val location: String, val st: String, val usn: String, val server: String)

    /** Parses an SSDP search reply or NOTIFY; null when it is not a media server with a location. */
    fun parseSsdp(packet: String): SsdpReply? {
        val headers = packet.split("\r\n", "\n")
            .drop(1)
            .mapNotNull { line ->
                val i = line.indexOf(':')
                if (i <= 0) null else line.substring(0, i).trim().uppercase() to line.substring(i + 1).trim()
            }.toMap()
        val location = headers["LOCATION"]?.takeIf { it.startsWith("http") } ?: return null
        val st = headers["ST"] ?: headers["NT"] ?: ""
        if (st.isNotEmpty() && !st.contains("MediaServer", true) && !st.contains("ContentDirectory", true) &&
            st != "upnp:rootdevice" && st != "ssdp:all"
        ) return null
        return SsdpReply(location, st, headers["USN"].orEmpty(), headers["SERVER"].orEmpty())
    }

    data class Device(
        val name: String,
        val controlUrl: String,
        val iconUrl: String?,
        val udn: String
    )

    private fun parseXml(xml: String): Document {
        val f = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
            runCatching { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
            isExpandEntityReferences = false
        }
        return f.newDocumentBuilder().parse(ByteArrayInputStream(xml.trim().toByteArray(Charsets.UTF_8)))
    }

    private fun Element.child(local: String): Element? {
        val nodes = getElementsByTagNameNS("*", local)
        return if (nodes.length > 0) nodes.item(0) as Element else null
    }

    private fun Element.directChild(local: String): Element? {
        var n = firstChild
        while (n != null) {
            if (n is Element && (n.localName ?: n.nodeName.substringAfter(':')) == local) return n
            n = n.nextSibling
        }
        return null
    }

    private fun Element.text(local: String): String? = directChild(local)?.textContent?.trim()

    /**
     * Reads the device description: friendly name and the ContentDirectory
     * control URL, resolved against URLBase or the description location.
     */
    fun parseDescription(xml: String, location: String): Device? {
        val doc = parseXml(xml)
        val root = doc.documentElement
        val base = root.text("URLBase")?.takeIf { it.isNotBlank() } ?: location
        val devices = root.getElementsByTagNameNS("*", "device")
        for (i in 0 until devices.length) {
            val device = devices.item(i) as Element
            val services = device.directChild("serviceList")?.getElementsByTagNameNS("*", "service") ?: continue
            for (j in 0 until services.length) {
                val svc = services.item(j) as Element
                val type = svc.text("serviceType").orEmpty()
                if (!type.startsWith("urn:schemas-upnp-org:service:ContentDirectory:")) continue
                val control = svc.text("controlURL") ?: continue
                val icon = device.directChild("iconList")?.getElementsByTagNameNS("*", "url")
                    ?.takeIf { it.length > 0 }?.item(0)?.textContent?.trim()
                return Device(
                    name = device.text("friendlyName").orEmpty().ifBlank { JellyfinRules.hostOf(location) },
                    controlUrl = resolve(base, control),
                    iconUrl = icon?.let { resolve(base, it) },
                    udn = device.text("UDN").orEmpty()
                )
            }
        }
        return null
    }

    fun resolve(base: String, ref: String): String =
        runCatching { URI(base).resolve(ref.trim()).toString() }.getOrDefault(ref)

    fun escapeXml(s: String): String = s
        .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        .replace("\"", "&quot;").replace("'", "&apos;")

    const val SOAP_ACTION = "\"$CONTENT_DIRECTORY#Browse\""

    fun browseBody(objectId: String, start: Int = 0, count: Int = PAGE): String =
        """<?xml version="1.0" encoding="utf-8"?>
<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/">
<s:Body><u:Browse xmlns:u="$CONTENT_DIRECTORY">
<ObjectID>${escapeXml(objectId)}</ObjectID>
<BrowseFlag>BrowseDirectChildren</BrowseFlag>
<Filter>*</Filter>
<StartingIndex>$start</StartingIndex>
<RequestedCount>$count</RequestedCount>
<SortCriteria></SortCriteria>
</u:Browse></s:Body></s:Envelope>"""

    data class BrowseResult(val items: List<DlnaObject>, val returned: Int, val total: Int)

    data class DlnaObject(
        val id: String,
        val title: String,
        val isContainer: Boolean,
        val url: String? = null,
        val mime: String = "",
        val durationMs: Long = 0L,
        val sizeBytes: Long = 0L,
        val artUrl: String? = null,
        val upnpClass: String = "",
        val childCount: Int? = null,
        val subtitleUrl: String? = null
    ) {
        val isVideo get() = upnpClass.startsWith("object.item.videoItem") || mime.startsWith("video/")
        val isAudio get() = upnpClass.startsWith("object.item.audioItem") || mime.startsWith("audio/")
    }

    /** Parses the SOAP BrowseResponse; the DIDL-Lite inside is escaped text. */
    fun parseBrowseResponse(xml: String): BrowseResult {
        val doc = parseXml(xml)
        val fault = doc.getElementsByTagNameNS("*", "Fault")
        if (fault.length > 0) {
            val desc = (fault.item(0) as Element).child("errorDescription")?.textContent
                ?: fault.item(0).textContent.trim()
            throw IllegalStateException("Server refused to list this folder: ${desc.take(120)}")
        }
        val resp = doc.documentElement
        val result = resp.getElementsByTagNameNS("*", "Result").item(0)?.textContent.orEmpty()
        val returned = resp.getElementsByTagNameNS("*", "NumberReturned").item(0)?.textContent?.trim()?.toIntOrNull() ?: 0
        val total = resp.getElementsByTagNameNS("*", "TotalMatches").item(0)?.textContent?.trim()?.toIntOrNull() ?: returned
        return BrowseResult(if (result.isBlank()) emptyList() else parseDidl(result), returned, total)
    }

    fun parseDidl(didl: String): List<DlnaObject> {
        val doc = parseXml(didl)
        val out = mutableListOf<DlnaObject>()
        var n = doc.documentElement.firstChild
        while (n != null) {
            if (n is Element) {
                val local = n.localName ?: n.nodeName
                val isContainer = local == "container"
                if (isContainer || local == "item") {
                    val id = n.getAttribute("id")
                    val title = n.child("title")?.textContent?.trim().orEmpty()
                    val cls = n.child("class")?.textContent?.trim().orEmpty()
                    var url: String? = null
                    var mime = ""
                    var duration = 0L
                    var size = 0L
                    var sub: String? = null
                    val resList = n.getElementsByTagNameNS("*", "res")
                    for (r in 0 until resList.length) {
                        val res = resList.item(r) as Element
                        val protocol = res.getAttribute("protocolInfo")
                        val m = protocol.split(':').getOrNull(2).orEmpty()
                        // Prefer the first http-get resource that is media, not a thumbnail
                        if (url == null && protocol.startsWith("http-get") && !m.startsWith("image/")) {
                            url = res.textContent.trim()
                            mime = m
                            duration = parseDuration(res.getAttribute("duration"))
                            size = res.getAttribute("size").toLongOrNull() ?: 0L
                        }
                        if (m.contains("srt") || m.contains("subrip") || m.contains("vtt")) sub = res.textContent.trim()
                    }
                    // Samsung/serviio style subtitle hints
                    n.getElementsByTagNameNS("*", "CaptionInfoEx").item(0)?.textContent?.trim()?.let { if (sub == null) sub = it }
                    val art = n.child("albumArtURI")?.textContent?.trim()
                    out += DlnaObject(
                        id = id,
                        title = title.ifBlank { id },
                        isContainer = isContainer,
                        url = url,
                        mime = mime,
                        durationMs = duration,
                        sizeBytes = size,
                        artUrl = art,
                        upnpClass = cls,
                        childCount = if (isContainer) n.getAttribute("childCount").toIntOrNull() else null,
                        subtitleUrl = sub
                    )
                }
            }
            n = n.nextSibling
        }
        return out
    }

    /** "1:02:03.500" / "0:00:05" / "00:01:02" -> ms; 0 when absent or malformed. */
    fun parseDuration(value: String?): Long {
        if (value.isNullOrBlank()) return 0L
        val m = Regex("""^(\d+):(\d{1,2}):(\d{1,2})(?:\.(\d+))?""").find(value.trim()) ?: return 0L
        val (h, mm, s, frac) = m.destructured
        val fracMs = if (frac.isEmpty()) 0L else (("0.$frac").toDouble() * 1000).toLong()
        return ((h.toLong() * 60 + mm.toLong()) * 60 + s.toLong()) * 1000 + fracMs
    }

    /** Containers first, then playable media; pictures and other items hidden. */
    fun toEntries(items: List<DlnaObject>, profileId: String): List<RemoteEntry> =
        items.filter { it.isContainer || ((it.isVideo || it.isAudio) && it.url != null) }
            .map { o ->
                RemoteEntry(
                    id = o.id,
                    name = o.title,
                    isFolder = o.isContainer,
                    playUri = o.url.orEmpty(),
                    sizeBytes = o.sizeBytes,
                    durationMs = o.durationMs,
                    mimeType = o.mime,
                    imageUrl = o.artUrl,
                    subtitle = when {
                        o.isContainer -> o.childCount?.let { "$it items" }.orEmpty()
                        else -> o.mime.substringAfter('/').uppercase()
                    },
                    subtitleUris = listOfNotNull(o.subtitleUrl),
                    kind = if (o.isContainer) "folder" else if (o.isVideo) "video" else "audio"
                )
            }
}

/**
 * Audio delay, spec 6 "Audio delay / sync adjustment". Media3's playback
 * clock is the audio position, so delaying audio by D means reporting a
 * clock that is D ahead: video frames are then shown D earlier relative to
 * the sound, i.e. the sound arrives D later.
 */
object AvSync {

    const val STEP_MS = 50L
    const val MAX_MS = 10_000L

    fun clamp(delayMs: Long): Long = delayMs.coerceIn(-MAX_MS, MAX_MS)

    /** Clock the player should see for a real audio position, never negative. */
    fun clockUs(audioPositionUs: Long, delayMs: Long, notSet: Long = Long.MIN_VALUE): Long {
        if (audioPositionUs == notSet) return audioPositionUs
        return (audioPositionUs + clamp(delayMs) * 1000L).coerceAtLeast(0L)
    }

    fun format(delayMs: Long): String {
        val d = clamp(delayMs)
        val sign = if (d > 0) "+" else if (d < 0) "-" else ""
        val abs = kotlin.math.abs(d)
        return "$sign${abs / 1000}.${"%02d".format((abs % 1000) / 10)} s"
    }
}
