package com.chaoswares.chaosplay.network

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class NetworkRulesTest {

    // ---- stream URLs ----

    @Test fun `validate adds https and rejects bad input`() {
        assertThat(StreamRules.validate(" nas.local:8080/a.mp4 "))
            .isEqualTo(StreamRules.Validation.Valid("https://nas.local:8080/a.mp4"))
        assertThat(StreamRules.validate("rtsp://cam/stream")).isInstanceOf(StreamRules.Validation.Valid::class.java)
        assertThat(StreamRules.validate("")).isInstanceOf(StreamRules.Validation.Invalid::class.java)
        assertThat(StreamRules.validate("http://a b")).isInstanceOf(StreamRules.Validation.Invalid::class.java)
        val ftp = StreamRules.validate("ftp://host/x") as StreamRules.Validation.Invalid
        assertThat(ftp.reason).contains("FTP")
        assertThat(StreamRules.validate("http:///nohost")).isInstanceOf(StreamRules.Validation.Invalid::class.java)
    }

    @Test fun `stream kind from url`() {
        assertThat(StreamRules.kindOf("https://x/live/index.m3u8?token=1")).isEqualTo(StreamKind.HLS)
        assertThat(StreamRules.kindOf("https://x/manifest.mpd")).isEqualTo(StreamKind.DASH)
        assertThat(StreamRules.kindOf("RTSP://cam/1")).isEqualTo(StreamKind.RTSP)
        assertThat(StreamRules.kindOf("https://x/movie.mkv")).isEqualTo(StreamKind.PROGRESSIVE)
        assertThat(StreamRules.kindOf("https://x/play?format=m3u8")).isEqualTo(StreamKind.HLS)
    }

    @Test fun `network url detection`() {
        assertThat(StreamRules.isNetworkUrl("https://x")).isTrue()
        assertThat(StreamRules.isNetworkUrl("content://media/1")).isFalse()
        assertThat(StreamRules.isNetworkUrl("/sdcard/a.mp4")).isFalse()
    }

    @Test fun `plain http warns only for public hosts`() {
        assertThat(StreamRules.isInsecurePublic("http://example.com/a.mp4")).isTrue()
        assertThat(StreamRules.isInsecurePublic("http://8.8.8.8/a")).isTrue()
        assertThat(StreamRules.isInsecurePublic("https://example.com/a.mp4")).isFalse()
        for (host in listOf("192.168.1.5", "10.0.0.2", "172.20.1.1", "127.0.0.1", "nas.local", "nas", "100.100.1.1", "169.254.3.3")) {
            assertThat(StreamRules.isInsecurePublic("http://$host:8096/x")).isFalse()
        }
        assertThat(StreamRules.isPrivateHost("172.32.0.1")).isFalse()
        assertThat(StreamRules.isPrivateHost("[::1]")).isTrue()
        assertThat(StreamRules.isPrivateHost("fd12::1")).isTrue()
    }

    @Test fun `display name and redaction`() {
        assertThat(StreamRules.displayName("https://h/Movies/My%20Film.mkv")).isEqualTo("My Film.mkv")
        assertThat(StreamRules.displayName("https://h/")).isEqualTo("h")
        assertThat(StreamRules.redact("https://bob:secret@h/x")).isEqualTo("https://h/x")
        assertThat(StreamRules.redact("https://h/x")).isEqualTo("https://h/x")
    }

    @Test fun `saved streams dedupe by url and round trip`() {
        var list = StreamRules.add(emptyList(), SavedStream("1", "A", "https://h/a"))
        list = StreamRules.add(list, SavedStream("2", "A again", "https://h/a"))
        assertThat(list.map { it.id }).containsExactly("2")
        list = StreamRules.add(list, SavedStream("3", "", "https://h/b.mp4", 9))
        val back = StreamRules.decode(StreamRules.encode(list))
        assertThat(back.last().name).isEqualTo("b.mp4")
        assertThat(StreamRules.remove(back, "2").map { it.id }).containsExactly("3")
    }

    // ---- profiles and base64 ----

    @Test fun `server profiles round trip and skip unknown types`() {
        val list = listOf(
            ServerProfile("a", ServerType.JELLYFIN, "Home", "http://nas:8096", "bob", "uid", 5, false),
            ServerProfile("b", ServerType.NEXTCLOUD, "", "https://c/remote.php/dav/files/bob/", "bob", trustAllCerts = true)
        )
        val back = ServerProfiles.decode(ServerProfiles.encode(list))
        assertThat(back[0]).isEqualTo(list[0])
        assertThat(back[1].name).isEqualTo("Nextcloud")
        assertThat(back[1].trustAllCerts).isTrue()
        assertThat(ServerProfiles.decode("S1\tx\tGOPHER\tn\tu")).isEmpty()
        assertThat(ServerProfiles.remove(ServerProfiles.add(list, list[0].copy(name = "New")), "b").single().name).isEqualTo("New")
    }

    @Test fun `base64 matches rfc vectors`() {
        val vectors = mapOf("" to "", "f" to "Zg==", "fo" to "Zm8=", "foo" to "Zm9v", "foob" to "Zm9vYg==", "fooba" to "Zm9vYmE=", "foobar" to "Zm9vYmFy")
        vectors.forEach { (plain, b64) -> assertThat(Base64Lite.encode(plain.toByteArray())).isEqualTo(b64) }
        assertThat(Base64Lite.basicAuth("Aladdin", "open sesame")).isEqualTo("Basic QWxhZGRpbjpvcGVuIHNlc2FtZQ==")
    }

    // ---- WebDAV / Nextcloud ----

    @Test fun `nextcloud dav root from what people paste`() {
        assertThat(WebDavRules.nextcloudDavRoot("cloud.example.com", "bob"))
            .isEqualTo("https://cloud.example.com/remote.php/dav/files/bob/")
        assertThat(WebDavRules.nextcloudDavRoot("https://h/nextcloud/index.php/apps/files/?dir=/", "Mary Ann"))
            .isEqualTo("https://h/nextcloud/remote.php/dav/files/Mary%20Ann/")
        assertThat(WebDavRules.nextcloudDavRoot("https://h/remote.php/dav/files/bob/Movies", "x"))
            .isEqualTo("https://h/remote.php/dav/files/bob/")
        assertThat(WebDavRules.nextcloudDavRoot("https://h/remote.php/webdav", "x"))
            .isEqualTo("https://h/remote.php/webdav/")
        assertThat(WebDavRules.nextcloudDavRoot("http://192.168.1.4:8080/", "u@x.com"))
            .isEqualTo("http://192.168.1.4:8080/remote.php/dav/files/u%40x.com/")
    }

    @Test fun `generic webdav root and child urls`() {
        assertThat(WebDavRules.webDavRoot("nas.local/dav")).isEqualTo("https://nas.local/dav/")
        assertThat(WebDavRules.childUrl("https://h/dav", "My Movies", true)).isEqualTo("https://h/dav/My%20Movies/")
        assertThat(WebDavRules.childUrl("https://h/dav/", "a+b#1.mkv", false)).isEqualTo("https://h/dav/a%2Bb%231.mkv")
    }

    @Test fun `parent never climbs above root and breadcrumbs decode`() {
        val root = "https://h/remote.php/dav/files/bob/"
        assertThat(WebDavRules.parentUrl(root + "Movies/2024/", root)).isEqualTo(root + "Movies/")
        assertThat(WebDavRules.parentUrl(root, root)).isNull()
        assertThat(WebDavRules.breadcrumbs(root + "My%20Movies/2024/", root).map { it.first })
            .containsExactly("Home", "My Movies", "2024").inOrder()
        assertThat(WebDavRules.breadcrumbs(root, root)).hasSize(1)
    }

    private val nextcloudXml = """<?xml version="1.0"?>
<d:multistatus xmlns:d="DAV:" xmlns:s="http://sabredav.org/ns" xmlns:oc="http://owncloud.org/ns" xmlns:nc="http://nextcloud.org/ns">
 <d:response><d:href>/remote.php/dav/files/bob/Movies/</d:href>
  <d:propstat><d:prop><d:resourcetype><d:collection/></d:resourcetype><oc:size>1048576</oc:size></d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat>
  <d:propstat><d:prop><d:getcontentlength/><d:getcontenttype/></d:prop><d:status>HTTP/1.1 404 Not Found</d:status></d:propstat>
 </d:response>
 <d:response><d:href>/remote.php/dav/files/bob/Movies/Big%20Buck%20Bunny.mp4</d:href>
  <d:propstat><d:prop><d:resourcetype/><d:getcontentlength>734003</d:getcontentlength><d:getcontenttype>video/mp4</d:getcontenttype><d:getlastmodified>Tue, 01 Oct 2024 10:00:00 GMT</d:getlastmodified></d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat>
 </d:response>
 <d:response><d:href>/remote.php/dav/files/bob/Movies/Big%20Buck%20Bunny.en.srt</d:href>
  <d:propstat><d:prop><d:resourcetype/><d:getcontentlength>2048</d:getcontentlength><d:getcontenttype>application/x-subrip</d:getcontenttype></d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat>
 </d:response>
 <d:response><d:href>/remote.php/dav/files/bob/Movies/Series/</d:href>
  <d:propstat><d:prop><d:resourcetype><d:collection/></d:resourcetype><oc:size>99</oc:size></d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat>
 </d:response>
 <d:response><d:href>/remote.php/dav/files/bob/Movies/.hidden</d:href>
  <d:propstat><d:prop><d:resourcetype/></d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat>
 </d:response>
 <d:response><d:href>/remote.php/dav/files/bob/Movies/notes.txt</d:href>
  <d:propstat><d:prop><d:resourcetype/><d:getcontentlength>5</d:getcontentlength><d:getcontenttype>text/plain</d:getcontenttype></d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat>
 </d:response>
</d:multistatus>"""

    @Test fun `parses a nextcloud multistatus`() {
        val items = WebDavRules.parseMultistatus(nextcloudXml, "https://cloud.example.com/remote.php/dav/files/bob/Movies/")
        assertThat(items.map { it.name }).containsExactly("Series", "Big Buck Bunny.en.srt", "Big Buck Bunny.mp4", "notes.txt").inOrder()
        val series = items[0]
        assertThat(series.isFolder).isTrue()
        assertThat(series.sizeBytes).isEqualTo(99)
        assertThat(series.url).isEqualTo("https://cloud.example.com/remote.php/dav/files/bob/Movies/Series/")
        val movie = items.first { it.name.endsWith(".mp4") }
        assertThat(movie.sizeBytes).isEqualTo(734003)
        assertThat(movie.contentType).isEqualTo("video/mp4")
        assertThat(movie.url).isEqualTo("https://cloud.example.com/remote.php/dav/files/bob/Movies/Big%20Buck%20Bunny.mp4")
    }

    @Test fun `visible filter and sidecar subtitles`() {
        val items = WebDavRules.parseMultistatus(nextcloudXml, "https://c/remote.php/dav/files/bob/Movies")
        assertThat(WebDavRules.visible(items, false).map { it.name }).containsExactly("Series", "Big Buck Bunny.mp4").inOrder()
        assertThat(WebDavRules.visible(items, true)).hasSize(4)
        val movie = items.first { it.name.endsWith(".mp4") }
        val entry = WebDavRules.toRemoteEntry(movie, items)
        assertThat(entry.kind).isEqualTo("video")
        assertThat(entry.isPlayable).isTrue()
        assertThat(entry.subtitleUris).containsExactly("https://c/remote.php/dav/files/bob/Movies/Big%20Buck%20Bunny.en.srt")
        assertThat(WebDavRules.toRemoteEntry(items[0], items).isPlayable).isFalse()
    }

    @Test fun `absolute hrefs and ports are kept`() {
        val xml = """<d:multistatus xmlns:d="DAV:"><d:response><d:href>http://nas:8080/dav/clip.mkv</d:href>
            <d:propstat><d:prop><d:displayname>Nice name.mkv</d:displayname></d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response></d:multistatus>"""
        val item = WebDavRules.parseMultistatus(xml, "http://nas:8080/dav/").single()
        assertThat(item.url).isEqualTo("http://nas:8080/dav/clip.mkv")
        assertThat(item.name).isEqualTo("Nice name.mkv")
        assertThat(item.isFolder).isFalse()
    }

    @Test fun `doctype is refused so entities cannot leak files`() {
        val evil = """<?xml version="1.0"?><!DOCTYPE x [<!ENTITY e SYSTEM "file:///etc/passwd">]><d:multistatus xmlns:d="DAV:"/>"""
        val result = runCatching { WebDavRules.parseMultistatus(evil, "https://h/") }
        assertThat(result.isFailure || result.getOrNull().isNullOrEmpty()).isTrue()
    }

    @Test fun `webdav friendly errors`() {
        assertThat(WebDavRules.errorFor(401)).contains("app password")
        assertThat(WebDavRules.errorFor(404)).contains("not found")
        assertThat(WebDavRules.errorFor(503)).contains("503")
    }

    // ---- Jellyfin ----

    @Test fun `normalise jellyfin addresses`() {
        assertThat(JellyfinRules.normaliseServerUrl("192.168.1.10:8096")).isEqualTo("http://192.168.1.10:8096")
        assertThat(JellyfinRules.normaliseServerUrl("jf.example.com/")).isEqualTo("https://jf.example.com")
        assertThat(JellyfinRules.normaliseServerUrl("https://jf.example.com/web/index.html#!/home.html"))
            .isEqualTo("https://jf.example.com")
        assertThat(JellyfinRules.normaliseServerUrl("https://h/jellyfin/web/")).isEqualTo("https://h/jellyfin")
        assertThat(JellyfinRules.normaliseServerUrl("  ")).isEqualTo("")
    }

    @Test fun `auth header carries client info and token`() {
        val h = JellyfinRules.authHeader("dev\"1", "1.2", token = "tok")
        assertThat(h).startsWith("MediaBrowser Client=\"CPlay\"")
        assertThat(h).contains("DeviceId=\"dev'1\"")
        assertThat(h).endsWith("Token=\"tok\"")
        assertThat(JellyfinRules.authHeader("d", "1")).doesNotContain("Token")
    }

    @Test fun `parse auth response and public info`() {
        val json = """{"User":{"Name":"bob","ServerId":"srv","Id":"u1"},"AccessToken":"abc","ServerId":"srv"}"""
        assertThat(JellyfinRules.parseAuth(json)).isEqualTo(JellyfinRules.AuthResult("abc", "u1", "bob", "srv"))
        assertThat(JellyfinRules.parseAuth("""{"User":{}}""")).isNull()
        assertThat(JellyfinRules.parseAuth("not json")).isNull()
        val info = JellyfinRules.parsePublicInfo("""{"ServerName":"Den","Version":"10.9.11","Id":"x","ProductName":"Jellyfin Server"}""")
        assertThat(info).isEqualTo(JellyfinRules.ServerInfo("Den", "10.9.11", "x"))
        assertThat(JellyfinRules.parsePublicInfo("""{"foo":1}""")).isNull()
        assertThat(JellyfinRules.authBody("a", "p\"w")).isEqualTo("""{"Username":"a","Pw":"p\"w"}""")
    }

    private val itemsJson = """{"Items":[
      {"Name":"Movies","Id":"lib1","Type":"CollectionFolder","IsFolder":true,"CollectionType":"movies","ImageTags":{"Primary":"t1"}},
      {"Name":"Pilot","Id":"ep1","Type":"Episode","IsFolder":false,"SeriesName":"Show","ParentIndexNumber":1,"IndexNumber":2,
       "RunTimeTicks":27000000000,"UserData":{"PlaybackPositionTicks":6000000000,"Played":false},
       "MediaSources":[{"Id":"ms1","Container":"mkv","MediaStreams":[
          {"Type":"Video","Index":0},
          {"Type":"Subtitle","Index":3,"IsExternal":true,"IsTextSubtitleStream":true,"DisplayTitle":"English - SRT","Language":"eng"},
          {"Type":"Subtitle","Index":4,"IsExternal":false,"IsTextSubtitleStream":true},
          {"Type":"Subtitle","Index":5,"IsExternal":true,"IsTextSubtitleStream":false}]}]},
      {"Name":"Film","Id":"m1","Type":"Movie","ProductionYear":2019,"RunTimeTicks":0,"UserData":{"Played":true}}
    ],"TotalRecordCount":3}"""

    @Test fun `parse items with user data, episodes and external subtitles`() {
        val items = JellyfinRules.parseItems(itemsJson)
        assertThat(items).hasSize(3)
        val lib = items[0]
        assertThat(lib.isPlayable).isFalse()
        assertThat(lib.collectionType).isEqualTo("movies")
        val ep = items[1]
        assertThat(ep.isPlayable).isTrue()
        assertThat(ep.displayTitle).isEqualTo("S01E02 · Pilot")
        assertThat(ep.runTimeMs).isEqualTo(2_700_000L)
        assertThat(ep.positionMs).isEqualTo(600_000L)
        assertThat(ep.mediaSourceId).isEqualTo("ms1")
        assertThat(ep.externalSubtitles).containsExactly(3 to "English - SRT")
        val film = items[2]
        assertThat(film.displayTitle).isEqualTo("Film (2019)")
        assertThat(film.played).isTrue()
        assertThat(film.primaryImageTag).isNull()
    }

    @Test fun `latest endpoint returns a bare array and junk gives nothing`() {
        assertThat(JellyfinRules.parseItems("""[{"Id":"a","Name":"x","Type":"Movie"},{"Name":"no id"}]""")).hasSize(1)
        assertThat(JellyfinRules.parseItems("{}")).isEmpty()
        assertThat(JellyfinRules.parseItems("<html>")).isEmpty()
    }

    @Test fun `remote entries use stable keys, never tokens`() {
        val ep = JellyfinRules.parseItems(itemsJson)[1]
        val entry = JellyfinRules.toRemoteEntry(ep, "https://jf", "srv1", "TOKEN")
        assertThat(entry.playUri).isEqualTo("jellyfin://srv1/ep1")
        assertThat(entry.playUri).doesNotContain("TOKEN")
        assertThat(entry.resumeMs).isEqualTo(600_000L)
        assertThat(entry.subtitle).isEqualTo("Show")
        assertThat(entry.subtitleUris.single()).isEqualTo("https://jf/Videos/ep1/ms1/Subtitles/3/Stream.srt?api_key=TOKEN")
        val lib = JellyfinRules.toRemoteEntry(JellyfinRules.parseItems(itemsJson)[0], "https://jf", "srv1", "T")
        assertThat(lib.isFolder).isTrue()
        assertThat(lib.imageUrl).isEqualTo("https://jf/Items/lib1/Images/Primary?maxWidth=400&tag=t1&quality=85")
    }

    @Test fun `item keys parse back`() {
        assertThat(JellyfinRules.parseItemKey("jellyfin://srv/abc")).isEqualTo("srv" to "abc")
        assertThat(JellyfinRules.parseItemKey("jellyfin://srv/")).isNull()
        assertThat(JellyfinRules.parseItemKey("https://x")).isNull()
    }

    @Test fun `stream and transcode urls`() {
        assertThat(JellyfinRules.directStreamUrl("https://jf", "i 1", "t&k"))
            .isEqualTo("https://jf/Videos/i+1/stream?static=true&MediaSourceId=i+1&api_key=t%26k")
        val hls = JellyfinRules.transcodeUrl("https://jf", "i", "t", "ps", "ms")
        assertThat(hls).startsWith("https://jf/Videos/i/master.m3u8?MediaSourceId=ms&api_key=t&PlaySessionId=ps")
        assertThat(JellyfinRules.itemsUrl("https://jf", "u", "p")).contains("/Users/u/Items?ParentId=p")
        assertThat(JellyfinRules.resumeUrl("https://jf", "u")).contains("/Users/u/Items/Resume")
        assertThat(JellyfinRules.searchUrl("https://jf", "u", "star wars")).contains("SearchTerm=star+wars")
        assertThat(JellyfinRules.imageUrl("https://jf", "i", null)).isNull()
    }

    @Test fun `playback report body and throttle`() {
        val body = org.json.JSONObject(JellyfinRules.playbackReportBody("i", 1_500, true, "ps", null))
        assertThat(body.getLong("PositionTicks")).isEqualTo(15_000_000L)
        assertThat(body.getString("MediaSourceId")).isEqualTo("i")
        assertThat(body.getBoolean("IsPaused")).isTrue()
        assertThat(JellyfinRules.shouldReportProgress(0, 9_999, false)).isFalse()
        assertThat(JellyfinRules.shouldReportProgress(0, 10_000, false)).isTrue()
        assertThat(JellyfinRules.shouldReportProgress(0, 1, true)).isTrue()
        assertThat(JellyfinRules.ticksToMs(JellyfinRules.msToTicks(1234))).isEqualTo(1234)
    }

    @Test fun `discovery and quick connect parsing`() {
        assertThat(JellyfinRules.parseDiscovery("""{"Address":"http://192.168.1.9:8096/","Id":"x","Name":"Den"}"""))
            .isEqualTo(JellyfinRules.Discovered("http://192.168.1.9:8096", "x", "Den"))
        assertThat(JellyfinRules.parseDiscovery("""{"Name":"x"}""")).isNull()
        assertThat(JellyfinRules.parseQuickConnect("""{"Secret":"s","Code":"123456","Authenticated":false}"""))
            .isEqualTo(JellyfinRules.QuickConnect("s", "123456", false))
        assertThat(JellyfinRules.parseQuickConnect("{}")).isNull()
    }

    @Test fun `jellyfin friendly errors`() {
        assertThat(JellyfinRules.errorFor(401)).isEqualTo("Wrong user name or password")
        assertThat(JellyfinRules.errorFor(404)).contains("Not a Jellyfin")
    }
}
