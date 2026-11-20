#include "NetRules.h"
#include "LibraryRules.h"
#include "MediaFiles.h"
#include "TimeFormat.h"

#include <QJsonArray>
#include <QJsonDocument>
#include <QJsonObject>
#include <QRegularExpression>
#include <QUrl>
#include <QXmlStreamReader>
#include <algorithm>

namespace NetRules {

// ---- addresses -------------------------------------------------------------

static QStringList segments(const QString &path)
{
    return QString(path).replace(u'\\', u'/').split(u'/', Qt::SkipEmptyParts);
}

static QPair<QString, int> hostPort(const QString &hp)
{
    // "[::1]:445", "nas:4445", "nas"
    if (hp.startsWith(u'[')) {
        const int close = hp.indexOf(u']');
        const QString host = hp.mid(1, close - 1);
        const int colon = hp.indexOf(u':', close);
        return {host, colon > 0 ? hp.mid(colon + 1).toInt() : 0};
    }
    const int colon = hp.lastIndexOf(u':');
    if (colon > 0 && hp.indexOf(u':') == colon) return {hp.left(colon), hp.mid(colon + 1).toInt()};
    return {hp, 0};
}

static QString stripScheme(QString s, const QString &scheme)
{
    s = s.trimmed();
    if (s.startsWith(scheme + QStringLiteral("://"), Qt::CaseInsensitive)) s = s.mid(scheme.size() + 3);
    return s;
}

ShareAddress parseSmb(const QString &input)
{
    QString s = stripScheme(input, QStringLiteral("smb"));
    s.replace(u'\\', u'/');
    while (s.startsWith(u'/')) s.remove(0, 1);
    QStringList parts = s.split(u'/', Qt::SkipEmptyParts);
    ShareAddress a;
    if (parts.isEmpty()) return a;
    const auto hp = hostPort(parts.takeFirst());
    a.host = hp.first;
    a.port = hp.second > 0 ? hp.second : 445;
    if (!parts.isEmpty()) a.share = parts.takeFirst();
    a.path = parts.join(u'/');
    return a;
}

ShareAddress parseFtp(const QString &input)
{
    QString s = stripScheme(input, QStringLiteral("ftp"));
    s.replace(u'\\', u'/');
    while (s.startsWith(u'/')) s.remove(0, 1);
    QStringList parts = s.split(u'/', Qt::SkipEmptyParts);
    ShareAddress a;
    if (parts.isEmpty()) return a;
    QString first = parts.takeFirst();
    if (first.contains(u'@')) first = first.mid(first.lastIndexOf(u'@') + 1); // never keep user:pass@
    const auto hp = hostPort(first);
    a.host = hp.first;
    a.port = hp.second > 0 ? hp.second : 21;
    a.path = parts.join(u'/');
    return a;
}

ShareAddress parseNfs(const QString &input, const QString &exportHint)
{
    QString s = input.trimmed();
    ShareAddress a;
    QString rest;
    if (s.startsWith(QStringLiteral("nfs://"), Qt::CaseInsensitive)) {
        s = s.mid(6);
        const int slash = s.indexOf(u'/');
        const auto hp = hostPort(slash < 0 ? s : s.left(slash));
        a.host = hp.first;
        a.port = hp.second;
        rest = slash < 0 ? QString() : s.mid(slash);
    } else if (s.contains(QStringLiteral(":/"))) {
        const int colon = s.indexOf(QStringLiteral(":/"));
        a.host = s.left(colon);
        rest = s.mid(colon + 1);
    } else {
        s.replace(u'\\', u'/');
        while (s.startsWith(u'/')) s.remove(0, 1);
        const int slash = s.indexOf(u'/');
        a.host = slash < 0 ? s : s.left(slash);
        rest = slash < 0 ? QString() : s.mid(slash);
    }
    const QStringList parts = segments(rest);
    QString hint = exportHint.trimmed();
    if (!hint.isEmpty()) {
        if (!hint.startsWith(u'/')) hint.prepend(u'/');
        a.share = QStringLiteral("/") + segments(hint).join(u'/');
        const QStringList hp = segments(hint);
        a.path = (parts.mid(0, hp.size()) == hp ? parts.mid(hp.size()) : parts).join(u'/');
    } else if (!parts.isEmpty()) {
        a.share = u'/' + parts.first();
        a.path = parts.mid(1).join(u'/');
    }
    return a;
}

QString joinPath(const QString &folder, const QString &name)
{
    const QStringList p = segments(folder) + segments(name);
    return p.join(u'/');
}

QString parentPath(const QString &path)
{
    QStringList p = segments(path);
    if (!p.isEmpty()) p.removeLast();
    return p.join(u'/');
}

// ---- Jellyfin --------------------------------------------------------------

QString jellyfinServerUrl(const QString &input)
{
    QString s = input.trimmed();
    while (s.endsWith(u'/')) s.chop(1);
    if (s.isEmpty()) return {};
    static const QRegularExpression scheme(QStringLiteral("^[a-zA-Z][a-zA-Z0-9+.-]*://"));
    static const QRegularExpression port8096(QStringLiteral(":8096(/|$)"));
    if (!scheme.match(s).hasMatch()) s = (port8096.match(s).hasMatch() ? QStringLiteral("http://") : QStringLiteral("https://")) + s;
    s = s.section(u'#', 0, 0);
    static const QRegularExpression web(QStringLiteral("/web(/.*)?$"), QRegularExpression::CaseInsensitiveOption);
    s.remove(web);
    while (s.endsWith(u'/')) s.chop(1);
    return s;
}

QString jellyfinAuthHeader(const QString &deviceId, const QString &version, const QString &token)
{
    auto q = [](QString v) { return v.replace(u'"', u'\''); };
    QStringList parts{QStringLiteral("Client=\"CPlay\""), QStringLiteral("Device=\"Windows\""),
                      QStringLiteral("DeviceId=\"%1\"").arg(q(deviceId)), QStringLiteral("Version=\"%1\"").arg(q(version))};
    if (!token.isEmpty()) parts << QStringLiteral("Token=\"%1\"").arg(q(token));
    return QStringLiteral("MediaBrowser ") + parts.join(QStringLiteral(", "));
}

QString jellyfinAuthBody(const QString &user, const QString &password)
{
    return QString::fromUtf8(QJsonDocument(QJsonObject{{"Username", user}, {"Pw", password}}).toJson(QJsonDocument::Compact));
}

std::optional<JellyfinAuth> parseJellyfinAuth(const QByteArray &json)
{
    const QJsonObject o = QJsonDocument::fromJson(json).object();
    const QString token = o.value(QStringLiteral("AccessToken")).toString();
    const QJsonObject user = o.value(QStringLiteral("User")).toObject();
    if (token.isEmpty() || user.isEmpty()) return std::nullopt;
    JellyfinAuth a;
    a.token = token;
    a.userId = user.value(QStringLiteral("Id")).toString();
    a.userName = user.value(QStringLiteral("Name")).toString();
    a.serverId = o.value(QStringLiteral("ServerId")).toString();
    if (a.serverId.isEmpty()) a.serverId = user.value(QStringLiteral("ServerId")).toString();
    return a;
}

QString jellyfinError(int status)
{
    switch (status) {
    case 400: return QStringLiteral("The server rejected the request");
    case 401: return QStringLiteral("Wrong user name or password");
    case 403: return QStringLiteral("This user is not allowed to use this server");
    case 404: return QStringLiteral("Not a Jellyfin server at this address");
    default: break;
    }
    if (status >= 500 && status < 600) return QStringLiteral("The server had a problem (%1). Try again later").arg(status);
    return QStringLiteral("Server answered %1").arg(status);
}

qint64 ticksToMs(qint64 ticks) { return ticks / 10000; }
qint64 msToTicks(qint64 ms) { return ms * 10000; }

QString jellyfinItemKey(const QString &serverId, const QString &itemId)
{
    return QStringLiteral("jellyfin://") + serverId + u'/' + itemId;
}

std::optional<QPair<QString, QString>> parseJellyfinKey(const QString &key)
{
    if (!key.startsWith(QLatin1String("jellyfin://"))) return std::nullopt;
    const QString rest = key.mid(11);
    const int slash = rest.indexOf(u'/');
    if (slash <= 0 || slash == rest.size() - 1) return std::nullopt;
    return QPair<QString, QString>{rest.left(slash), rest.mid(slash + 1)};
}

static const QStringList &playableTypes()
{
    static const QStringList t{QStringLiteral("Movie"), QStringLiteral("Episode"), QStringLiteral("Video"),
                               QStringLiteral("MusicVideo"), QStringLiteral("Trailer"), QStringLiteral("Audio"),
                               QStringLiteral("TvChannel")};
    return t;
}

QList<RemoteEntry> parseJellyfinItems(const QByteArray &json, const QString &base, const QString &serverId)
{
    const QJsonDocument doc = QJsonDocument::fromJson(json);
    const QJsonArray array = doc.isArray() ? doc.array() : doc.object().value(QStringLiteral("Items")).toArray();
    QList<RemoteEntry> out;
    for (const auto &value : array) {
        const QJsonObject o = value.toObject();
        const QString id = o.value(QStringLiteral("Id")).toString();
        if (id.isEmpty()) continue;
        const QString type = o.value(QStringLiteral("Type")).toString();
        const bool playable = !o.value(QStringLiteral("IsFolder")).toBool() && playableTypes().contains(type);
        const QJsonObject user = o.value(QStringLiteral("UserData")).toObject();
        RemoteEntry e;
        e.id = id;
        QString name = o.value(QStringLiteral("Name")).toString();
        if (name.isEmpty()) name = QStringLiteral("Untitled");
        const bool hasEpisode = o.contains(QStringLiteral("IndexNumber"));
        const int year = o.value(QStringLiteral("ProductionYear")).toInt();
        if (type == QLatin1String("Episode") && hasEpisode)
            name = QStringLiteral("S%1E%2 · %3")
                       .arg(o.value(QStringLiteral("ParentIndexNumber")).toInt(), 2, 10, QLatin1Char('0'))
                       .arg(o.value(QStringLiteral("IndexNumber")).toInt(), 2, 10, QLatin1Char('0'))
                       .arg(name);
        else if (type == QLatin1String("Movie") && year > 0)
            name = QStringLiteral("%1 (%2)").arg(name).arg(year);
        e.name = name;
        e.isFolder = !playable;
        e.playKey = playable ? jellyfinItemKey(serverId, id) : QString();
        e.durationMs = ticksToMs(qint64(o.value(QStringLiteral("RunTimeTicks")).toDouble()));
        e.resumeMs = ticksToMs(qint64(user.value(QStringLiteral("PlaybackPositionTicks")).toDouble()));
        e.played = user.value(QStringLiteral("Played")).toBool();
        const QString tag = o.value(QStringLiteral("ImageTags")).toObject().value(QStringLiteral("Primary")).toString();
        if (!tag.isEmpty())
            e.imageUrl = base + QStringLiteral("/Items/") + QString::fromUtf8(QUrl::toPercentEncoding(id)) +
                         QStringLiteral("/Images/Primary?maxWidth=400&quality=85&tag=") + QString::fromUtf8(QUrl::toPercentEncoding(tag));
        QStringList sub;
        const QString series = o.value(QStringLiteral("SeriesName")).toString();
        if (type == QLatin1String("Episode") && !series.isEmpty()) sub << series;
        if (type != QLatin1String("Episode") && year > 0) sub << QString::number(year);
        const QString collection = o.value(QStringLiteral("CollectionType")).toString();
        if (!playable && collection.isEmpty() && !type.isEmpty()) sub << type;
        e.subtitle = sub.join(QStringLiteral(" · "));
        e.kind = collection.isEmpty() ? type : collection;
        // External text subtitles of the first media source
        const QJsonObject source = o.value(QStringLiteral("MediaSources")).toArray().first().toObject();
        const QString sourceId = source.value(QStringLiteral("Id")).toString();
        for (const auto &s : source.value(QStringLiteral("MediaStreams")).toArray()) {
            const QJsonObject st = s.toObject();
            if (st.value(QStringLiteral("Type")).toString() == QLatin1String("Subtitle") && st.value(QStringLiteral("IsExternal")).toBool() &&
                st.value(QStringLiteral("IsTextSubtitleStream")).toBool(true) && !sourceId.isEmpty())
                e.subtitleUrls << QStringLiteral("%1/Videos/%2/%3/Subtitles/%4/Stream.srt").arg(base, id, sourceId).arg(st.value(QStringLiteral("Index")).toInt());
        }
        out << e;
    }
    return out;
}

QString jellyfinStreamUrl(const QString &base, const QString &itemId)
{
    const QString id = QString::fromUtf8(QUrl::toPercentEncoding(itemId));
    // The token goes in the X-Emby-Token header, never in the URL
    return base + QStringLiteral("/Videos/") + id + QStringLiteral("/stream?static=true&MediaSourceId=") + id;
}

QString jellyfinPlaybackBody(const QString &itemId, qint64 positionMs, bool paused, const QString &playSessionId)
{
    const QJsonObject o{{"ItemId", itemId}, {"MediaSourceId", itemId}, {"PositionTicks", double(msToTicks(std::max<qint64>(0, positionMs)))},
                        {"IsPaused", paused}, {"CanSeek", true}, {"PlaySessionId", playSessionId}, {"PlayMethod", "DirectPlay"}};
    return QString::fromUtf8(QJsonDocument(o).toJson(QJsonDocument::Compact));
}

bool shouldReportProgress(qint64 lastReportMs, qint64 nowMs, bool stateChanged)
{
    return stateChanged || nowMs - lastReportMs >= 10000;
}

// ---- WebDAV ----------------------------------------------------------------

static QString ensureScheme(const QString &input)
{
    static const QRegularExpression scheme(QStringLiteral("^[a-zA-Z][a-zA-Z0-9+.-]*://"));
    return scheme.match(input).hasMatch() ? input : QStringLiteral("https://") + input;
}

QString nextcloudDavRoot(const QString &serverInput, const QString &user)
{
    const QString s = ensureScheme(serverInput.trimmed());
    static const QRegularExpression existing(QStringLiteral("/remote\\.php/(dav/files/[^/]+|webdav)/?"), QRegularExpression::CaseInsensitiveOption);
    const auto m = existing.match(s);
    if (m.hasMatch()) {
        QString root = s.left(m.capturedEnd());
        while (root.endsWith(u'/')) root.chop(1);
        return root + u'/';
    }
    QString base = s.section(u'?', 0, 0);
    static const QRegularExpression index(QStringLiteral("/index\\.php.*$"), QRegularExpression::CaseInsensitiveOption);
    static const QRegularExpression apps(QStringLiteral("/(apps|login|s)/.*$"), QRegularExpression::CaseInsensitiveOption);
    base.remove(index);
    base.remove(apps);
    while (base.endsWith(u'/')) base.chop(1);
    return base + QStringLiteral("/remote.php/dav/files/") + QString::fromUtf8(QUrl::toPercentEncoding(user)) + u'/';
}

QString webDavRoot(const QString &input)
{
    QString s = ensureScheme(input.trimmed()).section(u'#', 0, 0);
    while (s.endsWith(u'/')) s.chop(1);
    return s + u'/';
}

QByteArray propfindBody()
{
    return QByteArrayLiteral(
        "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
        "<d:propfind xmlns:d=\"DAV:\" xmlns:oc=\"http://owncloud.org/ns\">\n"
        "  <d:prop><d:displayname/><d:resourcetype/><d:getcontentlength/><d:getcontenttype/><d:getlastmodified/><oc:size/></d:prop>\n"
        "</d:propfind>");
}

QString basicAuth(const QString &user, const QString &password)
{
    return QStringLiteral("Basic ") + QString::fromLatin1((user + u':' + password).toUtf8().toBase64());
}

QList<RemoteEntry> parseMultistatus(const QByteArray &xml, const QString &requestUrl)
{
    const QUrl request(requestUrl);
    auto normPath = [](QString p) {
        p = QUrl::fromPercentEncoding(p.toUtf8());
        while (p.size() > 1 && p.endsWith(u'/')) p.chop(1);
        return p;
    };
    const QString requestPath = normPath(request.path(QUrl::FullyEncoded));
    QList<RemoteEntry> out;
    QXmlStreamReader r(xml);
    struct Resp { QString href; bool folder = false; qint64 size = 0; QString type, display; } cur;
    bool inResponse = false, okStatus = true;
    // props are collected per propstat and only kept when its status is 200
    struct Props { bool folder = false; qint64 size = -1; QString type, display; } props;
    while (!r.atEnd()) {
        r.readNext();
        const QString name = r.name().toString();
        if (r.isStartElement()) {
            if (name == QLatin1String("response")) { inResponse = true; cur = {}; }
            else if (!inResponse) continue;
            else if (name == QLatin1String("href")) cur.href = r.readElementText().trimmed();
            else if (name == QLatin1String("propstat")) { props = {}; okStatus = true; }
            else if (name == QLatin1String("collection")) props.folder = true;
            else if (name == QLatin1String("getcontentlength")) props.size = r.readElementText().trimmed().toLongLong();
            else if (name == QLatin1String("size") && props.size <= 0) props.size = r.readElementText().trimmed().toLongLong();
            else if (name == QLatin1String("getcontenttype")) props.type = r.readElementText().trimmed();
            else if (name == QLatin1String("displayname")) props.display = r.readElementText().trimmed();
            else if (name == QLatin1String("status")) okStatus = r.readElementText().contains(QLatin1String(" 200"));
        } else if (r.isEndElement()) {
            if (name == QLatin1String("propstat") && okStatus) {
                cur.folder = cur.folder || props.folder;
                if (props.size > 0) cur.size = props.size;
                if (!props.type.isEmpty()) cur.type = props.type;
                if (!props.display.isEmpty()) cur.display = props.display;
            } else if (name == QLatin1String("response") && inResponse) {
                inResponse = false;
                const QUrl href = request.resolved(QUrl(cur.href));
                const QString rawPath = href.path(QUrl::FullyEncoded);
                if (normPath(rawPath) == requestPath || cur.href.isEmpty()) continue;
                const bool folder = cur.folder || rawPath.endsWith(u'/');
                QString raw = rawPath;
                while (raw.endsWith(u'/')) raw.chop(1);
                const QString decoded = QUrl::fromPercentEncoding(raw.section(u'/', -1).toUtf8());
                const QString shown = cur.display.isEmpty() ? decoded : cur.display;
                if (shown.startsWith(u'.')) continue;
                RemoteEntry e;
                QUrl abs = request;
                abs.setPath(QUrl::fromPercentEncoding(rawPath.toUtf8()));
                abs.setQuery(QString());
                abs.setFragment(QString());
                e.id = abs.toString(QUrl::FullyEncoded);
                e.name = shown;
                e.isFolder = folder;
                e.sizeBytes = cur.size;
                e.kind = folder ? QStringLiteral("folder") : (cur.type.startsWith(QLatin1String("audio")) ? QStringLiteral("audio") : QStringLiteral("video"));
                out << e;
            }
        }
    }
    std::stable_sort(out.begin(), out.end(), [](const RemoteEntry &a, const RemoteEntry &b) {
        if (a.isFolder != b.isFolder) return a.isFolder;
        return LibraryRules::naturalLess(a.name, b.name);
    });
    return out;
}

// ---- DLNA / UPnP -----------------------------------------------------------

QByteArray ssdpSearch(const QString &searchTarget)
{
    return QStringLiteral("M-SEARCH * HTTP/1.1\r\nHOST: 239.255.255.250:1900\r\nMAN: \"ssdp:discover\"\r\nMX: 2\r\nST: %1\r\n"
                          "USER-AGENT: Windows/10 UPnP/1.1 CPlay/1.0\r\n\r\n").arg(searchTarget).toUtf8();
}

QString ssdpLocation(const QByteArray &packet, const QString &wanted)
{
    QString location, st;
    const QStringList lines = QString::fromUtf8(packet).split(QRegularExpression(QStringLiteral("\r?\n")));
    for (int i = 1; i < lines.size(); ++i) {
        const int colon = lines[i].indexOf(u':');
        if (colon <= 0) continue;
        const QString key = lines[i].left(colon).trimmed().toUpper();
        const QString value = lines[i].mid(colon + 1).trimmed();
        if (key == QLatin1String("LOCATION")) location = value;
        else if (key == QLatin1String("ST") || (key == QLatin1String("NT") && st.isEmpty())) st = value;
    }
    if (!location.startsWith(QLatin1String("http"))) return {};
    if (!st.contains(wanted, Qt::CaseInsensitive)) return {};
    return location;
}

QString resolveUrl(const QString &base, const QString &ref)
{
    return QUrl(base).resolved(QUrl(ref.trimmed())).toString();
}

std::optional<UpnpDevice> parseDeviceDescription(const QByteArray &xml, const QString &location, const QString &serviceType)
{
    QXmlStreamReader r(xml);
    QString base = location;
    struct Dev { UpnpDevice d; bool has = false; };
    QList<Dev> stack;
    QStringList path;
    QString svcType, svcControl;
    std::optional<UpnpDevice> found;
    const QString wanted = QStringLiteral("urn:schemas-upnp-org:service:") + serviceType + u':';
    while (!r.atEnd() && !found) {
        r.readNext();
        const QString name = r.name().toString();
        if (r.isStartElement()) {
            const QString parent = path.isEmpty() ? QString() : path.last();
            path << name;
            if (name == QLatin1String("URLBase")) { const QString b = r.readElementText().trimmed(); if (!b.isEmpty()) base = b; path.removeLast(); }
            else if (name == QLatin1String("device")) stack << Dev{};
            else if (name == QLatin1String("service")) { svcType.clear(); svcControl.clear(); }
            else if (!stack.isEmpty() && parent == QLatin1String("device")) {
                if (name == QLatin1String("friendlyName")) { stack.last().d.name = r.readElementText().trimmed(); path.removeLast(); }
                else if (name == QLatin1String("UDN")) { stack.last().d.udn = r.readElementText().trimmed(); path.removeLast(); }
                else if (name == QLatin1String("manufacturer")) { stack.last().d.manufacturer = r.readElementText().trimmed(); path.removeLast(); }
            } else if (!stack.isEmpty() && parent == QLatin1String("service")) {
                if (name == QLatin1String("serviceType")) { svcType = r.readElementText().trimmed(); path.removeLast(); }
                else if (name == QLatin1String("controlURL")) { svcControl = r.readElementText().trimmed(); path.removeLast(); }
            } else if (!stack.isEmpty() && parent == QLatin1String("icon") && name == QLatin1String("url") && stack.last().d.iconUrl.isEmpty()) {
                stack.last().d.iconUrl = r.readElementText().trimmed();
                path.removeLast();
            }
        } else if (r.isEndElement()) {
            if (!path.isEmpty()) path.removeLast();
            if (name == QLatin1String("service") && !stack.isEmpty() && !svcControl.isEmpty()) {
                if (svcType.startsWith(wanted)) { stack.last().d.controlUrl = svcControl; stack.last().has = true; }
                if (svcType.startsWith(QLatin1String("urn:schemas-upnp-org:service:RenderingControl:")))
                    stack.last().d.renderingControlUrl = svcControl;
            } else if (name == QLatin1String("device") && !stack.isEmpty()) {
                Dev d = stack.takeLast();
                if (d.has) found = d.d;
            }
        }
    }
    if (!found) return std::nullopt;
    UpnpDevice d = *found;
    d.controlUrl = resolveUrl(base, d.controlUrl);
    if (!d.renderingControlUrl.isEmpty()) d.renderingControlUrl = resolveUrl(base, d.renderingControlUrl);
    if (!d.iconUrl.isEmpty()) d.iconUrl = resolveUrl(base, d.iconUrl);
    if (d.name.isEmpty()) d.name = QUrl(location).host();
    return d;
}

QString escapeXml(const QString &s)
{
    QString o = s;
    o.replace(u'&', QStringLiteral("&amp;")).replace(u'<', QStringLiteral("&lt;")).replace(u'>', QStringLiteral("&gt;"))
        .replace(u'"', QStringLiteral("&quot;")).replace(u'\'', QStringLiteral("&apos;"));
    return o;
}

QByteArray soapEnvelope(const QString &serviceType, const QString &action, const QList<QPair<QString, QString>> &args)
{
    QString body;
    for (const auto &[k, v] : args) body += QStringLiteral("<%1>%2</%1>").arg(k, escapeXml(v));
    return QStringLiteral("<?xml version=\"1.0\" encoding=\"utf-8\"?>\n"
                          "<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\">\n"
                          "<s:Body><u:%1 xmlns:u=\"%2\">%3</u:%1></s:Body></s:Envelope>")
        .arg(action, serviceType, body).toUtf8();
}

QByteArray browseBody(const QString &objectId, int start, int count)
{
    return soapEnvelope(QStringLiteral("urn:schemas-upnp-org:service:ContentDirectory:1"), QStringLiteral("Browse"),
                        {{"ObjectID", objectId}, {"BrowseFlag", "BrowseDirectChildren"}, {"Filter", "*"},
                         {"StartingIndex", QString::number(start)}, {"RequestedCount", QString::number(count)}, {"SortCriteria", ""}});
}

qint64 parseUpnpDuration(const QString &value)
{
    static const QRegularExpression re(QStringLiteral("^(\\d+):(\\d{1,2}):(\\d{1,2})(?:\\.(\\d+))?"));
    const auto m = re.match(value.trimmed());
    if (!m.hasMatch()) return 0;
    const qint64 frac = m.capturedLength(4) ? qint64((QStringLiteral("0.") + m.captured(4)).toDouble() * 1000) : 0;
    return ((m.captured(1).toLongLong() * 60 + m.captured(2).toLongLong()) * 60 + m.captured(3).toLongLong()) * 1000 + frac;
}

QString formatUpnpTime(qint64 ms)
{
    const qint64 s = std::max<qint64>(0, ms) / 1000;
    return QStringLiteral("%1:%2:%3").arg(s / 3600).arg((s % 3600) / 60, 2, 10, QLatin1Char('0')).arg(s % 60, 2, 10, QLatin1Char('0'));
}

QString xmlTag(const QByteArray &xml, const QString &localName)
{
    QXmlStreamReader r(xml);
    while (!r.atEnd()) {
        r.readNext();
        if (r.isStartElement() && r.name() == localName) return r.readElementText(QXmlStreamReader::IncludeChildElements).trimmed();
    }
    // Some devices send sloppy XML (undeclared prefixes): fall back to a plain text match
    const QRegularExpression re(QStringLiteral("<(?:\\w+:)?%1(?:\\s[^>]*)?>([^<]*)</(?:\\w+:)?%1>").arg(QRegularExpression::escape(localName)));
    const auto m = re.match(QString::fromUtf8(xml));
    if (!m.hasMatch()) return {};
    QString t = m.captured(1).trimmed();
    t.replace(QStringLiteral("&lt;"), QStringLiteral("<")).replace(QStringLiteral("&gt;"), QStringLiteral(">"))
        .replace(QStringLiteral("&quot;"), QStringLiteral("\"")).replace(QStringLiteral("&apos;"), QStringLiteral("'"))
        .replace(QStringLiteral("&amp;"), QStringLiteral("&"));
    return t;
}

QString soapFault(const QByteArray &xml)
{
    if (!xml.contains("Fault")) return {};
    QString d = xmlTag(xml, QStringLiteral("errorDescription"));
    if (d.isEmpty()) d = xmlTag(xml, QStringLiteral("faultstring"));
    return d.isEmpty() ? QStringLiteral("The device refused the command") : d.left(160);
}

static QList<RemoteEntry> parseDidl(const QString &didl)
{
    QList<RemoteEntry> out;
    QXmlStreamReader r(didl);
    RemoteEntry cur;
    bool inObj = false, isContainer = false;
    QString cls, url, mime;
    while (!r.atEnd()) {
        r.readNext();
        const QString name = r.name().toString();
        if (r.isStartElement()) {
            if ((name == QLatin1String("item") || name == QLatin1String("container")) && !inObj) {
                inObj = true;
                isContainer = name == QLatin1String("container");
                cur = {};
                cur.id = r.attributes().value(QStringLiteral("id")).toString();
                cur.isFolder = isContainer;
                const QString cc = r.attributes().value(QStringLiteral("childCount")).toString();
                if (isContainer && !cc.isEmpty()) cur.subtitle = cc + QStringLiteral(" items");
                cls.clear(); url.clear(); mime.clear();
            } else if (!inObj) {
                continue;
            } else if (name == QLatin1String("title")) {
                cur.name = r.readElementText().trimmed();
            } else if (name == QLatin1String("class")) {
                cls = r.readElementText().trimmed();
            } else if (name == QLatin1String("albumArtURI")) {
                cur.imageUrl = r.readElementText().trimmed();
            } else if (name == QLatin1String("res")) {
                const QString protocol = r.attributes().value(QStringLiteral("protocolInfo")).toString();
                const QString m = protocol.section(u':', 2, 2);
                const QString duration = r.attributes().value(QStringLiteral("duration")).toString();
                const qint64 size = r.attributes().value(QStringLiteral("size")).toLongLong();
                const QString text = r.readElementText().trimmed();
                if (url.isEmpty() && protocol.startsWith(QLatin1String("http-get")) && !m.startsWith(QLatin1String("image/"))) {
                    url = text; mime = m;
                    cur.durationMs = parseUpnpDuration(duration);
                    cur.sizeBytes = size;
                }
                if (m.contains(QLatin1String("srt")) || m.contains(QLatin1String("subrip")) || m.contains(QLatin1String("vtt")))
                    cur.subtitleUrls << text;
            } else if (name == QLatin1String("CaptionInfoEx") || name == QLatin1String("CaptionInfo")) {
                const QString t = r.readElementText().trimmed();
                if (cur.subtitleUrls.isEmpty() && !t.isEmpty()) cur.subtitleUrls << t;
            }
        } else if (r.isEndElement() && inObj && (name == QLatin1String("item") || name == QLatin1String("container"))) {
            inObj = false;
            if (cur.name.isEmpty()) cur.name = cur.id;
            const bool video = cls.startsWith(QLatin1String("object.item.videoItem")) || mime.startsWith(QLatin1String("video/"));
            const bool audio = cls.startsWith(QLatin1String("object.item.audioItem")) || mime.startsWith(QLatin1String("audio/"));
            if (isContainer) {
                cur.kind = QStringLiteral("folder");
                out << cur;
            } else if ((video || audio) && !url.isEmpty()) {
                cur.playKey = url;
                cur.kind = video ? QStringLiteral("video") : QStringLiteral("audio");
                if (cur.subtitle.isEmpty()) cur.subtitle = mime.section(u'/', 1).toUpper();
                out << cur;
            }
        }
    }
    return out;
}

BrowseResult parseBrowseResponse(const QByteArray &xml)
{
    BrowseResult b;
    b.fault = soapFault(xml);
    if (!b.fault.isEmpty()) return b;
    const QString result = xmlTag(xml, QStringLiteral("Result"));
    b.returned = xmlTag(xml, QStringLiteral("NumberReturned")).toInt();
    const QString total = xmlTag(xml, QStringLiteral("TotalMatches"));
    b.total = total.isEmpty() ? b.returned : total.toInt();
    if (!result.isEmpty()) b.entries = parseDidl(result);
    return b;
}

// ---- local media server ----------------------------------------------------

std::optional<ByteRange> parseRange(const QString &header, qint64 size)
{
    if (header.trimmed().isEmpty() || size <= 0) return std::nullopt;
    static const QRegularExpression re(QStringLiteral("^\\s*bytes\\s*=\\s*(\\d*)\\s*-\\s*(\\d*)\\s*$"));
    const auto m = re.match(header.section(u',', 0, 0));
    if (!m.hasMatch()) return std::nullopt;
    const QString a = m.captured(1), b = m.captured(2);
    if (a.isEmpty() && b.isEmpty()) return std::nullopt;
    ByteRange r;
    if (a.isEmpty()) {
        const qint64 n = b.toLongLong();
        if (n <= 0) { r.satisfiable = false; return r; }
        r.start = std::max<qint64>(0, size - n);
        r.end = size - 1;
        return r;
    }
    r.start = a.toLongLong();
    if (r.start >= size) { r.satisfiable = false; return r; }
    r.end = b.isEmpty() ? size - 1 : std::min(b.toLongLong(), size - 1);
    if (r.end < r.start) r.satisfiable = false;
    return r;
}

QString mimeFor(const QString &name)
{
    const QString ext = name.section(u'.', -1).toLower();
    static const QHash<QString, QString> map{
        {"mp4", "video/mp4"}, {"m4v", "video/mp4"}, {"mkv", "video/x-matroska"}, {"webm", "video/webm"},
        {"avi", "video/x-msvideo"}, {"mov", "video/quicktime"}, {"ts", "video/mp2t"}, {"m2ts", "video/mp2t"},
        {"mts", "video/mp2t"}, {"3gp", "video/3gpp"}, {"wmv", "video/x-ms-wmv"}, {"flv", "video/x-flv"},
        {"mpg", "video/mpeg"}, {"mpeg", "video/mpeg"}, {"vob", "video/mpeg"}, {"mp3", "audio/mpeg"},
        {"m4a", "audio/mp4"}, {"aac", "audio/mp4"}, {"flac", "audio/flac"}, {"ogg", "audio/ogg"},
        {"opus", "audio/ogg"}, {"wav", "audio/wav"}, {"srt", "application/x-subrip"}, {"vtt", "text/vtt"},
        {"ass", "text/x-ssa"}, {"ssa", "text/x-ssa"}};
    return map.value(ext, QStringLiteral("video/mp4"));
}

bool isValidToken(const QString &token)
{
    if (token.size() < 16 || token.size() > 64) return false;
    return std::all_of(token.begin(), token.end(), [](QChar c) { return c.isLetterOrNumber() && c.unicode() < 128; });
}

// ---- FTP -------------------------------------------------------------------

std::optional<FtpEntry> parseMlsdLine(const QString &line)
{
    // "type=file;size=1234;modify=20240101;UNIX.mode=0644; name.mkv"
    const int space = line.indexOf(u' ');
    if (space <= 0) return std::nullopt;
    FtpEntry e;
    e.name = line.mid(space + 1);
    if (e.name.isEmpty()) return std::nullopt;
    QString type;
    for (const QString &fact : line.left(space).split(u';', Qt::SkipEmptyParts)) {
        const QString key = fact.section(u'=', 0, 0).toLower();
        const QString value = fact.section(u'=', 1);
        if (key == QLatin1String("type")) type = value.toLower();
        else if (key == QLatin1String("size")) e.size = value.toLongLong();
    }
    if (type == QLatin1String("cdir") || type == QLatin1String("pdir") || e.name == QLatin1String(".") || e.name == QLatin1String("..")) return std::nullopt;
    e.isDir = type == QLatin1String("dir");
    if (!e.isDir && type != QLatin1String("file") && !type.isEmpty() && !type.startsWith(QLatin1String("os.unix=sym"))) return std::nullopt;
    return e;
}

std::optional<FtpEntry> parseListLine(const QString &line)
{
    // Unix: "drwxr-xr-x 1 owner group 4096 Jan 01 12:00 name with spaces"
    static const QRegularExpression unixRe(QStringLiteral("^([\\-dlbcps])[rwxsStT\\-]{9}\\S*\\s+\\d+\\s+\\S+\\s+\\S+\\s+(\\d+)\\s+\\w{3}\\s+\\d{1,2}\\s+[\\d:]{4,5}\\s+(.+)$"));
    // DOS: "01-02-24  10:30AM       <DIR>          Films" / "01-02-24  10:30AM  12345 a.mkv"
    static const QRegularExpression dosRe(QStringLiteral("^\\d{2}-\\d{2}-\\d{2,4}\\s+\\d{1,2}:\\d{2}[AaPp][Mm]\\s+(<DIR>|\\d+)\\s+(.+)$"));
    FtpEntry e;
    auto m = unixRe.match(line);
    if (m.hasMatch()) {
        const QChar t = m.captured(1).at(0);
        e.isDir = t == u'd';
        e.size = m.captured(2).toLongLong();
        e.name = m.captured(3);
        if (t == u'l') e.name = e.name.section(QStringLiteral(" -> "), 0, 0);
    } else {
        m = dosRe.match(line);
        if (!m.hasMatch()) return std::nullopt;
        e.isDir = m.captured(1) == QLatin1String("<DIR>");
        e.size = e.isDir ? 0 : m.captured(1).toLongLong();
        e.name = m.captured(2);
    }
    if (e.name == QLatin1String(".") || e.name == QLatin1String("..")) return std::nullopt;
    return e;
}

int parsePasvPort(const QString &reply)
{
    static const QRegularExpression re(QStringLiteral("(\\d+),(\\d+),(\\d+),(\\d+),(\\d+),(\\d+)"));
    const auto m = re.match(reply);
    if (!m.hasMatch()) return -1;
    return m.captured(5).toInt() * 256 + m.captured(6).toInt();
}

int parseEpsvPort(const QString &reply)
{
    static const QRegularExpression re(QStringLiteral("\\(\\|\\|\\|(\\d+)\\|\\)"));
    const auto m = re.match(reply);
    return m.hasMatch() ? m.captured(1).toInt() : -1;
}

// ---- shared ----------------------------------------------------------------

bool isPlayableName(const QString &name) { return MediaFiles::isMedia(name) && !MediaFiles::isSubtitle(name); }

QString displayDuration(qint64 ms) { return ms > 0 ? TimeFormat::format(ms) : QString(); }

} // namespace NetRules
