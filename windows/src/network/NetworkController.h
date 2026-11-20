#pragma once
#include "SecretStore.h"
#include "ShareFs.h"
#include "core/NetRules.h"
#include "player/LocationResolver.h"

#include <QHash>
#include <QMutex>
#include <QNetworkAccessManager>
#include <QObject>
#include <QReadWriteLock>
#include <QVariantList>
#include <functional>
#include <memory>

class QNetworkReply;
class QUdpSocket;
class LocalMediaServer;

/**
 * Network sources for QML ("net"): saved servers (Jellyfin, Nextcloud,
 * WebDAV, SMB, FTP, NFS, DLNA, stream links), browsing, Jellyfin home rows,
 * search and playback reports, DLNA discovery. Installs the LocationResolver
 * that turns credential-free keys into playable URLs.
 */
class NetworkController : public QObject
{
    Q_OBJECT
    Q_PROPERTY(QVariantList servers READ servers NOTIFY serversChanged)
    Q_PROPERTY(QVariantList discovered READ discovered NOTIFY discoveredChanged)
    Q_PROPERTY(bool discovering READ discovering NOTIFY discoveredChanged)
    Q_PROPERTY(QVariantList serverTypes READ serverTypes CONSTANT)

public:
    struct Server {
        QString id, type, name, address, user, base, userId, controlUrl;
        qint64 addedMs = 0;
    };

    NetworkController(const QString &dataDir, const QString &version, QObject *parent = nullptr);
    ~NetworkController() override;

    QVariantList servers() const;
    QVariantList discovered() const { return m_discovered; }
    bool discovering() const { return m_discovering; }
    QVariantList serverTypes() const;

    /** form: {id?, type, name, address, user, password}. Tests the connection, then saves. */
    Q_INVOKABLE void saveServer(const QVariantMap &form);
    Q_INVOKABLE void removeServer(const QString &id);
    Q_INVOKABLE QVariantMap server(const QString &id) const;
    /** Lists a folder / library ("" = top); answers with browseDone(request, ...). */
    Q_INVOKABLE int browse(const QString &id, const QString &path);
    Q_INVOKABLE int search(const QString &id, const QString &query);
    /** Jellyfin: Continue watching, Next up, Latest -> homeDone(request, sections, error). */
    Q_INVOKABLE int jellyfinHome(const QString &id);
    Q_INVOKABLE void discover();
    Q_INVOKABLE void addDiscovered(int index);
    Q_INVOKABLE bool isNetworkKey(const QString &location) const;
    /** Server-side resume point (Jellyfin) for a key, 0 if unknown. */
    Q_INVOKABLE qint64 resumePositionFor(const QString &location) const;
    /** Jellyfin playback reports: event = "start", "progress", "stop". */
    Q_INVOKABLE void reportPlayback(const QString &location, qint64 positionMs, bool paused, const QString &event);

    ResolvedLocation resolve(const QString &location);
    QString titleFor(const QString &location) const;
    /** The LAN server used for Play on TV (shares and local files). */
    QString publishForTv(const QString &location, const QString &tvHost, QString *mime);

signals:
    void serversChanged();
    void discoveredChanged();
    void serverSaved(bool ok, const QString &message, const QString &id);
    void browseDone(int request, const QVariantList &entries, const QString &error);
    void homeDone(int request, const QVariantList &sections, const QString &error);

private:
    using Reply = std::function<void(int status, const QByteArray &body, const QString &error)>;
    void request(const QByteArray &verb, const QString &url, const QList<QPair<QByteArray, QByteArray>> &headers,
                 const QByteArray &body, Reply done, int timeoutMs = 15000);
    QList<QPair<QByteArray, QByteArray>> jellyfinHeaders(const QString &token = {}) const;
    void load();
    void save() const;
    void commitServer(const Server &s, const QString &secret, bool keepSecret);
    Server serverById(const QString &id) const;
    QString secretFor(const QString &id) const;
    ShareLogin loginFor(const Server &s) const;
    QVariantList toVariant(const Server &s, const QList<RemoteEntry> &entries);
    QString netKey(const QString &serverId, const QString &path) const;
    void browseJellyfin(int req, const Server &s, const QString &path);
    void browseDav(int req, const Server &s, const QString &path);
    void browseDlna(int req, const Server &s, const QString &path);
    void browseShare(int req, const Server &s, const QString &path);
    void finishSave(bool ok, const QString &message, const Server &s, const QString &secret, bool keepSecret);

    QString m_dataDir;
    QString m_version;
    QString m_deviceId;
    QNetworkAccessManager m_nam;
    SecretStore m_secrets;
    mutable QReadWriteLock m_lock;          // m_servers, m_secretCache (resolver threads read them)
    QList<Server> m_servers;
    QHash<QString, QString> m_secretCache;
    mutable QMutex m_cacheLock;             // titles, subtitles, resume points of browsed items
    QHash<QString, QString> m_titles;
    QHash<QString, QStringList> m_subtitles;
    QHash<QString, qint64> m_resume;
    QHash<QString, QString> m_playSessions;
    std::unique_ptr<LocalMediaServer> m_local;
    std::unique_ptr<LocalMediaServer> m_lan;
    int m_nextRequest = 1;
    QVariantList m_discovered;
    bool m_discovering = false;
    QUdpSocket *m_ssdp = nullptr;
};
