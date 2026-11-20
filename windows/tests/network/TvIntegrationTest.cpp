// Play on TV end to end against the mock DLNA TV (mocktv.py) and the mock
// Chromecast (mockcast.py) in tools/test-servers. Skipped when they are not running.
// Never sends anything to a real TV: devices are added by their test address only.
#include "network/NetworkController.h"
#include "tv/TvController.h"

#include <QEventLoop>
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

static QJsonObject state(int port, bool reset = false)
{
    QNetworkAccessManager nam;
    QNetworkReply *r = nam.get(QNetworkRequest(QUrl(QStringLiteral("http://127.0.0.1:%1/debug/%2").arg(port).arg(reset ? "reset" : "state"))));
    QEventLoop loop;
    QObject::connect(r, &QNetworkReply::finished, &loop, &QEventLoop::quit);
    loop.exec();
    const QJsonObject o = QJsonDocument::fromJson(r->readAll()).object();
    r->deleteLater();
    return o;
}

static bool waitFor(const std::function<bool()> &cond, int ms = 8000)
{
    for (int i = 0; i < ms / 100; ++i) {
        if (cond()) return true;
        QTest::qWait(100);
    }
    return cond();
}

class TvIntegrationTest : public QObject
{
    Q_OBJECT
    QTemporaryDir m_dir;
    std::unique_ptr<NetworkController> m_net;
    std::unique_ptr<TvController> m_tv;
    const QString m_clip = QStringLiteral(TEST_SERVERS_DIR) + "/www/Bravo Bars.mp4";

    QString add(const QString &address)
    {
        QSignalSpy spy(m_tv.get(), &TvController::deviceAdded);
        m_tv->addDevice(address);
        if (spy.isEmpty() && !spy.wait(8000)) return {};
        if (!spy.at(0).at(0).toBool()) return {};
        for (const auto &d : m_tv->devices())
            if (d.toMap().value("name").toString() == spy.at(0).at(1).toString()) return d.toMap().value("id").toString();
        return {};
    }

private slots:
    void initTestCase()
    {
        m_net = std::make_unique<NetworkController>(m_dir.path(), QStringLiteral("test"));
        m_tv = std::make_unique<TvController>(m_net.get());
    }

    void dlnaTv()
    {
        if (!reachable(8768)) QSKIP("mock TV not running (tools/test-servers/mocktv.py)");
        state(8768, true);
        QVERIFY(add("http://127.0.0.1:9/nothing.xml").isEmpty());
        const QString id = add("http://127.0.0.1:8768/desc.xml");
        QVERIFY(!id.isEmpty());
        QCOMPARE(m_tv->devices().value(0).toMap().value("kind").toString(), QString("dlna"));

        m_tv->cast(id, m_clip, "Bravo Bars", 5000, 35000);
        QVERIFY(m_tv->casting());
        QVERIFY(waitFor([&] { return m_tv->state() == "playing"; }));
        const QJsonObject s = state(8768);
        const QString uri = s.value("uri").toString();
        QVERIFY(uri.startsWith("http://"));
        QVERIFY(uri.endsWith("/Bravo%20Bars.mp4"));
        QVERIFY(waitFor([&] { return state(8768).value("fetches").toArray().size() >= 2; }));
        for (const auto &f : state(8768).value("fetches").toArray()) {
            QCOMPARE(f.toObject().value("status").toInt(), 206);      // seekable for the TV
            QCOMPARE(f.toObject().value("type").toString(), QString("video/mp4"));
        }
        // Resume point: CPlay seeks once the TV plays
        QVERIFY(waitFor([&] { return state(8768).value("actions").toVariant().toStringList().contains("Seek"); }));
        QVERIFY(waitFor([&] { return m_tv->position() >= 5000; }));

        m_tv->pause();
        QVERIFY(waitFor([&] { return m_tv->state() == "paused"; }));
        m_tv->seek(20000);
        QVERIFY(waitFor([&] { return state(8768).value("position").toDouble() >= 20; }));
        m_tv->setVolume(30);
        QVERIFY(waitFor([&] { return state(8768).value("actions").toVariant().toStringList().contains("SetVolume"); }));

        QSignalSpy done(m_tv.get(), &TvController::finished);
        m_tv->stop();
        QCOMPARE(done.size(), 1);
        QVERIFY(done.at(0).at(0).toLongLong() >= 19000);   // CPlay continues locally from here
        QVERIFY(!m_tv->casting());
        QVERIFY(waitFor([&] { return state(8768).value("transport").toString() == "STOPPED"; }));
    }

    void chromecast()
    {
        if (!reachable(18009)) QSKIP("mock Chromecast not running (tools/test-servers/mockcast.py)");
        state(18010, true);
        const QString id = add("127.0.0.1:18009");
        QVERIFY(!id.isEmpty());
        m_tv->cast(id, m_clip, "Bravo Bars", 3000, 35000);
        QVERIFY(waitFor([&] { return m_tv->state() == "playing"; }));
        const QJsonObject s = state(18010);
        const QStringList actions = s.value("actions").toVariant().toStringList();
        QVERIFY(actions.contains("connection:CONNECT"));
        QVERIFY(actions.contains("receiver:LAUNCH"));
        QVERIFY(actions.contains("media:LOAD"));
        const QJsonObject media = s.value("media").toObject();
        QVERIFY(media.value("contentId").toString().endsWith("/Bravo%20Bars.mp4"));
        QCOMPARE(media.value("contentType").toString(), QString("video/mp4"));
        QCOMPARE(media.value("metadata").toObject().value("title").toString(), QString("Bravo Bars"));
        QVERIFY(waitFor([&] { return state(18010).value("fetches").toArray().size() >= 2; }));
        for (const auto &f : state(18010).value("fetches").toArray()) QCOMPARE(f.toObject().value("status").toInt(), 206);
        QVERIFY(waitFor([&] { return m_tv->position() >= 3000; }));

        m_tv->pause();
        QVERIFY(waitFor([&] { return m_tv->state() == "paused"; }));
        m_tv->seek(15000);
        QVERIFY(waitFor([&] { return state(18010).value("position").toDouble() >= 15; }));
        m_tv->setVolume(70);
        QVERIFY(waitFor([&] { return qAbs(state(18010).value("volume").toDouble() - 0.7) < 0.01; }));
        m_tv->play();
        QVERIFY(waitFor([&] { return m_tv->state() == "playing"; }));
        QSignalSpy done(m_tv.get(), &TvController::finished);
        m_tv->stop();
        QCOMPARE(done.size(), 1);
        QVERIFY(waitFor([&] { return state(18010).value("player").toString() == "IDLE"; }));
    }

    void unknownDevice()
    {
        m_tv->cast("nope", m_clip, "x", 0, 0);
        QVERIFY(!m_tv->casting());
        QCOMPARE(m_tv->state(), QString("error"));
    }
};

QTEST_GUILESS_MAIN(TvIntegrationTest)
#include "TvIntegrationTest.moc"
