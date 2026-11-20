#include "CastRules.h"

#include <QHash>
#include <QtEndian>

namespace CastRules {

// ---- protobuf ------------------------------------------------------------------

static void putVarint(QByteArray &out, quint64 v)
{
    while (v >= 0x80) {
        out.append(char((v & 0x7F) | 0x80));
        v >>= 7;
    }
    out.append(char(v));
}

static void putString(QByteArray &out, int field, const QByteArray &s)
{
    putVarint(out, quint64(field) << 3 | 2);
    putVarint(out, quint64(s.size()));
    out.append(s);
}

QByteArray encodeFrame(const CastMessage &m)
{
    QByteArray body;
    putVarint(body, 1 << 3 | 0); putVarint(body, 0);                 // protocol_version = CASTV2_1_0
    putString(body, 2, m.sourceId.toUtf8());
    putString(body, 3, m.destinationId.toUtf8());
    putString(body, 4, m.ns.toUtf8());
    putVarint(body, 5 << 3 | 0); putVarint(body, 0);                 // payload_type = STRING
    putString(body, 6, m.payload.toUtf8());
    QByteArray frame(4, '\0');
    qToBigEndian<quint32>(quint32(body.size()), frame.data());
    return frame + body;
}

static bool getVarint(const QByteArray &b, int &pos, quint64 &v)
{
    v = 0;
    for (int shift = 0; shift < 64; shift += 7) {
        if (pos >= b.size()) return false;
        const quint8 byte = quint8(b[pos++]);
        v |= quint64(byte & 0x7F) << shift;
        if (!(byte & 0x80)) return true;
    }
    return false;
}

std::optional<CastMessage> decodeMessage(const QByteArray &body)
{
    CastMessage m;
    int pos = 0;
    while (pos < body.size()) {
        quint64 key = 0;
        if (!getVarint(body, pos, key)) return std::nullopt;
        const int field = int(key >> 3), wire = int(key & 7);
        if (wire == 0) {
            quint64 v;
            if (!getVarint(body, pos, v)) return std::nullopt;
        } else if (wire == 2) {
            quint64 len;
            if (!getVarint(body, pos, len) || pos + qint64(len) > body.size()) return std::nullopt;
            const QByteArray s = body.mid(pos, int(len));
            pos += int(len);
            if (field == 2) m.sourceId = QString::fromUtf8(s);
            else if (field == 3) m.destinationId = QString::fromUtf8(s);
            else if (field == 4) m.ns = QString::fromUtf8(s);
            else if (field == 6) m.payload = QString::fromUtf8(s);
        } else if (wire == 5) {
            pos += 4;
        } else if (wire == 1) {
            pos += 8;
        } else {
            return std::nullopt;
        }
    }
    if (pos != body.size() || m.ns.isEmpty()) return std::nullopt;
    return m;
}

QList<CastMessage> takeFrames(QByteArray &buffer)
{
    QList<CastMessage> out;
    while (buffer.size() >= 4) {
        const quint32 len = qFromBigEndian<quint32>(buffer.constData());
        if (len > 1024 * 1024) { buffer.clear(); break; } // garbage: drop it
        if (buffer.size() < int(4 + len)) break;
        const auto m = decodeMessage(buffer.mid(4, int(len)));
        buffer.remove(0, int(4 + len));
        if (m) out << *m;
    }
    return out;
}

// ---- mDNS ------------------------------------------------------------------------

static void putName(QByteArray &out, const QString &name)
{
    for (const QString &label : name.split(u'.', Qt::SkipEmptyParts)) {
        const QByteArray l = label.toUtf8();
        out.append(char(l.size()));
        out.append(l);
    }
    out.append('\0');
}

QByteArray mdnsQuery(const QString &service)
{
    QByteArray q;
    const char header[12] = {0, 0, 0, 0, 0, 1, 0, 0, 0, 0, 0, 0}; // id 0, flags 0, 1 question
    q.append(header, 12);
    putName(q, service);
    q.append("\x00\x0c", 2);  // PTR
    q.append("\x00\x01", 2);  // IN (multicast answer)
    return q;
}

static bool readName(const QByteArray &p, int &pos, QString &name, int depth = 0)
{
    if (depth > 10) return false;
    QStringList labels;
    for (;;) {
        if (pos >= p.size()) return false;
        const quint8 len = quint8(p[pos]);
        if (len == 0) { ++pos; break; }
        if ((len & 0xC0) == 0xC0) {
            if (pos + 1 >= p.size()) return false;
            int ptr = ((len & 0x3F) << 8) | quint8(p[pos + 1]);
            pos += 2;
            QString rest;
            if (ptr >= p.size() || !readName(p, ptr, rest, depth + 1)) return false;
            if (!rest.isEmpty()) labels << rest;
            name = labels.join(u'.');
            return true;
        }
        if (pos + 1 + len > p.size()) return false;
        labels << QString::fromUtf8(p.mid(pos + 1, len));
        pos += 1 + len;
    }
    name = labels.join(u'.');
    return true;
}

QList<CastDevice> parseMdns(const QByteArray &p, const QString &service)
{
    if (p.size() < 12) return {};
    const int qd = qFromBigEndian<quint16>(p.constData() + 4);
    const int records = qFromBigEndian<quint16>(p.constData() + 6) + qFromBigEndian<quint16>(p.constData() + 8)
                        + qFromBigEndian<quint16>(p.constData() + 10);
    int pos = 12;
    for (int i = 0; i < qd; ++i) {
        QString n;
        if (!readName(p, pos, n)) return {};
        pos += 4;
    }
    QStringList instances;
    QHash<QString, CastDevice> byInstance;
    QHash<QString, QString> targetOf;   // instance -> host name
    QHash<QString, QString> addressOf;  // host name -> IPv4
    for (int i = 0; i < records && pos < p.size(); ++i) {
        QString name;
        if (!readName(p, pos, name) || pos + 10 > p.size()) break;
        const quint16 type = qFromBigEndian<quint16>(p.constData() + pos);
        const quint16 rdlen = qFromBigEndian<quint16>(p.constData() + pos + 8);
        pos += 10;
        if (pos + rdlen > p.size()) break;
        const int rdStart = pos;
        if (type == 12 && name.compare(service, Qt::CaseInsensitive) == 0) {       // PTR
            int r = rdStart;
            QString inst;
            if (readName(p, r, inst) && !instances.contains(inst)) instances << inst;
        } else if (type == 33) {                                                  // SRV
            int r = rdStart + 6;
            QString target;
            if (r <= rdStart + rdlen && readName(p, r, target)) {
                byInstance[name].port = qFromBigEndian<quint16>(p.constData() + rdStart + 4);
                targetOf.insert(name, target);
            }
        } else if (type == 16) {                                                  // TXT
            int r = rdStart;
            while (r < rdStart + rdlen) {
                const int len = quint8(p[r]);
                const QString kv = QString::fromUtf8(p.mid(r + 1, len));
                r += 1 + len;
                if (kv.startsWith(QLatin1String("fn="))) byInstance[name].name = kv.mid(3);
                else if (kv.startsWith(QLatin1String("md="))) byInstance[name].model = kv.mid(3);
                else if (kv.startsWith(QLatin1String("id="))) byInstance[name].id = kv.mid(3);
            }
        } else if (type == 1 && rdlen == 4) {                                     // A
            addressOf.insert(name, QStringLiteral("%1.%2.%3.%4").arg(quint8(p[rdStart])).arg(quint8(p[rdStart + 1]))
                                       .arg(quint8(p[rdStart + 2])).arg(quint8(p[rdStart + 3])));
        }
        pos = rdStart + rdlen;
    }
    QList<CastDevice> out;
    for (auto it = byInstance.begin(); it != byInstance.end(); ++it) {
        if (!it.key().endsWith(service, Qt::CaseInsensitive)) continue;
        CastDevice d = it.value();
        if (d.port == 0) d.port = 8009;
        if (d.id.isEmpty()) d.id = it.key();
        if (d.name.isEmpty()) d.name = it.key().section(u'.', 0, 0);
        d.host = addressOf.value(targetOf.value(it.key()));
        out << d;
    }
    for (const QString &inst : instances) {
        bool known = false;
        for (const auto &d : out) if (byInstance.contains(inst)) known = true;
        if (!known && !byInstance.contains(inst)) {
            CastDevice d;
            d.id = inst;
            d.name = inst.section(u'.', 0, 0);
            out << d;   // host unknown yet: a later packet completes it
        }
    }
    return out;
}

}
