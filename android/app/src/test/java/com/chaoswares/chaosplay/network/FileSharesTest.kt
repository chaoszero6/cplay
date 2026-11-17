package com.chaoswares.chaosplay.network

import com.chaoswares.chaosplay.player.AudioRules
import com.chaoswares.chaosplay.player.PlayerPrefs
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class FileSharesTest {

    // ---- remote URIs ----

    @Test fun `remote uris round trip names with spaces and unicode and carry no secrets`() {
        val uri = RemoteUris.build(RemoteUris.SMB, "p1", "/Movies/My Film #1 (தமிழ்).mkv")
        assertThat(uri).startsWith("chaos-smb://p1/Movies/My%20Film%20%231")
        assertThat(RemoteUris.parse(uri)).isEqualTo(RemoteUris.Parsed("chaos-smb", "p1", "/Movies/My Film #1 (தமிழ்).mkv"))
        assertThat(RemoteUris.isRemote(uri)).isTrue()
        assertThat(RemoteUris.isRemote("https://x/y.mp4")).isFalse()
        assertThat(RemoteUris.parse("chaos-ftp://p2/a+b.mp4")!!.path).isEqualTo("/a+b.mp4")
        assertThat(RemoteUris.parse("chaos-nfs:///x")).isNull()
        assertThat(RemoteUris.parse("http://p/x")).isNull()
        assertThat(RemoteUris.build(RemoteUris.NFS, "p", "/")).isEqualTo("chaos-nfs://p/")
        assertThat(RemoteUris.schemeFor(ServerType.SFTP)).isEqualTo(RemoteUris.SFTP)
        assertThat(RemoteUris.schemeFor(ServerType.DLNA)).isNull()
    }

    @Test fun `paths normalise, join, climb and crumb`() {
        assertThat(RemotePaths.normalise("a\\b//c/./d/../")).isEqualTo("/a/b/c")
        assertThat(RemotePaths.normalise("")).isEqualTo("/")
        assertThat(RemotePaths.normalise("/../..")).isEqualTo("/")
        assertThat(RemotePaths.join("/Movies/", "x.mkv")).isEqualTo("/Movies/x.mkv")
        assertThat(RemotePaths.parent("/Movies/Kids")).isEqualTo("/Movies")
        assertThat(RemotePaths.parent("/Movies")).isEqualTo("/")
        assertThat(RemotePaths.parent("/")).isNull()
        assertThat(RemotePaths.name("/a/b.mkv")).isEqualTo("b.mkv")
        assertThat(RemotePaths.crumbs("/Movies/Kids").map { it.second }).containsExactly("/", "/Movies", "/Movies/Kids").inOrder()
        assertThat(RemotePaths.splitFirst("/share/dir/f")).isEqualTo("share" to "/dir/f")
        assertThat(RemotePaths.splitFirst("/")).isEqualTo("" to "/")
    }

    @Test fun `share listing hides junk, sorts folders first, finds sidecars`() {
        val files = listOf(
            RemoteFile("Zoo.mkv", "/m/Zoo.mkv", false, 10),
            RemoteFile("Zoo.en.srt", "/m/Zoo.en.srt", false, 1),
            RemoteFile("zoo.ta.ass", "/m/zoo.ta.ass", false, 1),
            RemoteFile("Other.srt", "/m/Other.srt", false, 1),
            RemoteFile("album", "/m/album", true),
            RemoteFile(".DS_Store", "/m/.DS_Store", false),
            RemoteFile("ADMIN$", "/m/ADMIN$", true),
            RemoteFile("\$RECYCLE.BIN", "/m/\$RECYCLE.BIN", true),
            RemoteFile("song.flac", "/m/song.flac", false),
            RemoteFile("notes.txt", "/m/notes.txt", false)
        )
        assertThat(RemoteFileRules.visible(files, false).map { it.name }).containsExactly("album", "song.flac", "Zoo.mkv").inOrder()
        assertThat(RemoteFileRules.visible(files, true).map { it.name }).contains("notes.txt")
        val zoo = files.first()
        assertThat(RemoteFileRules.sidecars(zoo, files).map { it.name }).containsExactly("Zoo.en.srt", "zoo.ta.ass")
        val entry = RemoteFileRules.toEntry(zoo, files) { RemoteUris.build(RemoteUris.SMB, "p", it) }
        assertThat(entry.kind).isEqualTo("video")
        assertThat(entry.playUri).isEqualTo("chaos-smb://p/m/Zoo.mkv")
        assertThat(entry.subtitleUris).hasSize(2)
        assertThat(RemoteFileRules.toEntry(files[4], files) { it }.isPlayable).isFalse()
    }

    // ---- addresses ----

    @Test fun `smb addresses in every common spelling`() {
        assertThat(SmbAddress.parse("\\\\nas\\Movies\\Kids")).isEqualTo(SmbAddress.Parts("nas", 445, "Movies", "/Kids"))
        assertThat(SmbAddress.parse("smb://192.168.1.5/Video")).isEqualTo(SmbAddress.Parts("192.168.1.5", 445, "Video", "/"))
        assertThat(SmbAddress.parse("nas:4450")).isEqualTo(SmbAddress.Parts("nas", 4450, "", "/"))
        assertThat(SmbAddress.parse("  ")).isNull()
        assertThat(SmbAddress.parse("nas:99999")).isNull()
        assertThat(SmbAddress.parse("nas:abc")).isNull()
        assertThat(SmbAddress.format(SmbAddress.Parts("nas", 445, "Video", "/"))).isEqualTo("smb://nas:445/Video")
    }

    @Test fun `smb user with domain`() {
        assertThat(SmbAddress.splitUser("WORKGROUP\\bob")).isEqualTo("WORKGROUP" to "bob")
        assertThat(SmbAddress.splitUser("bob@corp.local")).isEqualTo("corp.local" to "bob")
        assertThat(SmbAddress.splitUser(" bob ")).isEqualTo("" to "bob")
    }

    @Test fun `ftp and sftp addresses`() {
        assertThat(FtpAddress.parse("nas", defaultSftp = false)).isEqualTo(FtpAddress.Parts(false, "nas", 21, "/"))
        assertThat(FtpAddress.parse("nas", defaultSftp = true)).isEqualTo(FtpAddress.Parts(true, "nas", 22, "/"))
        assertThat(FtpAddress.parse("sftp://user@box:2222/home/me/Videos/", false))
            .isEqualTo(FtpAddress.Parts(true, "box", 2222, "/home/me/Videos"))
        assertThat(FtpAddress.parse("ftp://10.0.0.2:2121/pub", true)).isEqualTo(FtpAddress.Parts(false, "10.0.0.2", 2121, "/pub"))
        assertThat(FtpAddress.parse("ftp://:21/x", false)).isNull()
        assertThat(FtpAddress.format(FtpAddress.Parts(true, "box", 22, "/v"))).isEqualTo("sftp://box:22/v")
    }

    @Test fun `nfs exports`() {
        assertThat(NfsAddress.parse("nas:/volume1/video")).isEqualTo(NfsAddress.Parts("nas", "/volume1/video"))
        assertThat(NfsAddress.parse("nfs://192.168.1.2/export/media/")).isEqualTo(NfsAddress.Parts("192.168.1.2", "/export/media"))
        assertThat(NfsAddress.parse("nas")).isNull()
        assertThat(NfsAddress.parse("nas:/")).isNull()
        assertThat(NfsAddress.format(NfsAddress.Parts("nas", "/v"))).isEqualTo("nfs://nas/v")
    }

    // ---- DLNA ----

    @Test fun `ssdp search and replies`() {
        val req = DlnaRules.searchRequest()
        assertThat(req).startsWith("M-SEARCH * HTTP/1.1\r\n")
        assertThat(req).contains("ST: urn:schemas-upnp-org:device:MediaServer:1")
        assertThat(req).endsWith("\r\n\r\n")
        val reply = "HTTP/1.1 200 OK\r\nCACHE-CONTROL: max-age=1810\r\nLocation: http://192.168.1.9:8200/rootDesc.xml\r\n" +
            "SERVER: Debian MiniDLNA/1.3.3\r\nST: urn:schemas-upnp-org:device:MediaServer:1\r\nUSN: uuid:4d696e69::x\r\n\r\n"
        assertThat(DlnaRules.parseSsdp(reply)).isEqualTo(
            DlnaRules.SsdpReply("http://192.168.1.9:8200/rootDesc.xml", "urn:schemas-upnp-org:device:MediaServer:1", "uuid:4d696e69::x", "Debian MiniDLNA/1.3.3")
        )
        assertThat(DlnaRules.parseSsdp("HTTP/1.1 200 OK\r\nST: urn:schemas-upnp-org:device:MediaRenderer:1\r\nLOCATION: http://x/d.xml\r\n")).isNull()
        assertThat(DlnaRules.parseSsdp("HTTP/1.1 200 OK\r\nST: urn:schemas-upnp-org:device:MediaServer:1\r\n")).isNull()
    }

    private val description = """<?xml version="1.0"?>
<root xmlns="urn:schemas-upnp-org:device-1-0"><specVersion><major>1</major><minor>0</minor></specVersion>
<device><deviceType>urn:schemas-upnp-org:device:MediaServer:1</deviceType><friendlyName>NAS: minidlna</friendlyName>
<UDN>uuid:4d696e69-444c-164e-9d41-001</UDN>
<iconList><icon><mimetype>image/png</mimetype><url>/icons/sm.png</url></icon></iconList>
<serviceList>
<service><serviceType>urn:schemas-upnp-org:service:ConnectionManager:1</serviceType><controlURL>/ctl/ConnectionMgr</controlURL></service>
<service><serviceType>urn:schemas-upnp-org:service:ContentDirectory:1</serviceType><controlURL>/ctl/ContentDir</controlURL></service>
</serviceList></device></root>"""

    @Test fun `device description resolves the content directory url`() {
        val d = DlnaRules.parseDescription(description, "http://192.168.1.9:8200/rootDesc.xml")!!
        assertThat(d.name).isEqualTo("NAS: minidlna")
        assertThat(d.controlUrl).isEqualTo("http://192.168.1.9:8200/ctl/ContentDir")
        assertThat(d.iconUrl).isEqualTo("http://192.168.1.9:8200/icons/sm.png")
        val withBase = description.replace("<specVersion>", "<URLBase>http://10.0.0.1:9000/</URLBase><specVersion>")
        assertThat(DlnaRules.parseDescription(withBase, "http://x/rootDesc.xml")!!.controlUrl).isEqualTo("http://10.0.0.1:9000/ctl/ContentDir")
        assertThat(DlnaRules.parseDescription("<root xmlns=\"urn:schemas-upnp-org:device-1-0\"><device/></root>", "http://x/")).isNull()
    }

    @Test fun `browse body escapes the object id`() {
        val body = DlnaRules.browseBody("64$1&<x>", 200, 50)
        assertThat(body).contains("<ObjectID>64$1&amp;&lt;x&gt;</ObjectID>")
        assertThat(body).contains("<StartingIndex>200</StartingIndex>")
        assertThat(body).contains("<RequestedCount>50</RequestedCount>")
    }

    private val didl = """<DIDL-Lite xmlns:dc="http://purl.org/dc/elements/1.1/" xmlns:upnp="urn:schemas-upnp-org:metadata-1-0/upnp/" xmlns="urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/" xmlns:dlna="urn:schemas-dlna-org:metadata-1-0/">
<container id="64$0" parentID="64" restricted="1" searchable="1" childCount="3"><dc:title>Movies &amp; Shows</dc:title><upnp:class>object.container.storageFolder</upnp:class></container>
<item id="64$1" parentID="64" restricted="1"><dc:title>Big Buck Bunny</dc:title><upnp:class>object.item.videoItem</upnp:class>
<upnp:albumArtURI>http://192.168.1.9:8200/AlbumArt/1.jpg</upnp:albumArtURI>
<res size="73400320" duration="0:09:56.458" protocolInfo="http-get:*:video/x-matroska:*">http://192.168.1.9:8200/MediaItems/22.mkv</res>
<res protocolInfo="http-get:*:text/srt:*">http://192.168.1.9:8200/Captions/22.srt</res></item>
<item id="64$2" parentID="64"><dc:title>Holiday.jpg</dc:title><upnp:class>object.item.imageItem.photo</upnp:class><res protocolInfo="http-get:*:image/jpeg:*">http://x/p.jpg</res></item>
<item id="64$3" parentID="64"><dc:title>Song</dc:title><upnp:class>object.item.audioItem.musicTrack</upnp:class><res protocolInfo="http-get:*:image/jpeg:DLNA.ORG_PN=JPEG_TN">http://x/thumb.jpg</res><res duration="0:03:00" protocolInfo="http-get:*:audio/mpeg:*">http://x/s.mp3</res></item>
</DIDL-Lite>"""

    @Test fun `didl lite items, containers and resources`() {
        val items = DlnaRules.parseDidl(didl)
        assertThat(items).hasSize(4)
        val folder = items[0]
        assertThat(folder.isContainer).isTrue()
        assertThat(folder.title).isEqualTo("Movies & Shows")
        assertThat(folder.childCount).isEqualTo(3)
        val movie = items[1]
        assertThat(movie.url).isEqualTo("http://192.168.1.9:8200/MediaItems/22.mkv")
        assertThat(movie.mime).isEqualTo("video/x-matroska")
        assertThat(movie.durationMs).isEqualTo(596_458L)
        assertThat(movie.sizeBytes).isEqualTo(73_400_320L)
        assertThat(movie.subtitleUrl).isEqualTo("http://192.168.1.9:8200/Captions/22.srt")
        assertThat(movie.isVideo).isTrue()
        // The thumbnail resource is skipped in favour of the audio stream
        assertThat(items[3].url).isEqualTo("http://x/s.mp3")
        val entries = DlnaRules.toEntries(items, "p")
        assertThat(entries.map { it.name }).containsExactly("Movies & Shows", "Big Buck Bunny", "Song").inOrder()
        assertThat(entries[1].playUri).isEqualTo("http://192.168.1.9:8200/MediaItems/22.mkv")
        assertThat(entries[1].subtitleUris).containsExactly("http://192.168.1.9:8200/Captions/22.srt")
        assertThat(entries[0].subtitle).isEqualTo("3 items")
    }

    @Test fun `browse response unwraps escaped didl and counts`() {
        val escaped = didl.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        val soap = """<?xml version="1.0"?><s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/"><s:Body>
<u:BrowseResponse xmlns:u="urn:schemas-upnp-org:service:ContentDirectory:1"><Result>$escaped</Result>
<NumberReturned>4</NumberReturned><TotalMatches>250</TotalMatches><UpdateID>7</UpdateID></u:BrowseResponse></s:Body></s:Envelope>"""
        val r = DlnaRules.parseBrowseResponse(soap)
        assertThat(r.items).hasSize(4)
        assertThat(r.returned).isEqualTo(4)
        assertThat(r.total).isEqualTo(250)
    }

    @Test fun `browse fault becomes a readable error`() {
        val fault = """<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/"><s:Body><s:Fault><faultcode>s:Client</faultcode>
<detail><UPnPError xmlns="urn:schemas-upnp-org:control-1-0"><errorCode>701</errorCode><errorDescription>No such object</errorDescription></UPnPError></detail></s:Fault></s:Body></s:Envelope>"""
        val e = runCatching { DlnaRules.parseBrowseResponse(fault) }.exceptionOrNull()
        assertThat(e).isNotNull()
        assertThat(e!!.message).contains("No such object")
    }

    @Test fun `upnp durations`() {
        assertThat(DlnaRules.parseDuration("1:02:03.5")).isEqualTo(3_723_500L)
        assertThat(DlnaRules.parseDuration("0:00:05")).isEqualTo(5_000L)
        assertThat(DlnaRules.parseDuration("")).isEqualTo(0L)
        assertThat(DlnaRules.parseDuration("bogus")).isEqualTo(0L)
        assertThat(DlnaRules.resolve("http://h:1/a/desc.xml", "ctl/x")).isEqualTo("http://h:1/a/ctl/x")
    }

    // ---- audio delay and levelling ----

    @Test fun `audio delay shifts the clock the right way and clamps`() {
        // +200 ms delay: the clock runs 200 ms ahead, so sound arrives later
        assertThat(AvSync.clockUs(1_000_000, 200)).isEqualTo(1_200_000)
        assertThat(AvSync.clockUs(100_000, -500)).isEqualTo(0)
        assertThat(AvSync.clockUs(Long.MIN_VALUE, 300)).isEqualTo(Long.MIN_VALUE)
        assertThat(AvSync.clamp(99_999)).isEqualTo(AvSync.MAX_MS)
        assertThat(AvSync.format(0)).isEqualTo("0.00 s")
        assertThat(AvSync.format(150)).isEqualTo("+0.15 s")
        assertThat(AvSync.format(-1_250)).isEqualTo("-1.25 s")
    }

    @Test fun `leveller lifts quiet sound and caps loud sound`() {
        val quiet = AudioRules.levelledDb(-45f)
        val loud = AudioRules.levelledDb(-3f)
        assertThat(quiet).isGreaterThan(-45f)
        assertThat(loud).isLessThan(0f)
        // The gap between quiet and loud shrinks: that is the levelling
        assertThat(loud - quiet).isLessThan(-3f - (-45f))
        assertThat(AudioRules.levelledDb(-30f)).isWithin(0.01f).of(-21f)
    }

    @Test fun `volume levelling preference round trips`() {
        val p = PlayerPrefs(volumeLevelling = true)
        assertThat(PlayerPrefs.decode(PlayerPrefs.encode(p)).volumeLevelling).isTrue()
        assertThat(PlayerPrefs().volumeLevelling).isFalse()
    }

    @Test fun `new server types are available and share types flagged`() {
        listOf(ServerType.SMB, ServerType.FTP, ServerType.SFTP, ServerType.NFS, ServerType.DLNA).forEach {
            assertThat(it.available).isTrue()
        }
        assertThat(ServerType.SMB.isFileShare).isTrue()
        assertThat(ServerType.DLNA.isFileShare).isFalse()
        val p = ServerProfile("x", ServerType.NFS, "n", "nfs://nas/v")
        assertThat(ServerProfiles.decode(ServerProfiles.encode(listOf(p))).single()).isEqualTo(p)
    }
}
