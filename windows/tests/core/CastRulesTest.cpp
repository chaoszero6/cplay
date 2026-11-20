// Chromecast framing and mDNS parsing (Play on TV)
#include "core/CastRules.h"
#include <QTest>
#include <QtEndian>

using namespace CastRules;

static void name(QByteArray &p, const QStringList &labels, bool end = true)
{
    for (const auto &l : labels) { p.append(char(l.size())); p.append(l.toUtf8()); }
    if (end) p.append('\0');
}
static void u16(QByteArray &p, quint16 v) { char b[2]; qToBigEndian(v, b); p.append(b, 2); }
static void u32(QByteArray &p, quint32 v) { char b[4]; qToBigEndian(v, b); p.append(b, 4); }

class CastRulesTest : public QObject
{
    Q_OBJECT
private slots:
    void frameRoundTrip()
    {
        CastMessage m{"sender-0", "receiver-0", NsReceiver, "{\"type\":\"LAUNCH\",\"appId\":\"CC1AD845\",\"requestId\":1}"};
        QByteArray buf = encodeFrame(m);
        QCOMPARE(qFromBigEndian<quint32>(buf.constData()), quint32(buf.size() - 4));
        // Two frames and half of a third arrive together
        QByteArray stream = buf + encodeFrame({"receiver-0", "sender-0", NsHeartbeat, "{\"type\":\"PING\"}"}) + buf.left(7);
        const auto frames = takeFrames(stream);
        QCOMPARE(frames.size(), 2);
        QCOMPARE(frames[0].sourceId, QString("sender-0"));
        QCOMPARE(frames[0].destinationId, QString("receiver-0"));
        QCOMPARE(frames[0].ns, QString(NsReceiver));
        QCOMPARE(frames[0].payload, m.payload);
        QCOMPARE(frames[1].payload, QString("{\"type\":\"PING\"}"));
        QCOMPARE(stream.size(), 7);   // the partial frame waits for more bytes
        stream += buf.mid(7);
        QCOMPARE(takeFrames(stream).size(), 1);
        QVERIFY(stream.isEmpty());
    }
    void knownBytes()
    {
        // protocol_version=0, source "a", destination "b", namespace "n", payload_type=0, payload "p"
        const QByteArray body = QByteArray::fromHex("08001201611a016222016e2800320170");
        const auto m = decodeMessage(body);
        QVERIFY(m.has_value());
        QCOMPARE(m->sourceId, QString("a"));
        QCOMPARE(m->destinationId, QString("b"));
        QCOMPARE(m->ns, QString("n"));
        QCOMPARE(m->payload, QString("p"));
        QCOMPARE(encodeFrame(*m).mid(4), body);
        QVERIFY(!decodeMessage(QByteArray::fromHex("12ff")).has_value());  // truncated
        QByteArray huge(4, '\xff');
        QVERIFY(takeFrames(huge).isEmpty());
        QVERIFY(huge.isEmpty());
    }
    void mdns()
    {
        const QByteArray q = mdnsQuery();
        QCOMPARE(q.mid(4, 2), QByteArray("\x00\x01", 2));
        QVERIFY(q.contains("\x0b_googlecast\x04_tcp\x05local"));
        QVERIFY(q.endsWith(QByteArray("\x00\x0c\x00\x01", 4)));

        // Response: PTR + SRV + TXT + A, with a compression pointer to the service name
        QByteArray p;
        u16(p, 0); u16(p, 0x8400); u16(p, 0); u16(p, 1); u16(p, 0); u16(p, 3);
        const int serviceAt = p.size();
        name(p, {"_googlecast", "_tcp", "local"});
        u16(p, 12); u16(p, 1); u32(p, 120);
        QByteArray inst;
        name(inst, {"Chromecast-abc"}, false);
        inst.append(char(0xC0)); inst.append(char(serviceAt));
        u16(p, quint16(inst.size())); p.append(inst);
        // SRV for the instance
        const int instAt = p.size();
        p.append(inst);
        u16(p, 33); u16(p, 0x8001); u32(p, 120);
        QByteArray srv; u16(srv, 0); u16(srv, 0); u16(srv, 8009); name(srv, {"abc", "local"});
        u16(p, quint16(srv.size())); p.append(srv);
        // TXT
        p.append(char(0xC0)); p.append(char(instAt));
        u16(p, 16); u16(p, 0x8001); u32(p, 120);
        QByteArray txt;
        for (const QByteArray kv : {QByteArray("id=abc123"), QByteArray("md=Chromecast"), QByteArray("fn=Living Room TV")}) { txt.append(char(kv.size())); txt.append(kv); }
        u16(p, quint16(txt.size())); p.append(txt);
        // A
        name(p, {"abc", "local"});
        u16(p, 1); u16(p, 0x8001); u32(p, 120); u16(p, 4);
        p.append(char(192)); p.append(char(168)); p.append(char(1)); p.append(char(23));

        const auto devices = parseMdns(p);
        QCOMPARE(devices.size(), 1);
        QCOMPARE(devices[0].name, QString("Living Room TV"));
        QCOMPARE(devices[0].model, QString("Chromecast"));
        QCOMPARE(devices[0].id, QString("abc123"));
        QCOMPARE(devices[0].host, QString("192.168.1.23"));
        QCOMPARE(devices[0].port, 8009);
        QVERIFY(parseMdns(QByteArray("short")).isEmpty());
    }
};

QTEST_APPLESS_MAIN(CastRulesTest)
#include "CastRulesTest.moc"
