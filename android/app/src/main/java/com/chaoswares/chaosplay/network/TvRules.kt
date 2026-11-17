package com.chaoswares.chaosplay.network

import org.w3c.dom.Element
import java.io.ByteArrayInputStream
import javax.xml.parsers.DocumentBuilderFactory

/**
 * "Play on TV" through DLNA / UPnP renderers (spec 19.4 "Cast to TVs and
 * speakers (renderer control): send a video from the app to a DLNA
 * renderer"). Most smart TVs (Samsung, LG, Sony, Panasonic) and many
 * receivers are MediaRenderers. Pure rules: SSDP, description, SOAP.
 */
object TvRules {

    const val MEDIA_RENDERER = "urn:schemas-upnp-org:device:MediaRenderer:1"
    const val AV_TRANSPORT = "urn:schemas-upnp-org:service:AVTransport:1"
    const val RENDERING_CONTROL = "urn:schemas-upnp-org:service:RenderingControl:1"

    fun searchRequest(mx: Int = 2): String =
        "M-SEARCH * HTTP/1.1\r\n" +
            "HOST: ${DlnaRules.SSDP_ADDRESS}:${DlnaRules.SSDP_PORT}\r\n" +
            "MAN: \"ssdp:discover\"\r\n" +
            "MX: $mx\r\n" +
            "ST: $MEDIA_RENDERER\r\n" +
            "USER-AGENT: Android/1 UPnP/1.1 CPlay/1.4\r\n\r\n"

    /** LOCATION of a renderer reply; null for anything else. */
    fun parseSearchReply(packet: String): String? {
        val headers = packet.split("\r\n", "\n").drop(1).mapNotNull { line ->
            val i = line.indexOf(':')
            if (i <= 0) null else line.substring(0, i).trim().uppercase() to line.substring(i + 1).trim()
        }.toMap()
        val st = headers["ST"] ?: headers["NT"] ?: return null
        if (!st.contains("MediaRenderer", ignoreCase = true) && !st.contains("AVTransport", ignoreCase = true)) return null
        return headers["LOCATION"]?.takeIf { it.startsWith("http") }
    }

    data class Renderer(
        val name: String,
        val udn: String,
        val avTransportUrl: String,
        val renderingControlUrl: String?,
        val manufacturer: String
    )

    fun parseDescription(xml: String, location: String): Renderer? {
        val f = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
            runCatching { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
            isExpandEntityReferences = false
        }
        val doc = f.newDocumentBuilder().parse(ByteArrayInputStream(xml.trim().toByteArray(Charsets.UTF_8)))
        val root = doc.documentElement
        val base = root.getElementsByTagNameNS("*", "URLBase").item(0)?.textContent?.trim()?.takeIf { it.isNotEmpty() } ?: location
        val devices = root.getElementsByTagNameNS("*", "device")
        for (i in 0 until devices.length) {
            val device = devices.item(i) as Element
            var av: String? = null
            var rc: String? = null
            val services = device.getElementsByTagNameNS("*", "service")
            for (j in 0 until services.length) {
                val svc = services.item(j) as Element
                if (svc.parentNode?.parentNode !== device) continue // only this device's own services
                val type = svc.getElementsByTagNameNS("*", "serviceType").item(0)?.textContent?.trim().orEmpty()
                val control = svc.getElementsByTagNameNS("*", "controlURL").item(0)?.textContent?.trim() ?: continue
                if (type.startsWith("urn:schemas-upnp-org:service:AVTransport:")) av = DlnaRules.resolve(base, control)
                if (type.startsWith("urn:schemas-upnp-org:service:RenderingControl:")) rc = DlnaRules.resolve(base, control)
            }
            if (av != null) {
                fun text(tag: String) = (0 until device.childNodes.length).map { device.childNodes.item(it) }
                    .firstOrNull { (it.localName ?: it.nodeName.substringAfter(':')) == tag }?.textContent?.trim().orEmpty()
                return Renderer(
                    name = text("friendlyName").ifBlank { JellyfinRules.hostOf(location) },
                    udn = text("UDN"),
                    avTransportUrl = av,
                    renderingControlUrl = rc,
                    manufacturer = text("manufacturer")
                )
            }
        }
        return null
    }

    fun soapAction(service: String, action: String) = "\"$service#$action\""

    fun envelope(service: String, action: String, args: List<Pair<String, String>>): String {
        val body = args.joinToString("") { (k, v) -> "<$k>${DlnaRules.escapeXml(v)}</$k>" }
        return """<?xml version="1.0" encoding="utf-8"?>
<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/">
<s:Body><u:$action xmlns:u="$service">$body</u:$action></s:Body></s:Envelope>"""
    }

    /**
     * DIDL-Lite metadata sent with the URI: many TVs (Samsung, LG) refuse a
     * URI without a protocolInfo telling them the media type.
     */
    fun didl(title: String, url: String, mime: String, durationMs: Long): String {
        val cls = if (mime.startsWith("audio")) "object.item.audioItem.musicTrack" else "object.item.videoItem"
        val dur = if (durationMs > 0) " duration=\"${formatTime(durationMs)}.000\"" else ""
        val flags = "DLNA.ORG_OP=01;DLNA.ORG_CI=0;DLNA.ORG_FLAGS=01700000000000000000000000000000"
        return "<DIDL-Lite xmlns=\"urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/\" xmlns:dc=\"http://purl.org/dc/elements/1.1/\" " +
            "xmlns:upnp=\"urn:schemas-upnp-org:metadata-1-0/upnp/\">" +
            "<item id=\"0\" parentID=\"-1\" restricted=\"1\"><dc:title>${DlnaRules.escapeXml(title)}</dc:title>" +
            "<upnp:class>$cls</upnp:class><res protocolInfo=\"http-get:*:${mime.ifBlank { "video/mp4" }}:$flags\"$dur>" +
            "${DlnaRules.escapeXml(url)}</res></item></DIDL-Lite>"
    }

    fun setUri(url: String, metadata: String) = envelope(
        AV_TRANSPORT, "SetAVTransportURI",
        listOf("InstanceID" to "0", "CurrentURI" to url, "CurrentURIMetaData" to metadata)
    )

    fun play() = envelope(AV_TRANSPORT, "Play", listOf("InstanceID" to "0", "Speed" to "1"))
    fun pause() = envelope(AV_TRANSPORT, "Pause", listOf("InstanceID" to "0"))
    fun stop() = envelope(AV_TRANSPORT, "Stop", listOf("InstanceID" to "0"))
    fun seek(positionMs: Long) = envelope(
        AV_TRANSPORT, "Seek", listOf("InstanceID" to "0", "Unit" to "REL_TIME", "Target" to formatTime(positionMs))
    )
    fun positionInfo() = envelope(AV_TRANSPORT, "GetPositionInfo", listOf("InstanceID" to "0"))
    fun transportInfo() = envelope(AV_TRANSPORT, "GetTransportInfo", listOf("InstanceID" to "0"))
    fun setVolume(volume: Int) = envelope(
        RENDERING_CONTROL, "SetVolume",
        listOf("InstanceID" to "0", "Channel" to "Master", "DesiredVolume" to volume.coerceIn(0, 100).toString())
    )

    /** "1:02:03" (UPnP H+:MM:SS). */
    fun formatTime(ms: Long): String {
        val s = (ms.coerceAtLeast(0L)) / 1000
        return "${s / 3600}:${"%02d".format((s % 3600) / 60)}:${"%02d".format(s % 60)}"
    }

    private fun tag(xml: String, name: String): String? =
        Regex("<(?:\\w+:)?$name>([^<]*)</(?:\\w+:)?$name>").find(xml)?.groupValues?.get(1)?.trim()

    data class Position(val positionMs: Long, val durationMs: Long)

    fun parsePosition(xml: String): Position = Position(
        DlnaRules.parseDuration(tag(xml, "RelTime")),
        DlnaRules.parseDuration(tag(xml, "TrackDuration"))
    )

    /** PLAYING, PAUSED_PLAYBACK, STOPPED, TRANSITIONING, NO_MEDIA_PRESENT. */
    fun parseTransportState(xml: String): String = tag(xml, "CurrentTransportState").orEmpty()

    fun faultMessage(xml: String): String? {
        if (!xml.contains("Fault")) return null
        return tag(xml, "errorDescription") ?: tag(xml, "faultstring") ?: "The TV refused the command"
    }
}

/**
 * The phone's built-in media server for "Play on TV": a TV cannot open
 * content:// URIs, SMB shares or password-protected WebDAV, so the phone
 * serves the bytes over HTTP with Range support (TVs seek with Range).
 */
object MediaServerRules {

    data class Range(val start: Long, val end: Long) {
        val length: Long get() = end - start + 1
    }

    /**
     * Parses a single-range "bytes=a-b" / "bytes=a-" / "bytes=-n" header
     * against [size]. Null means "send the whole file"; an unsatisfiable
     * range returns Range(-1, -1) so the server can answer 416.
     */
    fun parseRange(header: String?, size: Long): Range? {
        if (header.isNullOrBlank() || size <= 0) return null
        val m = Regex("""^\s*bytes\s*=\s*(\d*)\s*-\s*(\d*)\s*$""").find(header.substringBefore(',')) ?: return null
        val (a, b) = m.destructured
        if (a.isEmpty() && b.isEmpty()) return null
        return if (a.isEmpty()) {
            val n = b.toLong()
            if (n <= 0) Range(-1, -1) else Range((size - n).coerceAtLeast(0), size - 1)
        } else {
            val start = a.toLong()
            if (start >= size) return Range(-1, -1)
            val end = if (b.isEmpty()) size - 1 else b.toLong().coerceAtMost(size - 1)
            if (end < start) Range(-1, -1) else Range(start, end)
        }
    }

    fun mimeFor(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
        "mp4", "m4v" -> "video/mp4"
        "mkv" -> "video/x-matroska"
        "webm" -> "video/webm"
        "avi" -> "video/x-msvideo"
        "mov" -> "video/quicktime"
        "ts", "m2ts", "mts" -> "video/mp2t"
        "3gp" -> "video/3gpp"
        "wmv" -> "video/x-ms-wmv"
        "flv" -> "video/x-flv"
        "mpg", "mpeg", "vob" -> "video/mpeg"
        "mp3" -> "audio/mpeg"
        "m4a", "aac" -> "audio/mp4"
        "flac" -> "audio/flac"
        "ogg", "opus" -> "audio/ogg"
        "wav" -> "audio/wav"
        "srt" -> "application/x-subrip"
        else -> "video/mp4"
    }

    /** Path segment that names the shared item; random so other LAN devices cannot guess it. */
    fun isValidToken(token: String): Boolean = token.length in 16..64 && token.all { it.isLetterOrDigit() }
}
