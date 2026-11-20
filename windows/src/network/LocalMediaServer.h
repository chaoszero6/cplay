#pragma once
#include <QHash>
#include <QHostAddress>
#include <QMutex>
#include <QString>
#include <QTcpServer>
#include <functional>
#include <memory>

class QThreadPool;

/** Random-access bytes of one file (local, SMB, NFS, FTP). One instance per HTTP request. */
class ByteSource
{
public:
    virtual ~ByteSource() = default;
    virtual bool open(QString *error) = 0;
    virtual qint64 size() const = 0;
    /** Up to [max] bytes at [offset]; 0 at the end, -1 on error. */
    virtual qint64 read(qint64 offset, char *buf, qint64 max) = 0;
};
using SourceFactory = std::function<std::unique_ptr<ByteSource>()>;

/**
 * Tiny HTTP server that hands share files (SMB, NFS, FTP) to mpv, and local
 * files to a TV, as http://<host>:<port>/<token>/<name> with Range support.
 * Each URL carries a random 32-character token; passwords stay inside CPlay.
 * The loopback instance only listens on 127.0.0.1; the LAN instance (Play on
 * TV) starts on demand.
 */
class LocalMediaServer : public QTcpServer
{
    Q_OBJECT
public:
    explicit LocalMediaServer(bool lan, QObject *parent = nullptr);
    ~LocalMediaServer() override;
    /** URL for [factory]. [peer] (LAN only) picks the address the TV can reach. */
    QString publish(SourceFactory factory, const QString &fileName, const QHostAddress &peer = {});
    void clear();
    bool ensureListening();

protected:
    void incomingConnection(qintptr socketDescriptor) override;

private:
    struct Entry { SourceFactory factory; QString name; qint64 lastUsed = 0; };
    void serve(qintptr fd);
    std::shared_ptr<Entry> lookup(const QString &token);

    bool m_lan;
    QMutex m_lock;
    QHash<QString, std::shared_ptr<Entry>> m_entries;
    std::unique_ptr<QThreadPool> m_pool;
};

/** Plain local file (for Play on TV). */
std::unique_ptr<ByteSource> makeFileSource(const QString &path);
/** HTTP(S) file read with Range requests and extra headers (logins stay on this PC). */
std::unique_ptr<ByteSource> makeHttpSource(const QString &url, const QList<QPair<QByteArray, QByteArray>> &headers);
