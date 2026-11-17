package com.chaoswares.chaosplay.network

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class TvRulesTest {

    @Test fun `search request and renderer replies`() {
        assertThat(TvRules.searchRequest()).contains("ST: urn:schemas-upnp-org:device:MediaRenderer:1")
        val reply = "HTTP/1.1 200 OK\r\nLOCATION: http://192.168.0.50:9197/dmr\r\nST: urn:schemas-upnp-org:device:MediaRenderer:1\r\n\r\n"
        assertThat(TvRules.parseSearchReply(reply)).isEqualTo("http://192.168.0.50:9197/dmr")
        assertThat(TvRules.parseSearchReply("HTTP/1.1 200 OK\r\nLOCATION: http://x/\r\nST: urn:schemas-upnp-org:device:MediaServer:1\r\n")).isNull()
        assertThat(TvRules.parseSearchReply("HTTP/1.1 200 OK\r\nST: urn:schemas-upnp-org:service:AVTransport:1\r\nLOCATION: http://tv/d.xml\r\n"))
            .isEqualTo("http://tv/d.xml")
    }

    private val samsung = """<?xml version="1.0"?>
<root xmlns="urn:schemas-upnp-org:device-1-0"><specVersion><major>1</major><minor>0</minor></specVersion>
<device><deviceType>urn:schemas-upnp-org:device:MediaRenderer:1</deviceType>
<friendlyName>[TV] Samsung Q80 Series (55)</friendlyName><manufacturer>Samsung Electronics</manufacturer><UDN>uuid:abc</UDN>
<serviceList>
<service><serviceType>urn:schemas-upnp-org:service:RenderingControl:1</serviceType><controlURL>/upnp/control/RenderingControl1</controlURL></service>
<service><serviceType>urn:schemas-upnp-org:service:ConnectionManager:1</serviceType><controlURL>/upnp/control/ConnectionManager1</controlURL></service>
<service><serviceType>urn:schemas-upnp-org:service:AVTransport:1</serviceType><controlURL>/upnp/control/AVTransport1</controlURL></service>
</serviceList></device></root>"""

    @Test fun `renderer description gives transport and volume urls`() {
        val r = TvRules.parseDescription(samsung, "http://192.168.0.50:9197/dmr")!!
        assertThat(r.name).isEqualTo("[TV] Samsung Q80 Series (55)")
        assertThat(r.manufacturer).isEqualTo("Samsung Electronics")
        assertThat(r.avTransportUrl).isEqualTo("http://192.168.0.50:9197/upnp/control/AVTransport1")
        assertThat(r.renderingControlUrl).isEqualTo("http://192.168.0.50:9197/upnp/control/RenderingControl1")
        val noAv = samsung.replace("AVTransport", "Other")
        assertThat(TvRules.parseDescription(noAv, "http://x/")).isNull()
    }

    @Test fun `soap commands`() {
        val set = TvRules.setUri("http://p:1/t/a&b.mkv", TvRules.didl("A & B", "http://p:1/t/a&b.mkv", "video/x-matroska", 3_723_000))
        assertThat(set).contains("<u:SetAVTransportURI xmlns:u=\"urn:schemas-upnp-org:service:AVTransport:1\">")
        assertThat(set).contains("<CurrentURI>http://p:1/t/a&amp;b.mkv</CurrentURI>")
        // Metadata travels escaped inside the SOAP body
        assertThat(set).contains("&lt;DIDL-Lite")
        assertThat(set).contains("protocolInfo=&quot;http-get:*:video/x-matroska:")
        assertThat(TvRules.seek(3_723_400)).contains("<Target>1:02:03</Target>")
        assertThat(TvRules.play()).contains("<Speed>1</Speed>")
        assertThat(TvRules.setVolume(150)).contains("<DesiredVolume>100</DesiredVolume>")
        assertThat(TvRules.soapAction(TvRules.AV_TRANSPORT, "Play")).isEqualTo("\"urn:schemas-upnp-org:service:AVTransport:1#Play\"")
    }

    @Test fun `didl marks audio and video and carries duration`() {
        val v = TvRules.didl("Movie", "http://x/m.mp4", "video/mp4", 5_000)
        assertThat(v).contains("object.item.videoItem")
        assertThat(v).contains("duration=\"0:00:05.000\"")
        assertThat(TvRules.didl("Song", "http://x/s.mp3", "audio/mpeg", 0)).contains("object.item.audioItem")
        assertThat(TvRules.didl("x", "u", "", 0)).contains("http-get:*:video/mp4:")
    }

    @Test fun `position, state and faults`() {
        val pos = """<s:Envelope xmlns:s="x"><s:Body><u:GetPositionInfoResponse xmlns:u="y"><Track>1</Track>
<TrackDuration>0:01:30</TrackDuration><RelTime>0:00:42</RelTime></u:GetPositionInfoResponse></s:Body></s:Envelope>"""
        assertThat(TvRules.parsePosition(pos)).isEqualTo(TvRules.Position(42_000, 90_000))
        assertThat(TvRules.parsePosition("<x/>")).isEqualTo(TvRules.Position(0, 0))
        assertThat(TvRules.parseTransportState("<CurrentTransportState>PAUSED_PLAYBACK</CurrentTransportState>")).isEqualTo("PAUSED_PLAYBACK")
        assertThat(TvRules.faultMessage("<s:Fault><errorDescription>Illegal MIME-type</errorDescription></s:Fault>")).isEqualTo("Illegal MIME-type")
        assertThat(TvRules.faultMessage("<ok/>")).isNull()
        assertThat(TvRules.formatTime(-5)).isEqualTo("0:00:00")
    }

    @Test fun `range header parsing`() {
        assertThat(MediaServerRules.parseRange("bytes=0-99", 1000)).isEqualTo(MediaServerRules.Range(0, 99))
        assertThat(MediaServerRules.parseRange("bytes=500-", 1000)).isEqualTo(MediaServerRules.Range(500, 999))
        assertThat(MediaServerRules.parseRange("bytes=-100", 1000)).isEqualTo(MediaServerRules.Range(900, 999))
        assertThat(MediaServerRules.parseRange("bytes=900-5000", 1000)).isEqualTo(MediaServerRules.Range(900, 999))
        assertThat(MediaServerRules.parseRange("bytes=1000-", 1000)).isEqualTo(MediaServerRules.Range(-1, -1))
        assertThat(MediaServerRules.parseRange("bytes=50-10", 1000)).isEqualTo(MediaServerRules.Range(-1, -1))
        assertThat(MediaServerRules.parseRange("bytes=0-9,20-29", 1000)).isEqualTo(MediaServerRules.Range(0, 9))
        assertThat(MediaServerRules.parseRange(null, 1000)).isNull()
        assertThat(MediaServerRules.parseRange("items=1-2", 1000)).isNull()
        assertThat(MediaServerRules.Range(10, 19).length).isEqualTo(10)
    }

    @Test fun `mime types and tokens`() {
        assertThat(MediaServerRules.mimeFor("a.MKV")).isEqualTo("video/x-matroska")
        assertThat(MediaServerRules.mimeFor("song.flac")).isEqualTo("audio/flac")
        assertThat(MediaServerRules.mimeFor("noext")).isEqualTo("video/mp4")
        assertThat(MediaServerRules.isValidToken("a".repeat(32))).isTrue()
        assertThat(MediaServerRules.isValidToken("../etc/passwd")).isFalse()
        assertThat(MediaServerRules.isValidToken("short")).isFalse()
    }
}
