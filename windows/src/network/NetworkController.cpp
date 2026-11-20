#include "NetworkController.h"
#include "LocalMediaServer.h"
#include "core/LibraryRules.h"
#include "core/MediaFiles.h"

#include <QDateTime>
#include <QDir>
#include <QFile>
#include <QFileInfo>
#include <QJsonArray>
#include <QJsonDocument>
#include <QJsonObject>
#include <QNetworkReply>
#include <QNetworkRequest>
#include <QPointer>
#include <QRandomGenerator>
#include <QSaveFile>
#include <QSettings>
#include <QThreadPool>
#include <QTimer>
#include <QUdpSocket>
#include <QUrl>
#include <QUuid>

using namespace NetRules;

namespace {
const QString kNetScheme = QStringLiteral("cplay-net://");

QString typeLabel(const QString &t)
{
    static const QHash<QString, QString> labels{
        {"jellyfin", "Jellyfin"}, {"nextcloud", "Nextcloud"}, {"webdav", "WebDAV"}, {"smb", "Windows share (SMB)"},
        {"ftp", "FTP"}, {"nfs", "NFS"}, {"dlna", "DLNA / UPnP"}, {"url", "Stream link"}};
    return labels.value(t, t);
}

QString typeIcon(const QString &t)
{
    static const QHash<QString, QString> icons{
        {"jellyfin", "live_tv"}, {"nextcloud", "cloud"}, {"webdav", "cloud_queue"}, {"smb", "dns"},
        {"ftp", "storage"}, {"nfs", "storage"}, {"dlna", "devices"}, {"url", "link"}};
    return icons.value(t, "lan");
}

QString lastSegment(const QString &path)
{
    QString p = path;
    while (p.endsWith(u'/')) p.chop(1);
    return QUrl::fromPercentEncoding(p.section(u'/', -1).toUtf8());
}
} // namespace

NetworkController::NetworkController(const QString &dataDir, const QString &version, QObject *parent)
    : QObject(parent), m_dataDir(dataDir), m_version(version), m_secrets(dataDir + QStringLiteral("/secrets.json")),
      m_local(std::make_unique<LocalMediaServer>(false)), m_lan(std::make_unique<LocalMediaServer>(true))
{
    QSettings settings(dataDir + QStringLiteral("/settings.ini"), QSettings::IniFormat);
    m_deviceId = settings.value(QStringLiteral("network/deviceId")).toString();
    if (m_deviceId.isEmpty()) {
        m_deviceId = QUuid::createUuid().toString(QUuid::WithoutBraces);
        settings.setValue(QStringLiteral("network/deviceId"), m_deviceId);
    }
    load();
    LocationResolver::install([this](const QString &l) { return resolve(l); },
                              [this](const QString &l) { return titleFor(l); });
}

NetworkController::~NetworkController()
{
    LocationResolver::install({}, {});
}

// ---- storage ------------------------------------------------------------------

void NetworkController::load()
{
    QFile f(m_dataDir + QStringLiteral("/servers.json"));
    if (f.open(QIODevice::ReadOnly)) {
        for (const auto &v : QJsonDocument::fromJson(f.readAll()).array()) {
            const QJsonObject o = v.toObject();
            Server s;
            s.id = o.value("id").toString();
            s.type = o.value("type").toString();
            s.name = o.value("name").toString();
            s.address = o.value("address").toString();
            s.user = o.value("user").toString();
            s.base = o.value("base").toString();
            s.userId = o.value("userId").toString();
            s.controlUrl = o.value("controlUrl").toString();
            s.addedMs = qint64(o.value("added").toDouble());
            if (!s.id.isEmpty() && !s.type.isEmpty()) m_servers << s;
        }
    }
    QWriteLocker w(&m_lock);
    for (const auto &s : std::as_const(m_servers)) m_secretCache.insert(s.id, m_secrets.get(s.id));
}

void NetworkController::save() const
{
    QJsonArray a;
    {
        QReadLocker r(&m_lock);
        for (const auto &s : m_servers)
            a << QJsonObject{{"id", s.id}, {"type", s.type}, {"name", s.name}, {"address", s.address}, {"user", s.user},
                             {"base", s.base}, {"userId", s.userId}, {"controlUrl", s.controlUrl}, {"added", double(s.addedMs)}};
    }
    QDir().mkpath(m_dataDir);
    QSaveFile f(m_dataDir + QStringLiteral("/servers.json"));
    if (f.open(QIODevice::WriteOnly)) {
        f.write(QJsonDocument(a).toJson());
        f.commit();
    }
}

NetworkController::Server NetworkController::serverById(const QString &id) const
{
    QReadLocker r(&m_lock);
    for (const auto &s : m_servers) if (s.id == id) return s;
    return {};
}

QString NetworkController::secretFor(const QString &id) const
{
    QReadLocker r(&m_lock);
    return m_secretCache.value(id);
}

QVariantList NetworkController::servers() const
{
    QVariantList out;
    QReadLocker r(&m_lock);
    for (const auto &s : m_servers)
        out << QVariantMap{{"id", s.id}, {"type", s.type}, {"name", s.name}, {"address", s.address}, {"user", s.user},
                           {"typeLabel", typeLabel(s.type)}, {"icon", typeIcon(s.type)}};
    return out;
}

QVariantMap NetworkController::server(const QString &id) const
{
    const Server s = serverById(id);
    return {{"id", s.id}, {"type", s.type}, {"name", s.name}, {"address", s.address}, {"user", s.user},
            {"hasPassword", !secretFor(id).isEmpty()}};
}

QVariantList NetworkController::serverTypes() const
{
    auto t = [](const char *key, const char *hint, bool user, const char *help) {
        return QVariantMap{{"key", key}, {"label", typeLabel(key)}, {"icon", typeIcon(key)}, {"addressHint", hint},
                           {"needsUser", user}, {"help", help}};
    };
    return {t("jellyfin", "http://192.168.1.10:8096", true, "Your Jellyfin server address. CPlay signs in once and keeps only the access token."),
            t("nextcloud", "cloud.example.com", true, "Your Nextcloud address. Use an app password if two-factor sign-in is on."),
            t("webdav", "https://example.com/webdav", true, "The WebDAV folder address."),
            t("smb", "\\\\nas\\Videos", true, "\\\\server or \\\\server\\share. Leave the user empty for guest access."),
            t("ftp", "ftp://nas:21/videos", true, "Server name or ftp:// address. Leave the user empty for anonymous FTP."),
            t("nfs", "nas:/export/videos", false, "Server name, or server:/export to open one export."),
            t("dlna", "http://192.168.1.10:8200/rootDesc.xml", false, "Usually found for you: press Search on the Network page."),
            t("url", "https://example.com/live/stream.m3u8", false, "A video, HLS (.m3u8) or DASH (.mpd) link to keep.")};
}

void NetworkController::commitServer(const Server &s, const QString &secret, bool keepSecret)
{
    {
        QWriteLocker w(&m_lock);
        bool replaced = false;
        for (auto &x : m_servers) if (x.id == s.id) { x = s; replaced = true; }
        if (!replaced) m_servers << s;
        if (!keepSecret) m_secretCache.insert(s.id, secret);
    }
    if (!keepSecret) m_secrets.set(s.id, secret);
    save();
    emit serversChanged();
}

void NetworkController::removeServer(const QString &id)
{
    {
        QWriteLocker w(&m_lock);
        m_servers.erase(std::remove_if(m_servers.begin(), m_servers.end(), [&](const Server &s) { return s.id == id; }), m_servers.end());
        m_secretCache.remove(id);
    }
    m_secrets.remove(id);
    save();
    emit serversChanged();
}

// ---- HTTP -------------------------------------------------------------------------

void NetworkController::request(const QByteArray &verb, const QString &url, const QList<QPair<QByteArray, QByteArray>> &headers,
                                const QByteArray &body, Reply done, int timeoutMs)
{
    QNetworkRequest req{QUrl(url)};
    req.setTransferTimeout(timeoutMs);
    req.setAttribute(QNetworkRequest::RedirectPolicyAttribute, QNetworkRequest::NoLessSafeRedirectPolicy);
    req.setHeader(QNetworkRequest::UserAgentHeader, QStringLiteral("CPlay/") + m_version);
    for (const auto &[k, v] : headers) req.setRawHeader(k, v);
    QNetworkReply *reply = m_nam.sendCustomRequest(req, verb, body);
    connect(reply, &QNetworkReply::finished, this, [reply, done] {
        reply->deleteLater();
        int status = reply->attribute(QNetworkRequest::HttpStatusCodeAttribute).toInt();
        const QByteArray data = reply->readAll();
        QString error;
        // A 401 without a login challenge (Jellyfin) surfaces as an error without a status
        if (status == 0 && reply->error() == QNetworkReply::AuthenticationRequiredError) status = 401;
        if (status == 0 && reply->error() == QNetworkReply::ContentAccessDenied) status = 403;
        if (status == 0) qDebug("CPlay network: %s -> error %d", qPrintable(reply->url().toDisplayString(QUrl::RemoveQuery | QUrl::RemoveUserInfo)), int(reply->error()));
        if (status == 0) {
            const auto e = reply->error();
            if (e == QNetworkReply::OperationCanceledError || e == QNetworkReply::TimeoutError) error = QStringLiteral("The server did not answer in time");
            else if (e == QNetworkReply::HostNotFoundError) error = QStringLiteral("Server not found. Check the address");
            else if (e == QNetworkReply::ConnectionRefusedError) error = QStringLiteral("The server refused the connection. Check the address and port");
            else if (e >= QNetworkReply::SslHandshakeFailedError && e <= QNetworkReply::UnknownNetworkError && reply->error() == QNetworkReply::SslHandshakeFailedError)
                error = QStringLiteral("Secure connection failed (certificate not trusted)");
            else error = reply->errorString();
        }
        done(status, data, error);
    });
}

QList<QPair<QByteArray, QByteArray>> NetworkController::jellyfinHeaders(const QString &token) const
{
    return {{"Authorization", jellyfinAuthHeader(m_deviceId, m_version, token).toUtf8()},
            {"Accept", "application/json"}, {"Content-Type", "application/json"}};
}

// ---- saving -----------------------------------------------------------------------

void NetworkController::finishSave(bool ok, const QString &message, const Server &s, const QString &secret, bool keepSecret)
{
    if (ok) commitServer(s, secret, keepSecret);
    emit serverSaved(ok, ok ? QStringLiteral("Saved") : message, s.id);
}

void NetworkController::saveServer(const QVariantMap &form)
{
    Server s;
    s.id = form.value("id").toString();
    const Server old = s.id.isEmpty() ? Server{} : serverById(s.id);
    if (s.id.isEmpty()) s.id = QUuid::createUuid().toString(QUuid::WithoutBraces).left(12);
    s.type = form.value("type").toString();
    s.address = form.value("address").toString().trimmed();
    s.user = form.value("user").toString().trimmed();
    s.addedMs = old.addedMs > 0 ? old.addedMs : QDateTime::currentMSecsSinceEpoch();
    QString password = form.value("password").toString();
    // Editing without typing the password again keeps the saved one (same user)
    const bool keep = password.isEmpty() && !old.id.isEmpty() && old.user == s.user && s.type != QLatin1String("jellyfin");
    if (keep) password = secretFor(s.id);
    QString name = form.value("name").toString().trimmed();
    if (s.address.isEmpty()) { emit serverSaved(false, QStringLiteral("Type the server address"), s.id); return; }

    if (s.type == QLatin1String("jellyfin")) {
        s.base = jellyfinServerUrl(s.address);
        if (password.isEmpty() && !old.id.isEmpty() && old.user == s.user && !secretFor(s.id).isEmpty()) {
            // Only the name/address changed: keep the token
            s.userId = old.userId;
            s.name = name.isEmpty() ? old.name : name;
            finishSave(true, {}, s, {}, true);
            return;
        }
        request("GET", s.base + QStringLiteral("/System/Info/Public"), {}, {}, [=, this](int st, const QByteArray &info, const QString &err) mutable {
            if (st != 200) { emit serverSaved(false, err.isEmpty() ? jellyfinError(st) : err, s.id); return; }
            const QString serverName = QJsonDocument::fromJson(info).object().value("ServerName").toString();
            request("POST", s.base + QStringLiteral("/Users/AuthenticateByName"), jellyfinHeaders(), jellyfinAuthBody(s.user, password).toUtf8(),
                    [=, this](int st2, const QByteArray &body, const QString &err2) mutable {
                const auto auth = parseJellyfinAuth(body);
                if (st2 != 200 || !auth) { emit serverSaved(false, err2.isEmpty() ? jellyfinError(st2 == 200 ? 401 : st2) : err2, s.id); return; }
                s.userId = auth->userId;
                s.name = name.isEmpty() ? (serverName.isEmpty() ? QStringLiteral("Jellyfin") : serverName) : name;
                finishSave(true, {}, s, auth->token, false);
            });
        });
        return;
    }
    if (s.type == QLatin1String("nextcloud") || s.type == QLatin1String("webdav")) {
        s.base = s.type == QLatin1String("nextcloud") ? nextcloudDavRoot(s.address, s.user) : webDavRoot(s.address);
        s.name = name.isEmpty() ? (s.type == QLatin1String("nextcloud") ? QStringLiteral("Nextcloud") : QUrl(s.base).host()) : name;
        QList<QPair<QByteArray, QByteArray>> h{{"Depth", "0"}, {"Content-Type", "application/xml"}};
        if (!s.user.isEmpty()) h << qMakePair(QByteArray("Authorization"), basicAuth(s.user, password).toUtf8());
        request("PROPFIND", s.base, h, propfindBody(), [=, this](int st, const QByteArray &, const QString &err) {
            if (st == 207 || st == 200) finishSave(true, {}, s, password, keep);
            else emit serverSaved(false, st == 401 ? QStringLiteral("Wrong user name or password")
                                       : st == 404 ? QStringLiteral("No WebDAV folder at this address")
                                       : (err.isEmpty() ? QStringLiteral("Server answered %1").arg(st) : err), s.id);
        });
        return;
    }
    if (s.type == QLatin1String("dlna")) {
        request("GET", s.address, {}, {}, [=, this](int st, const QByteArray &xml, const QString &err) mutable {
            const auto dev = st == 200 ? parseDeviceDescription(xml, s.address, QStringLiteral("ContentDirectory")) : std::nullopt;
            if (!dev) { emit serverSaved(false, err.isEmpty() ? QStringLiteral("No DLNA media server at this address") : err, s.id); return; }
            s.controlUrl = dev->controlUrl;
            s.name = name.isEmpty() ? dev->name : name;
            finishSave(true, {}, s, {}, false);
        });
        return;
    }
    if (s.type == QLatin1String("url")) {
        s.name = name.isEmpty() ? MediaFiles::displayName(s.address) : name;
        finishSave(true, {}, s, {}, false);
        return;
    }
    if (s.type == QLatin1String("smb") || s.type == QLatin1String("ftp") || s.type == QLatin1String("nfs")) {
        const ShareAddress a = s.type == QLatin1String("smb") ? parseSmb(s.address) : s.type == QLatin1String("ftp") ? parseFtp(s.address) : parseNfs(s.address);
        if (!a.valid()) { emit serverSaved(false, QStringLiteral("That address doesn't look right"), s.id); return; }
        s.name = name.isEmpty() ? (a.share.isEmpty() ? a.host : a.host + QStringLiteral(" · ") + a.share.section(u'/', -1)) : name;
        Server copy = s;
        ShareLogin login = loginFor(copy);
        login.password = password;
        QPointer<NetworkController> self(this);
        QThreadPool::globalInstance()->start([self, login, copy, password, keep] {
            QString error;
            const bool ok = ShareFs::test(login, &error);
            if (!self) return;
            QMetaObject::invokeMethod(self, [self, ok, error, copy, password, keep] {
                if (self) self->finishSave(ok, error, copy, password, keep);
            }, Qt::QueuedConnection);
        });
        return;
    }
    emit serverSaved(false, QStringLiteral("Unknown kind of server"), s.id);
}

ShareLogin NetworkController::loginFor(const Server &s) const
{
    ShareLogin l;
    l.type = s.type;
    l.user = s.user;
    l.password = secretFor(s.id);
    ShareAddress a = s.type == QLatin1String("smb") ? parseSmb(s.address) : s.type == QLatin1String("ftp") ? parseFtp(s.address) : parseNfs(s.address);
    l.host = a.host;
    l.port = s.type == QLatin1String("nfs") ? a.port : a.port;
    l.root = s.type == QLatin1String("ftp") ? a.path : (a.share.isEmpty() ? QString() : (s.type == QLatin1String("smb") ? NetRules::joinPath(a.share, a.path) : a.share));
    if (s.type == QLatin1String("nfs") && !a.path.isEmpty() && !a.share.isEmpty()) l.root = a.share; // sub-folder handled by browsing
    return l;
}

// ---- browsing -------------------------------------------------------------------

QString NetworkController::netKey(const QString &serverId, const QString &path) const
{
    return kNetScheme + serverId + u'/' + QString::fromUtf8(QUrl::toPercentEncoding(path));
}

QVariantList NetworkController::toVariant(const Server &s, const QList<RemoteEntry> &entries)
{
    QVariantList out;
    QMutexLocker l(&m_cacheLock);
    for (const auto &e : entries) {
        QString key = e.playKey;
        if (!e.isFolder && key.isEmpty()) key = netKey(s.id, e.id);
        if (!e.isFolder) {
            m_titles.insert(key, e.name);
            if (!e.subtitleUrls.isEmpty()) m_subtitles.insert(key, e.subtitleUrls);
            if (s.type == QLatin1String("jellyfin")) m_resume.insert(key, e.resumeMs);
        }
        double progress = 0;
        if (e.durationMs > 0 && e.resumeMs > 0) progress = std::min(1.0, double(e.resumeMs) / double(e.durationMs));
        out << QVariantMap{{"id", e.id}, {"name", e.name}, {"isFolder", e.isFolder}, {"playKey", e.isFolder ? QString() : key},
                           {"sizeLabel", e.sizeBytes > 0 ? LibraryRules::sizeLabel(e.sizeBytes) : QString()},
                           {"durationLabel", displayDuration(e.durationMs)}, {"durationMs", e.durationMs},
                           {"imageUrl", e.imageUrl}, {"subtitle", e.subtitle}, {"progress", progress},
                           {"played", e.played}, {"kind", e.kind}};
    }
    return out;
}

int NetworkController::browse(const QString &id, const QString &path)
{
    const int req = m_nextRequest++;
    const Server s = serverById(id);
    if (s.id.isEmpty()) {
        QTimer::singleShot(0, this, [=, this] { emit browseDone(req, {}, QStringLiteral("This source was removed")); });
        return req;
    }
    if (s.type == QLatin1String("jellyfin")) browseJellyfin(req, s, path);
    else if (s.type == QLatin1String("nextcloud") || s.type == QLatin1String("webdav")) browseDav(req, s, path);
    else if (s.type == QLatin1String("dlna")) browseDlna(req, s, path);
    else if (s.type == QLatin1String("url")) {
        RemoteEntry e;
        e.id = s.address;
        e.name = s.name;
        e.playKey = s.address;
        e.kind = QStringLiteral("video");
        e.subtitle = QStringLiteral("Stream link");
        const QVariantList list = toVariant(s, {e});
        QTimer::singleShot(0, this, [=, this] { emit browseDone(req, list, {}); });
    } else browseShare(req, s, path);
    return req;
}

void NetworkController::browseJellyfin(int req, const Server &s, const QString &path)
{
    const QString token = secretFor(s.id);
    const QString user = QString::fromUtf8(QUrl::toPercentEncoding(s.userId));
    QString url;
    if (path.isEmpty()) url = s.base + QStringLiteral("/Users/%1/Views").arg(user);
    else if (path.startsWith(QLatin1String("search:")))
        url = s.base + QStringLiteral("/Users/%1/Items?SearchTerm=%2&Recursive=true&Limit=60&IncludeItemTypes=Movie,Series,Episode,Video"
                                      "&Fields=MediaSources,ProductionYear,RunTimeTicks,UserData").arg(user, QString::fromUtf8(QUrl::toPercentEncoding(path.mid(7))));
    else
        url = s.base + QStringLiteral("/Users/%1/Items?ParentId=%2&SortBy=SortName&SortOrder=Ascending&Limit=400"
                                      "&Fields=MediaSources,ProductionYear,RunTimeTicks,UserData").arg(user, QString::fromUtf8(QUrl::toPercentEncoding(path)));
    request("GET", url, jellyfinHeaders(token), {}, [=, this](int st, const QByteArray &body, const QString &err) {
        if (st != 200) { emit browseDone(req, {}, st == 401 ? QStringLiteral("Signed out: edit this server and sign in again") : (err.isEmpty() ? jellyfinError(st) : err)); return; }
        emit browseDone(req, toVariant(s, parseJellyfinItems(body, s.base, s.id)), {});
    });
}

int NetworkController::search(const QString &id, const QString &query)
{
    return browse(id, QStringLiteral("search:") + query);
}

int NetworkController::jellyfinHome(const QString &id)
{
    const int req = m_nextRequest++;
    const Server s = serverById(id);
    const QString token = secretFor(s.id);
    const QString user = QString::fromUtf8(QUrl::toPercentEncoding(s.userId));
    const QString fields = QStringLiteral("&Fields=MediaSources,ProductionYear,RunTimeTicks,UserData");
    struct Row { QString title, url; };
    const QList<Row> rows{
        {QStringLiteral("Continue watching"), s.base + QStringLiteral("/Users/%1/Items/Resume?Limit=16&MediaTypes=Video").arg(user) + fields},
        {QStringLiteral("Next up"), s.base + QStringLiteral("/Shows/NextUp?UserId=%1&Limit=16").arg(user) + fields},
        {QStringLiteral("Recently added"), s.base + QStringLiteral("/Users/%1/Items/Latest?Limit=16&IncludeItemTypes=Movie,Episode,Video").arg(user) + fields}};
    auto results = std::make_shared<QVector<QVariantList>>(rows.size());
    auto pending = std::make_shared<int>(int(rows.size()));
    auto firstError = std::make_shared<QString>();
    for (int i = 0; i < rows.size(); ++i) {
        request("GET", rows[i].url, jellyfinHeaders(token), {}, [=, this](int st, const QByteArray &body, const QString &err) {
            if (st == 200) (*results)[i] = toVariant(s, parseJellyfinItems(body, s.base, s.id));
            else if (firstError->isEmpty()) *firstError = err.isEmpty() ? jellyfinError(st) : err;
            if (--*pending > 0) return;
            QVariantList sections;
            for (int j = 0; j < rows.size(); ++j)
                if (!(*results)[j].isEmpty()) sections << QVariantMap{{"title", rows[j].title}, {"entries", (*results)[j]}};
            emit homeDone(req, sections, sections.isEmpty() ? *firstError : QString());
        });
    }
    return req;
}

void NetworkController::browseDav(int req, const Server &s, const QString &path)
{
    const QString url = path.isEmpty() ? s.base : path;
    QList<QPair<QByteArray, QByteArray>> h{{"Depth", "1"}, {"Content-Type", "application/xml"}};
    if (!s.user.isEmpty()) h << qMakePair(QByteArray("Authorization"), basicAuth(s.user, secretFor(s.id)).toUtf8());
    request("PROPFIND", url, h, propfindBody(), [=, this](int st, const QByteArray &body, const QString &err) {
        if (st != 207 && st != 200) { emit browseDone(req, {}, st == 401 ? QStringLiteral("Wrong user name or password") : (err.isEmpty() ? QStringLiteral("Server answered %1").arg(st) : err)); return; }
        QList<RemoteEntry> items;
        for (auto e : parseMultistatus(body, url)) {
            if (!e.isFolder && !isPlayableName(e.name)) continue;
            if (!e.isFolder) e.subtitle = e.name.section(u'.', -1).toUpper();
            items << e;
        }
        emit browseDone(req, toVariant(s, items), {});
    });
}

void NetworkController::browseDlna(int req, const Server &s, const QString &path)
{
    const QString objectId = path.isEmpty() ? QStringLiteral("0") : path;
    auto all = std::make_shared<QList<RemoteEntry>>();
    auto page = std::make_shared<std::function<void(int)>>();
    *page = [=, this](int start) {
        request("POST", s.controlUrl,
                {{"Content-Type", "text/xml; charset=\"utf-8\""}, {"SOAPAction", "\"urn:schemas-upnp-org:service:ContentDirectory:1#Browse\""}},
                browseBody(objectId, start, 200), [=, this](int st, const QByteArray &body, const QString &err) {
            const BrowseResult b = parseBrowseResponse(body);
            if (!b.fault.isEmpty() || (st != 200 && b.entries.isEmpty())) {
                emit browseDone(req, {}, !b.fault.isEmpty() ? QStringLiteral("The server refused to list this folder: ") + b.fault
                                                             : (err.isEmpty() ? QStringLiteral("Server answered %1").arg(st) : err));
                return;
            }
            *all += b.entries;
            const int next = start + b.returned;
            if (b.returned > 0 && next < b.total && next < 5000) { (*page)(next); return; }
            emit browseDone(req, toVariant(s, *all), {});
            *page = nullptr; // break the self-reference
        });
    };
    (*page)(0);
}

void NetworkController::browseShare(int req, const Server &s, const QString &path)
{
    const ShareLogin login = loginFor(s);
    QPointer<NetworkController> self(this);
    QThreadPool::globalInstance()->start([self, login, path, s, req] {
        QString error;
        const QList<RemoteEntry> entries = ShareFs::list(login, path, &error);
        if (!self) return;
        QMetaObject::invokeMethod(self, [self, entries, error, s, req] {
            if (!self) return;
            emit self->browseDone(req, self->toVariant(s, entries), error);
        }, Qt::QueuedConnection);
    });
}

// ---- discovery --------------------------------------------------------------------

void NetworkController::discover()
{
    if (m_discovering) return;
    m_discovering = true;
    m_discovered.clear();
    emit discoveredChanged();
    delete m_ssdp;
    m_ssdp = new QUdpSocket(this);
    m_ssdp->bind(QHostAddress::AnyIPv4, 0);
    auto seen = std::make_shared<QSet<QString>>();
    connect(m_ssdp, &QUdpSocket::readyRead, this, [this, seen] {
        while (m_ssdp && m_ssdp->hasPendingDatagrams()) {
            QByteArray packet(int(m_ssdp->pendingDatagramSize()), Qt::Uninitialized);
            m_ssdp->readDatagram(packet.data(), packet.size());
            const QString location = ssdpLocation(packet, QStringLiteral("MediaServer"));
            if (location.isEmpty() || seen->contains(location)) continue;
            seen->insert(location);
            request("GET", location, {}, {}, [this, location](int st, const QByteArray &xml, const QString &) {
                if (st != 200) return;
                const auto dev = parseDeviceDescription(xml, location, QStringLiteral("ContentDirectory"));
                if (!dev) return;
                {
                    QReadLocker r(&m_lock);
                    for (const auto &s : m_servers) if (s.type == QLatin1String("dlna") && s.address == location) return;
                }
                m_discovered << QVariantMap{{"name", dev->name}, {"location", location}, {"host", QUrl(location).host()}, {"type", "dlna"}};
                emit discoveredChanged();
            }, 5000);
        }
    });
    const QByteArray search = ssdpSearch(QStringLiteral("urn:schemas-upnp-org:device:MediaServer:1"));
    for (int i = 0; i < 2; ++i)
        QTimer::singleShot(i * 700, this, [this, search] { if (m_ssdp) m_ssdp->writeDatagram(search, QHostAddress(QStringLiteral("239.255.255.250")), 1900); });
    QTimer::singleShot(4000, this, [this] {
        m_discovering = false;
        emit discoveredChanged();
    });
}

void NetworkController::addDiscovered(int index)
{
    const QVariantMap d = m_discovered.value(index).toMap();
    if (d.isEmpty()) return;
    saveServer({{"type", "dlna"}, {"address", d.value("location")}, {"name", d.value("name")}});
    m_discovered.removeAt(index);
    emit discoveredChanged();
}

// ---- keys and playback ----------------------------------------------------------

bool NetworkController::isNetworkKey(const QString &location) const
{
    return location.startsWith(kNetScheme) || location.startsWith(QLatin1String("jellyfin://"));
}

QString NetworkController::titleFor(const QString &location) const
{
    if (!isNetworkKey(location) && !location.startsWith(QLatin1String("http"))) return {};
    QMutexLocker l(&m_cacheLock);
    const QString t = m_titles.value(location);
    if (!t.isEmpty() || !location.startsWith(kNetScheme)) return t;
    // Share keys carry their path: the file name is the title
    return lastSegment(QUrl::fromPercentEncoding(location.mid(kNetScheme.size()).section(u'/', 1).toUtf8()));
}

qint64 NetworkController::resumePositionFor(const QString &location) const
{
    QMutexLocker l(&m_cacheLock);
    return m_resume.value(location, 0);
}

ResolvedLocation NetworkController::resolve(const QString &location)
{
    ResolvedLocation out;
    out.title = titleFor(location);
    {
        QMutexLocker l(&m_cacheLock);
        out.subtitles = m_subtitles.value(location);
    }
    if (const auto jf = parseJellyfinKey(location)) {
        const Server s = serverById(jf->first);
        if (s.id.isEmpty()) { out.error = QStringLiteral("This Jellyfin server was removed from CPlay"); return out; }
        out.url = jellyfinStreamUrl(s.base, jf->second);
        out.httpHeaders << QStringLiteral("Authorization: ") + jellyfinAuthHeader(m_deviceId, m_version, secretFor(s.id));
        return out;
    }
    if (location.startsWith(kNetScheme)) {
        const QString rest = location.mid(kNetScheme.size());
        const Server s = serverById(rest.section(u'/', 0, 0));
        const QString path = QUrl::fromPercentEncoding(rest.section(u'/', 1).toUtf8());
        if (s.id.isEmpty()) { out.error = QStringLiteral("This network source was removed from CPlay"); return out; }
        if (s.type == QLatin1String("nextcloud") || s.type == QLatin1String("webdav")) {
            out.url = path;
            if (!s.user.isEmpty()) out.httpHeaders << QStringLiteral("Authorization: ") + basicAuth(s.user, secretFor(s.id));
            return out;
        }
        out.url = m_local->publish(ShareFs::sourceFor(loginFor(s), path), lastSegment(path));
        if (out.url.isEmpty()) out.error = QStringLiteral("Could not start CPlay's local streaming server");
        return out;
    }
    out.url = location.startsWith(QLatin1String("file://")) ? QUrl(location).toLocalFile() : location;
    out.title.clear();
    return out;
}

QString NetworkController::publishForTv(const QString &location, const QString &tvHost, QString *mime)
{
    // TVs can't send our login headers: logged-in sources go through the LAN server
    // (CPlay adds the login on the way), so no password or token leaves this PC
    const QHostAddress peer(tvHost);
    if (location.startsWith(QLatin1String("jellyfin://")) || location.startsWith(kNetScheme)) {
        const ResolvedLocation r = resolve(location);
        if (!r.error.isEmpty() || r.url.isEmpty()) return {};
        const QString name = titleFor(location);
        if (mime) *mime = mimeFor(location.startsWith(kNetScheme) ? name : QStringLiteral("x.mp4"));
        if (location.startsWith(kNetScheme)) {
            const QString rest = location.mid(kNetScheme.size());
            const Server s = serverById(rest.section(u'/', 0, 0));
            const QString path = QUrl::fromPercentEncoding(rest.section(u'/', 1).toUtf8());
            // Share file (SMB, FTP, NFS): publish the same source on the LAN server
            if (s.type == QLatin1String("smb") || s.type == QLatin1String("ftp") || s.type == QLatin1String("nfs"))
                return m_lan->publish(ShareFs::sourceFor(loginFor(s), path), lastSegment(path), peer);
        }
        QList<QPair<QByteArray, QByteArray>> headers;
        for (const QString &h : r.httpHeaders) headers << qMakePair(h.section(u':', 0, 0).trimmed().toUtf8(), h.section(u':', 1).trimmed().toUtf8());
        const QString url = r.url;
        return m_lan->publish([url, headers] { return makeHttpSource(url, headers); }, name.isEmpty() ? QStringLiteral("video.mp4") : name, peer);
    }
    if (MediaFiles::isUrl(location)) {
        if (mime) *mime = mimeFor(location.section(u'?', 0, 0));
        return location;
    }
    if (mime) *mime = mimeFor(location);
    return m_lan->publish([location] { return makeFileSource(location); }, QFileInfo(location).fileName(), peer);
}

void NetworkController::reportPlayback(const QString &location, qint64 positionMs, bool paused, const QString &event)
{
    const auto jf = parseJellyfinKey(location);
    if (!jf) return;
    const Server s = serverById(jf->first);
    if (s.id.isEmpty()) return;
    QString session = m_playSessions.value(location);
    if (event == QLatin1String("start") || session.isEmpty()) {
        session = QUuid::createUuid().toString(QUuid::WithoutBraces).remove(u'-');
        m_playSessions.insert(location, session);
    }
    const QString path = event == QLatin1String("start") ? QStringLiteral("/Sessions/Playing")
                       : event == QLatin1String("stop") ? QStringLiteral("/Sessions/Playing/Stopped")
                                                        : QStringLiteral("/Sessions/Playing/Progress");
    request("POST", s.base + path, jellyfinHeaders(secretFor(s.id)), jellyfinPlaybackBody(jf->second, positionMs, paused, session).toUtf8(),
            [](int, const QByteArray &, const QString &) {});
    {
        QMutexLocker l(&m_cacheLock);
        m_resume.insert(location, event == QLatin1String("stop") ? positionMs : m_resume.value(location));
    }
    if (event == QLatin1String("stop")) m_playSessions.remove(location);
}
