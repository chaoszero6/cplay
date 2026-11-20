// Network rules, matching android/app/src/test/.../network/*RulesTest.kt
#include "core/NetRules.h"
#include <QTest>

using namespace NetRules;

// Raw-string fixtures live in NetRulesTestData.cpp (moc cannot read raw strings)
QByteArray testData(int i);

class NetRulesTest : public QObject
{
    Q_OBJECT
private slots:
    void smbAddresses()
    {
        auto a = parseSmb("\\\\nas\\Videos\\Kids");
        QCOMPARE(a.host, QString("nas"));
        QCOMPARE(a.port, 445);
        QCOMPARE(a.share, QString("Videos"));
        QCOMPARE(a.path, QString("Kids"));
        a = parseSmb("smb://192.168.1.5:4445/VIDEOS/");
        QCOMPARE(a.host, QString("192.168.1.5"));
        QCOMPARE(a.port, 4445);
        QCOMPARE(a.share, QString("VIDEOS"));
        QCOMPARE(a.path, QString());
        a = parseSmb("nas");
        QCOMPARE(a.share, QString());
        QVERIFY(!parseSmb("  ").valid());
    }
    void ftpAndNfsAddresses()
    {
        auto f = parseFtp("ftp://user:pw@host:2121/films/new");
        QCOMPARE(f.host, QString("host"));   // credentials never kept from the address
        QCOMPARE(f.port, 2121);
        QCOMPARE(f.path, QString("films/new"));
        QCOMPARE(parseFtp("host").port, 21);
        auto n = parseNfs("nfs://nas/export/videos/kids");
        QCOMPARE(n.host, QString("nas"));
        QCOMPARE(n.share, QString("/export"));
        QCOMPARE(n.path, QString("videos/kids"));
        n = parseNfs("nas:/srv/media/films", "/srv/media");
        QCOMPARE(n.share, QString("/srv/media"));
        QCOMPARE(n.path, QString("films"));
        QCOMPARE(joinPath("a/b/", "/c.mkv"), QString("a/b/c.mkv"));
        QCOMPARE(parentPath("a/b/c"), QString("a/b"));
        QCOMPARE(parentPath("a"), QString());
    }
    void jellyfinUrlsAndAuth()
    {
        QCOMPARE(jellyfinServerUrl("192.168.1.2:8096"), QString("http://192.168.1.2:8096"));
        QCOMPARE(jellyfinServerUrl("media.example.com"), QString("https://media.example.com"));
        QCOMPARE(jellyfinServerUrl("http://nas:8096/web/index.html#!/home.html"), QString("http://nas:8096"));
        QCOMPARE(jellyfinServerUrl("https://x.org/jelly/"), QString("https://x.org/jelly"));
        QVERIFY(jellyfinAuthHeader("dev1", "1.0", "tok").contains("Token=\"tok\""));
        QVERIFY(!jellyfinAuthHeader("dev1", "1.0").contains("Token"));
        QVERIFY(jellyfinAuthHeader("d\"x", "1").contains("DeviceId=\"d'x\""));
        const auto auth = parseJellyfinAuth(testData(0));
        QVERIFY(auth.has_value());
        QCOMPARE(auth->token, QString("abc"));
        QCOMPARE(auth->userId, QString("u1"));
        QCOMPARE(auth->serverId, QString("srv"));
        QVERIFY(!parseJellyfinAuth("{}").has_value());
        QVERIFY(!parseJellyfinAuth("not json").has_value());
        QCOMPARE(jellyfinError(401), QString("Wrong user name or password"));
        QVERIFY(jellyfinError(503).contains("503"));
    }
    void jellyfinKeysAndItems()
    {
        QCOMPARE(jellyfinItemKey("s", "i"), QString("jellyfin://s/i"));
        QCOMPARE(parseJellyfinKey("jellyfin://s/i")->second, QString("i"));
        QVERIFY(!parseJellyfinKey("jellyfin://s/").has_value());
        QVERIFY(!parseJellyfinKey("http://x").has_value());
        const QByteArray json = testData(1);
        const auto items = parseJellyfinItems(json, "http://h:8096", "srv");
        QCOMPARE(items.size(), 3);
        QCOMPARE(items[0].name, QString("S01E02 · Pilot"));
        QCOMPARE(items[0].playKey, QString("jellyfin://srv/ep1"));
        QCOMPARE(items[0].durationMs, 3600000);
        QCOMPARE(items[0].resumeMs, 600000);
        QCOMPARE(items[0].subtitle, QString("Show"));
        QVERIFY(items[0].imageUrl.startsWith("http://h:8096/Items/ep1/Images/Primary"));
        QCOMPARE(items[0].subtitleUrls, QStringList({"http://h:8096/Videos/ep1/ms1/Subtitles/3/Stream.srt"}));
        QVERIFY(items[1].isFolder);
        QVERIFY(items[1].playKey.isEmpty());
        QCOMPARE(items[1].kind, QString("movies"));
        QCOMPARE(items[2].name, QString("Film (2019)"));
        QCOMPARE(parseJellyfinItems(testData(2), "b", "s").size(), 1); // /Items/Latest
        QVERIFY(!jellyfinStreamUrl("http://h", "abc").contains("api_key"));  // token travels in a header
        QVERIFY(jellyfinPlaybackBody("i", 1000, true, "ps").contains("\"PositionTicks\":10000000"));
        QVERIFY(shouldReportProgress(0, 10000, false));
        QVERIFY(!shouldReportProgress(0, 9999, false));
        QVERIFY(shouldReportProgress(0, 1, true));
    }
    void webDav()
    {
        QCOMPARE(nextcloudDavRoot("cloud.example.com", "anna"), QString("https://cloud.example.com/remote.php/dav/files/anna/"));
        QCOMPARE(nextcloudDavRoot("https://c.org/index.php/apps/files/?dir=/", "a b"), QString("https://c.org/remote.php/dav/files/a%20b/"));
        QCOMPARE(nextcloudDavRoot("http://h:8766/remote.php/dav/files/test/Films", "x"), QString("http://h:8766/remote.php/dav/files/test/"));
        QCOMPARE(webDavRoot("http://h/dav"), QString("http://h/dav/"));
        QCOMPARE(basicAuth("test", "secret"), QString("Basic dGVzdDpzZWNyZXQ="));
        const QByteArray xml = testData(3);
        const auto items = parseMultistatus(xml, "http://h:8766/remote.php/dav/files/test/");
        QCOMPARE(items.size(), 3);
        QCOMPARE(items[0].name, QString("Shows"));
        QVERIFY(items[0].isFolder);
        QCOMPARE(items[1].name, QString("Ep 2.mkv"));
        QCOMPARE(items[1].sizeBytes, 10);
        QCOMPARE(items[2].name, QString("Ep 10.mkv"));
        QCOMPARE(items[2].sizeBytes, 2048);
        QCOMPARE(items[2].id, QString("http://h:8766/remote.php/dav/files/test/Ep%2010.mkv"));
    }
    void ssdpAndDescription()
    {
        const QByteArray reply = "HTTP/1.1 200 OK\r\nCACHE-CONTROL: max-age=1800\r\nLOCATION: http://10.0.0.5:8200/rootDesc.xml\r\nST: urn:schemas-upnp-org:device:MediaServer:1\r\n\r\n";
        QCOMPARE(ssdpLocation(reply, "MediaServer"), QString("http://10.0.0.5:8200/rootDesc.xml"));
        QVERIFY(ssdpLocation(reply, "MediaRenderer").isEmpty());
        QVERIFY(ssdpSearch("ssdp:all").startsWith("M-SEARCH * HTTP/1.1\r\n"));
        const QByteArray desc = testData(4);
        const auto tv = parseDeviceDescription(desc, "http://10.0.0.5:8200/rootDesc.xml", "AVTransport");
        QVERIFY(tv.has_value());
        QCOMPARE(tv->name, QString("Living room TV"));
        QCOMPARE(tv->controlUrl, QString("http://10.0.0.5:8200/av"));
        QCOMPARE(tv->renderingControlUrl, QString("http://10.0.0.5:8200/rc"));
        QCOMPARE(tv->iconUrl, QString("http://10.0.0.5:8200/icon.png"));
        const auto cd = parseDeviceDescription(desc, "http://10.0.0.5:8200/rootDesc.xml", "ContentDirectory");
        QCOMPARE(cd->name, QString("Inner"));
        QCOMPARE(cd->controlUrl, QString("http://10.0.0.5:8200/cd/control"));
        QVERIFY(!parseDeviceDescription(desc, "http://x/", "Nothing").has_value());
    }
    void didlBrowse()
    {
        const QString didl = testData(5);
        const QByteArray soap = "<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\"><s:Body><u:BrowseResponse xmlns:u=\"urn:schemas-upnp-org:service:ContentDirectory:1\"><Result>"
                                + escapeXml(didl).toUtf8() + "</Result><NumberReturned>3</NumberReturned><TotalMatches>3</TotalMatches></u:BrowseResponse></s:Body></s:Envelope>";
        const auto b = parseBrowseResponse(soap);
        QVERIFY(b.fault.isEmpty());
        QCOMPARE(b.total, 3);
        QCOMPARE(b.entries.size(), 2); // the photo is left out
        QCOMPARE(b.entries[0].name, QString("Films"));
        QCOMPARE(b.entries[0].subtitle, QString("4 items"));
        QCOMPARE(b.entries[1].name, QString("Clip & more"));
        QCOMPARE(b.entries[1].playKey, QString("http://h/clip.mp4"));
        QCOMPARE(b.entries[1].durationMs, 62500);
        QCOMPARE(b.entries[1].sizeBytes, 999);
        QCOMPARE(b.entries[1].subtitleUrls, QStringList({"http://h/clip.srt"}));
        QVERIFY(browseBody("0", 0, 200).contains("<ObjectID>0</ObjectID>"));
        const QByteArray fault = "<s:Envelope><s:Body><s:Fault><detail><UPnPError><errorCode>701</errorCode><errorDescription>No such object</errorDescription></UPnPError></detail></s:Fault></s:Body></s:Envelope>";
        QCOMPARE(parseBrowseResponse(fault).fault, QString("No such object"));
        QCOMPARE(parseUpnpDuration("1:02:03"), 3723000);
        QCOMPARE(parseUpnpDuration("junk"), 0);
        QCOMPARE(formatUpnpTime(3723999), QString("1:02:03"));
        QCOMPARE(escapeXml("<a & 'b'>"), QString("&lt;a &amp; &apos;b&apos;&gt;"));
        QVERIFY(soapEnvelope("urn:x", "Play", {{"InstanceID", "0"}}).contains("<u:Play xmlns:u=\"urn:x\"><InstanceID>0</InstanceID></u:Play>"));
    }
    void ranges()
    {
        auto r = parseRange("bytes=0-", 100);
        QCOMPARE(r->start, 0); QCOMPARE(r->end, 99);
        r = parseRange("bytes=10-19", 100);
        QCOMPARE(r->start, 10); QCOMPARE(r->end, 19);
        r = parseRange("bytes=-30", 100);
        QCOMPARE(r->start, 70); QCOMPARE(r->end, 99);
        r = parseRange("bytes=50-500", 100);
        QCOMPARE(r->end, 99);
        QVERIFY(!parseRange("bytes=100-", 100)->satisfiable);
        QVERIFY(!parseRange("bytes=20-10", 100)->satisfiable);
        QVERIFY(!parseRange("", 100).has_value());
        QVERIFY(!parseRange("items=1-2", 100).has_value());
        QCOMPARE(mimeFor("a.MKV"), QString("video/x-matroska"));
        QCOMPARE(mimeFor("noext"), QString("video/mp4"));
        QVERIFY(isValidToken("abcdefghijklmnop1234"));
        QVERIFY(!isValidToken("short"));
        QVERIFY(!isValidToken("abcdefghijklmnop/../x"));
    }
    void ftpReplies()
    {
        auto m = parseMlsdLine("type=file;size=1234;modify=20240101; Ep 1.mkv");
        QCOMPARE(m->name, QString("Ep 1.mkv"));
        QCOMPARE(m->size, 1234);
        QVERIFY(!m->isDir);
        QVERIFY(parseMlsdLine("type=dir;modify=1; Shows")->isDir);
        QVERIFY(!parseMlsdLine("type=cdir; .").has_value());
        auto u = parseListLine("-rw-r--r--   1 owner group   1048576 Jan 01 12:00 My Film.mkv");
        QCOMPARE(u->name, QString("My Film.mkv"));
        QCOMPARE(u->size, 1048576);
        QVERIFY(parseListLine("drwxr-xr-x   2 o g 4096 Mar  3  2023 Shows")->isDir);
        QCOMPARE(parseListLine("lrwxrwxrwx 1 o g 10 Jan 01 12:00 link -> target")->name, QString("link"));
        auto d = parseListLine("01-02-24  10:30AM       <DIR>          Films");
        QVERIFY(d->isDir);
        QCOMPARE(d->name, QString("Films"));
        QCOMPARE(parseListLine("01-02-24  10:30AM  12345 a b.mp4")->size, 12345);
        QVERIFY(!parseListLine("total 12").has_value());
        QCOMPARE(parsePasvPort("227 Entering Passive Mode (127,0,0,1,117,49)."), 117 * 256 + 49);
        QCOMPARE(parseEpsvPort("229 Entering Extended Passive Mode (|||30001|)"), 30001);
        QCOMPARE(parsePasvPort("500 no"), -1);
        QVERIFY(isPlayableName("x.mkv"));
        QVERIFY(!isPlayableName("x.srt"));
        QVERIFY(!isPlayableName("x.txt"));
    }
};

QTEST_APPLESS_MAIN(NetRulesTest)
#include "NetRulesTest.moc"
