#include "LocalMediaServer.h"
#include "core/NetRules.h"

#include <QDateTime>
#include <QEventLoop>
#include <QNetworkAccessManager>
#include <QNetworkReply>
#include <QNetworkRequest>
#include <QFile>
#include <QNetworkInterface>
#include <QRandomGenerator>
#include <QTcpSocket>
#include <QThreadPool>
#include <QUdpSocket>
#include <QUrl>

namespace {

class FileSource : public ByteSource
{
public:
    explicit FileSource(QString path) : m_file(std::move(path)) {}
    bool open(QString *error) override
    {
        if (m_file.open(QIODevice::ReadOnly)) return true;
        if (error) *error = m_file.errorString();
        return false;
    }
    qint64 size() const override { return m_file.size(); }
    qint64 read(qint64 offset, char *buf, qint64 max) override
    {
        if (m_file.pos() != offset && !m_file.seek(offset)) return -1;
        return m_file.read(buf, max);
    }

private:
    QFile m_file;
};

class HttpSource : public ByteSource
{
public:
    HttpSource(QString url, QList<QPair<QByteArray, QByteArray>> headers) : m_url(std::move(url)), m_headers(std::move(headers)) {}
    bool open(QString *error) override
    {
        m_nam = std::make_unique<QNetworkAccessManager>();
        int status = 0;
        QByteArray range;
        const QByteArray body = get(0, 0, &status, &range);
        if (status == 206) m_size = range.mid(range.lastIndexOf('/') + 1).toLongLong();
        else if (status == 200) m_size = m_lastLength;
        if (m_size <= 0) { if (error) *error = QStringLiteral("HTTP %1").arg(status); return false; }
        Q_UNUSED(body);
        return true;
    }
    qint64 size() const override { return m_size; }
    qint64 read(qint64 offset, char *buf, qint64 max) override
    {
        const qint64 n = std::min<qint64>(max, 1024 * 1024);
        int status = 0;
        const QByteArray data = get(offset, offset + n - 1, &status, nullptr);
        if (status != 206 && status != 200) return -1;
        const qint64 got = std::min<qint64>(data.size(), n);
        memcpy(buf, data.constData(), size_t(got));
        return got;
    }

private:
    QByteArray get(qint64 from, qint64 to, int *status, QByteArray *contentRange)
    {
        QNetworkRequest req{QUrl(m_url)};
        for (const auto &[k, v] : m_headers) req.setRawHeader(k, v);
        req.setRawHeader("Range", "bytes=" + QByteArray::number(from) + '-' + QByteArray::number(to));
        req.setTransferTimeout(20000);
        QNetworkReply *reply = m_nam->get(req);
        QEventLoop loop;
        QObject::connect(reply, &QNetworkReply::finished, &loop, &QEventLoop::quit);
        loop.exec();
        *status = reply->attribute(QNetworkRequest::HttpStatusCodeAttribute).toInt();
        if (contentRange) *contentRange = reply->rawHeader("Content-Range");
        m_lastLength = reply->header(QNetworkRequest::ContentLengthHeader).toLongLong();
        const QByteArray data = reply->readAll();
        reply->deleteLater();
        return data;
    }
    QString m_url;
    QList<QPair<QByteArray, QByteArray>> m_headers;
    std::unique_ptr<QNetworkAccessManager> m_nam;
    qint64 m_size = -1;
    qint64 m_lastLength = 0;
};

QString randomToken()
{
    static const char alphabet[] = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";
    QString t;
    for (int i = 0; i < 32; ++i) t += QLatin1Char(alphabet[QRandomGenerator::system()->bounded(62)]);
    return t;
}

bool writeAll(QTcpSocket &s, const char *data, qint64 len)
{
    qint64 done = 0;
    while (done < len) {
        const qint64 n = s.write(data + done, len - done);
        if (n < 0) return false;
        done += n;
        // Keep at most ~1 MB queued: the player reads at its own pace
        while (s.bytesToWrite() > 1024 * 1024)
            if (!s.waitForBytesWritten(30000)) return false;
    }
    return true;
}

} // namespace

std::unique_ptr<ByteSource> makeFileSource(const QString &path) { return std::make_unique<FileSource>(path); }

std::unique_ptr<ByteSource> makeHttpSource(const QString &url, const QList<QPair<QByteArray, QByteArray>> &headers)
{
    return std::make_unique<HttpSource>(url, headers);
}

LocalMediaServer::LocalMediaServer(bool lan, QObject *parent)
    : QTcpServer(parent), m_lan(lan), m_pool(std::make_unique<QThreadPool>())
{
    m_pool->setMaxThreadCount(12);
    m_pool->setExpiryTimeout(30000);
}

LocalMediaServer::~LocalMediaServer()
{
    close();
    m_pool->clear();
    m_pool->waitForDone(3000);
}

bool LocalMediaServer::ensureListening()
{
    if (isListening()) return true;
    return listen(m_lan ? QHostAddress::AnyIPv4 : QHostAddress::LocalHost, 0);
}

QString LocalMediaServer::publish(SourceFactory factory, const QString &fileName, const QHostAddress &peer)
{
    if (!ensureListening()) return {};
    const QString token = randomToken();
    auto entry = std::make_shared<Entry>();
    entry->factory = std::move(factory);
    entry->name = fileName;
    entry->lastUsed = QDateTime::currentMSecsSinceEpoch();
    {
        QMutexLocker l(&m_lock);
        // Keep the most recent few hundred: old queue entries get a new URL when played again
        if (m_entries.size() > 400) {
            auto oldest = m_entries.begin();
            for (auto it = m_entries.begin(); it != m_entries.end(); ++it)
                if (it.value()->lastUsed < oldest.value()->lastUsed) oldest = it;
            m_entries.erase(oldest);
        }
        m_entries.insert(token, entry);
    }
    QString host = QStringLiteral("127.0.0.1");
    if (m_lan) {
        // The local address on the route to the TV (connect() on UDP sends nothing)
        QUdpSocket probe;
        probe.connectToHost(peer.isNull() ? QHostAddress(QStringLiteral("192.168.1.1")) : peer, 9);
        if (probe.waitForConnected(500) && !probe.localAddress().isNull() && !probe.localAddress().isLoopback())
            host = probe.localAddress().toString();
        else
            for (const QHostAddress &a : QNetworkInterface::allAddresses())
                if (a.protocol() == QAbstractSocket::IPv4Protocol && !a.isLoopback()) { host = a.toString(); break; }
    }
    QString safeName = fileName;
    safeName.replace(u'/', u'_').replace(u'\\', u'_');
    return QStringLiteral("http://%1:%2/%3/%4").arg(host).arg(serverPort()).arg(token,
                                                       QString::fromUtf8(QUrl::toPercentEncoding(safeName)));
}

void LocalMediaServer::clear()
{
    QMutexLocker l(&m_lock);
    m_entries.clear();
}

std::shared_ptr<LocalMediaServer::Entry> LocalMediaServer::lookup(const QString &token)
{
    QMutexLocker l(&m_lock);
    auto e = m_entries.value(token);
    if (e) e->lastUsed = QDateTime::currentMSecsSinceEpoch();
    return e;
}

void LocalMediaServer::incomingConnection(qintptr socketDescriptor)
{
    m_pool->start([this, socketDescriptor] { serve(socketDescriptor); });
}

void LocalMediaServer::serve(qintptr fd)
{
    QTcpSocket s;
    if (!s.setSocketDescriptor(fd)) return;
    // Request line + headers
    QByteArray head;
    while (!head.contains("\r\n\r\n")) {
        if (!s.waitForReadyRead(15000)) return;
        head += s.readAll();
        if (head.size() > 32 * 1024) return;
    }
    const QList<QByteArray> lines = head.left(head.indexOf("\r\n\r\n")).split('\n');
    const QList<QByteArray> request = lines.value(0).trimmed().split(' ');
    const QByteArray method = request.value(0);
    const QString path = QString::fromUtf8(request.value(1));
    QString rangeHeader;
    for (int i = 1; i < lines.size(); ++i) {
        const QByteArray l = lines[i].trimmed();
        if (l.toLower().startsWith("range:")) rangeHeader = QString::fromLatin1(l.mid(6).trimmed());
    }
    auto reply = [&s](const QByteArray &status, const QByteArray &extra = {}) {
        const QByteArray r = "HTTP/1.1 " + status + "\r\nContent-Length: 0\r\nConnection: close\r\n" + extra + "\r\n";
        s.write(r);
        s.waitForBytesWritten(3000);
        s.disconnectFromHost();
    };
    if (method != "GET" && method != "HEAD") { reply("405 Method Not Allowed"); return; }
    const QString token = path.section(u'/', 1, 1);
    if (!NetRules::isValidToken(token)) { reply("404 Not Found"); return; }
    const auto entry = lookup(token);
    if (!entry) { reply("404 Not Found"); return; }

    std::unique_ptr<ByteSource> source = entry->factory();
    QString error;
    if (!source || !source->open(&error)) { reply("502 Bad Gateway"); return; }
    const qint64 size = source->size();
    const auto range = NetRules::parseRange(rangeHeader, size);
    if (range && !range->satisfiable) { reply("416 Range Not Satisfiable", "Content-Range: bytes */" + QByteArray::number(size) + "\r\n"); return; }
    const qint64 start = range ? range->start : 0;
    const qint64 end = range ? range->end : size - 1;
    const qint64 length = size > 0 ? end - start + 1 : 0;

    QByteArray h = range ? "HTTP/1.1 206 Partial Content\r\n" : "HTTP/1.1 200 OK\r\n";
    h += "Content-Type: " + NetRules::mimeFor(entry->name).toLatin1() + "\r\n";
    h += "Content-Length: " + QByteArray::number(length) + "\r\n";
    h += "Accept-Ranges: bytes\r\n";
    if (range) h += "Content-Range: bytes " + QByteArray::number(start) + '-' + QByteArray::number(end) + '/' + QByteArray::number(size) + "\r\n";
    // DLNA TVs want these to allow seeking
    h += "transferMode.dlna.org: Streaming\r\n";
    h += "contentFeatures.dlna.org: DLNA.ORG_OP=01;DLNA.ORG_CI=0;DLNA.ORG_FLAGS=01700000000000000000000000000000\r\n";
    h += "Connection: close\r\n\r\n";
    if (!writeAll(s, h.constData(), h.size())) return;
    if (method == "GET") {
        QByteArray buf(256 * 1024, Qt::Uninitialized);
        qint64 pos = start;
        while (pos <= end && s.state() == QAbstractSocket::ConnectedState) {
            const qint64 n = source->read(pos, buf.data(), std::min<qint64>(buf.size(), end - pos + 1));
            if (n <= 0) break;
            if (!writeAll(s, buf.constData(), n)) break;
            pos += n;
        }
    }
    if (s.state() == QAbstractSocket::ConnectedState) {
        s.waitForBytesWritten(30000);
        s.disconnectFromHost();
        if (s.state() != QAbstractSocket::UnconnectedState) s.waitForDisconnected(2000);
    }
}
