// End-to-end network tests against the local mock servers in tools/test-servers
// (start them with HOSTADDR=127.0.0.1; every test is skipped when they are not running).
// Never points at real servers.
#include "network/NetworkController.h"

#include <QEventLoop>
#include <QFile>
#include <QJsonArray>
#include <QJsonDocument>
#include <QJsonObject>
#include <QNetworkAccessManager>
#include <QNetworkReply>
#include <QSignalSpy>
#include <QTcpSocket>
#include <QTemporaryDir>
#include <QTest>

static bool reachable(int port)
{
    QTcpSocket s;
    s.connectToHost(QStringLiteral("127.0.0.1"), quint16(port));
    return s.waitForConnected(500);
}

static QByteArray httpGet(const QString &url, const QStringList &headers = {}, const QByteArray &range = {}, int *status = nullptr)
{
    QNetworkAccessManager nam;
    QNetworkRequest req{QUrl(url)};
    for (const auto &h : headers) req.setRawHeader(h.section(u':', 0, 0).trimmed().toUtf8(), h.section(u':', 1).trimmed().toUtf8());
    if (!range.isEmpty()) req.setRawHeader("Range", range);
    QNetworkReply *r = nam.get(req);
    QEventLoop loop;
    QObject::connect(r, &QNetworkReply::finished, &loop, &QEventLoop::quit);
    loop.exec();
    if (status) *status = r->attribute(QNetworkRequest::HttpStatusCodeAttribute).toInt();
    const QByteArray data = r->readAll();
    r->deleteLater();
    return data;
}

static QByteArray fileBytes(const QString &rel)
{
    QFile f(QStringLiteral(TEST_SERVERS_DIR) + u'/' + rel);
    f.open(QIODevice::ReadOnly);
    return f.readAll();
}

class NetworkIntegrationTest : public QObject
{
    Q_OBJECT
    QTemporaryDir m_dir;
    std::unique_ptr<NetworkController> m_net;

    QString save(const QVariantMap &form, bool expectOk = true, QString *message = nullptr)
    {
        QSignalSpy spy(m_net.get(), &NetworkController::serverSaved);
        m_net->saveServer(form);
        if (spy.isEmpty() && !spy.wait(20000)) return {};
        if (message) *message = spy.at(0).at(1).toString();
        if (spy.at(0).at(0).toBool() != expectOk) qWarning() << "save:" << spy.at(0).at(1).toString();
        return spy.at(0).at(0).toBool() ? spy.at(0).at(2).toString() : QString();
    }
    QVariantList browse(const QString &id, const QString &path, QString *error = nullptr)
    {
        QSignalSpy spy(m_net.get(), &NetworkController::browseDone);
        const int req = m_net->browse(id, path);
        for (int i = 0; i < 40; ++i) {
            for (const auto &args : spy) if (args.at(0).toInt() == req) {
                if (error) *error = args.at(2).toString();
                return args.at(1).toList();
            }
            spy.wait(500);
        }
        return {};
    }
    static QVariantMap find(const QVariantList &l, const QString &name)
    {
        for (const auto &v : l) if (v.toMap().value("name").toString() == name) return v.toMap();
        return {};
    }

private slots:
    void initTestCase()
    {
        if (!reachable(18096) || !reachable(4445)) QSKIP("mock servers not running (tools/test-servers)");
        m_net = std::make_unique<NetworkController>(m_dir.path(), QStringLiteral("test"));
    }

    void jellyfin()
    {
        QString msg;
        QVERIFY(save({{"type", "jellyfin"}, {"address", "http://127.0.0.1:18096/web/index.html"}, {"user", "test"}, {"password", "wrong"}}, false, &msg).isEmpty());
        QCOMPARE(msg, QString("Wrong user name or password"));
        const QString id = save({{"type", "jellyfin"}, {"address", "http://127.0.0.1:18096"}, {"user", "test"}, {"password", "secret"}});
        QVERIFY(!id.isEmpty());
        QCOMPARE(m_net->server(id).value("name").toString(), QString("Mock Jellyfin"));
        // The token is stored encrypted, never in servers.json
        QFile servers(m_dir.filePath("servers.json"));
        QVERIFY(servers.open(QIODevice::ReadOnly));
        QVERIFY(!servers.readAll().contains("tok-123"));

        const auto views = browse(id, "");
        QVERIFY(find(views, "Movies").value("isFolder").toBool());
        const auto movies = browse(id, "lib1");
        const auto m1 = find(movies, "Bravo Movie (2024)");
        QCOMPARE(m1.value("playKey").toString(), QString("jellyfin://%1/m1").arg(id));
        QVERIFY(m1.value("progress").toDouble() > 0.3);
        const QString key = m1.value("playKey").toString();
        QCOMPARE(m_net->resumePositionFor(key), 12000);
        QCOMPARE(m_net->titleFor(key), QString("Bravo Movie (2024)"));

        const ResolvedLocation r = m_net->resolve(key);
        QVERIFY(r.error.isEmpty());
        QVERIFY(r.url.startsWith("http://127.0.0.1:18096/Videos/m1/stream"));
        QVERIFY(!r.url.contains("tok-123"));                 // token only in the header
        QVERIFY(r.httpHeaders.value(0).contains("Token=\"tok-123\""));
        QCOMPARE(r.subtitles.size(), 1);
        int status = 0;
        QCOMPARE(httpGet(r.url, r.httpHeaders, {}, &status).size(), fileBytes("www/Bravo Bars.mp4").size());
        QCOMPARE(status, 200);
        QCOMPARE(httpGet(r.subtitles[0], r.httpHeaders), QByteArray("1\n00:00:00,500 --> 00:00:30,000\nJellyfin server subtitle\n"));

        QSignalSpy home(m_net.get(), &NetworkController::homeDone);
        m_net->jellyfinHome(id);
        QVERIFY(home.wait(10000));
        const auto sections = home.at(0).at(1).toList();
        QCOMPARE(sections.value(0).toMap().value("title").toString(), QString("Continue watching"));
        QCOMPARE(sections.value(1).toMap().value("title").toString(), QString("Recently added")); // empty Next up left out

        const auto found = browse(id, "search:second");
        QCOMPARE(found.size(), 1);

        // Playback reports
        const QByteArray before = httpGet("http://127.0.0.1:18096/debug/reports");
        const int n0 = int(QJsonDocument::fromJson(before).array().size());
        m_net->reportPlayback(key, 15000, false, "start");
        m_net->reportPlayback(key, 25000, true, "progress");
        m_net->reportPlayback(key, 30000, false, "stop");
        QJsonArray reports;
        for (int i = 0; i < 30 && int(reports.size()) - n0 < 3; ++i) {
            QTest::qWait(200);
            reports = QJsonDocument::fromJson(httpGet("http://127.0.0.1:18096/debug/reports")).array();
        }
        QCOMPARE(int(reports.size()) - n0, 3);
        QCOMPARE(reports.at(n0).toArray().at(0).toString(), QString("/Sessions/Playing"));
        QCOMPARE(reports.at(n0 + 2).toArray().at(0).toString(), QString("/Sessions/Playing/Stopped"));
        QCOMPARE(reports.at(n0 + 2).toArray().at(1).toObject().value("PositionTicks").toDouble(), 300000000.0);
        QCOMPARE(m_net->resumePositionFor(key), 30000);
    }

    void nextcloud()
    {
        QString msg;
        save({{"type", "nextcloud"}, {"address", "http://127.0.0.1:8766"}, {"user", "test"}, {"password", "nope"}}, false, &msg);
        QCOMPARE(msg, QString("Wrong user name or password"));
        const QString id = save({{"type", "nextcloud"}, {"address", "http://127.0.0.1:8766/index.php/apps/files/"}, {"user", "test"}, {"password", "secret"}});
        QVERIFY(!id.isEmpty());
        const auto top = browse(id, "");
        const auto movies = find(top, "Movies");
        QVERIFY(movies.value("isFolder").toBool());
        const auto files = browse(id, movies.value("id").toString());
        QVERIFY(find(files, "Series").value("isFolder").toBool());
        QVERIFY(find(files, "notes.txt").isEmpty());           // not a video
        QVERIFY(find(files, "Charlie Fractal.en.srt").isEmpty());
        const QString key = find(files, "Charlie Fractal.mp4").value("playKey").toString();
        QVERIFY(key.startsWith("cplay-net://"));
        QVERIFY(!key.contains("secret"));
        const ResolvedLocation r = m_net->resolve(key);
        QVERIFY(r.httpHeaders.value(0).startsWith("Authorization: Basic "));
        QCOMPARE(httpGet(r.url, r.httpHeaders), fileBytes("dav/Movies/Charlie Fractal.mp4"));
    }

    void dlna()
    {
        const QString id = save({{"type", "dlna"}, {"address", "http://127.0.0.1:8767/desc.xml"}});
        QVERIFY(!id.isEmpty());
        QCOMPARE(m_net->server(id).value("name").toString(), QString("Mock DLNA"));
        const auto top = browse(id, "");
        QCOMPARE(top.size(), 2);
        const auto videos = browse(id, find(top, "Videos").value("id").toString());
        QCOMPARE(videos.size(), 1);   // the photo is not offered
        const auto clip = find(videos, "Bravo over DLNA");
        QCOMPARE(clip.value("playKey").toString(), QString("http://127.0.0.1:8765/Bravo%20Bars.mp4"));
        QCOMPARE(clip.value("durationLabel").toString(), QString("0:35"));
        QCOMPARE(m_net->resolve(clip.value("playKey").toString()).subtitles.size(), 1);
        QString error;
        browse(id, "missing", &error);
        QVERIFY(error.contains("No such object"));
    }

    void smb()
    {
        QString msg;
        save({{"type", "smb"}, {"address", "\\\\127.0.0.1:4445\\VIDEOS"}, {"user", "test"}, {"password", "bad"}}, false, &msg);
        QCOMPARE(msg, QString("Wrong user name or password"));
        const QString id = save({{"type", "smb"}, {"address", "\\\\127.0.0.1:4445\\VIDEOS"}, {"user", "test"}, {"password", "secret"}});
        QVERIFY(!id.isEmpty());
        const auto top = browse(id, "");
        QVERIFY(find(top, "Movies").value("isFolder").toBool());
        const auto files = browse(id, "Movies");
        QVERIFY(find(files, "Kids").value("isFolder").toBool());
        QVERIFY(find(files, "notes.txt").isEmpty());
        const QString key = find(files, "Bravo Bars.mp4").value("playKey").toString();
        const ResolvedLocation r = m_net->resolve(key);
        QVERIFY(r.url.startsWith("http://127.0.0.1:"));
        QVERIFY(!r.url.contains("secret"));
        const QByteArray want = fileBytes("share/Movies/Bravo Bars.mp4");
        QCOMPARE(httpGet(r.url), want);
        int status = 0;
        QCOMPARE(httpGet(r.url, {}, "bytes=1000-1999", &status), want.mid(1000, 1000));
        QCOMPARE(status, 206);
        QCOMPARE(httpGet(r.url, {}, "bytes=-10"), want.right(10));
        // A share list when only the server is given
        const QString all = save({{"type", "smb"}, {"address", "smb://127.0.0.1:4445"}, {"user", "test"}, {"password", "secret"}});
        QVERIFY(!all.isEmpty());
        QVERIFY(!find(browse(all, ""), "VIDEOS").isEmpty());
        QVERIFY(!find(browse(all, "VIDEOS/Movies/Kids"), "Charlie Fractal.mp4").isEmpty());
    }

    void ftp()
    {
        QString msg;
        save({{"type", "ftp"}, {"address", "127.0.0.1:2121"}, {"user", "test"}, {"password", "bad"}}, false, &msg);
        QCOMPARE(msg, QString("Wrong user name or password"));
        const QString id = save({{"type", "ftp"}, {"address", "ftp://127.0.0.1:2121"}, {"user", "test"}, {"password", "secret"}});
        QVERIFY(!id.isEmpty());
        const auto files = browse(id, "Movies");
        QVERIFY(find(files, "Kids").value("isFolder").toBool());
        const QString key = find(files, "Bravo Bars.mp4").value("playKey").toString();
        const ResolvedLocation r = m_net->resolve(key);
        const QByteArray want = fileBytes("share/Movies/Bravo Bars.mp4");
        QCOMPARE(httpGet(r.url), want);
        QCOMPARE(httpGet(r.url, {}, "bytes=200000-200999"), want.mid(200000, 1000));
        QCOMPARE(httpGet(r.url, {}, "bytes=5-"), want.mid(5));
    }

    void tvPublishing()
    {
        // Local files and logged-in sources reach a TV through the LAN server (no secrets in the URL)
        QString mime;
        const QString local = m_net->publishForTv(QStringLiteral(TEST_SERVERS_DIR) + "/www/Bravo Bars.mp4", "127.0.0.1", &mime);
        QCOMPARE(mime, QString("video/mp4"));
        QVERIFY(local.startsWith("http://"));
        QCOMPARE(httpGet(local, {}, "bytes=0-99"), fileBytes("www/Bravo Bars.mp4").left(100));
        QString jfId;
        for (const auto &s : m_net->servers()) if (s.toMap().value("type") == "jellyfin") jfId = s.toMap().value("id").toString();
        const QString jf = m_net->publishForTv(QString("jellyfin://%1/m1").arg(jfId), "127.0.0.1", &mime);
        QVERIFY(!jf.contains("tok-123"));
        QCOMPARE(httpGet(jf, {}, "bytes=100-199"), fileBytes("www/Bravo Bars.mp4").mid(100, 100));
        int status = 0;
        httpGet(local.left(local.lastIndexOf('/') - 32) + "notarealtokennotarealtoken123456/x.mp4", {}, {}, &status);
        QCOMPARE(status, 404);
    }

    void removedServer()
    {
        const QString id = save({{"type", "url"}, {"address", "http://127.0.0.1:8765/Bravo%20Bars.mp4"}});
        QVERIFY(!id.isEmpty());
        QCOMPARE(browse(id, "").size(), 1);
        m_net->removeServer(id);
        QString error;
        browse(id, "", &error);
        QVERIFY(error.contains("removed"));
        QVERIFY(!m_net->resolve("cplay-net://gone/x.mkv").error.isEmpty());
    }
};

QTEST_GUILESS_MAIN(NetworkIntegrationTest)
#include "NetworkIntegrationTest.moc"
