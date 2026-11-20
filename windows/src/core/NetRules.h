#pragma once
#include <QDateTime>
#include <QList>
#include <QPair>
#include <QString>
#include <QStringList>
#include <optional>

/** One row when browsing any network source. */
struct RemoteEntry {
    QString id;            // folder path / item id to browse into
    QString name;
    bool isFolder = false;
    QString playKey;       // what the player opens (URL or credential-free key)
    qint64 sizeBytes = 0;
    qint64 durationMs = 0;
    qint64 resumeMs = 0;
    bool played = false;
    QString imageUrl;
    QString subtitle;      // second line ("S01E02", "12 items", "MKV")
    QString kind;          // folder / video / audio / series / ...
    QStringList subtitleUrls;
};

/**
 * Network rules ported from the Android app (network/...Rules.kt): addresses,
 * Jellyfin JSON, WebDAV PROPFIND, DLNA/UPnP (SSDP, DIDL-Lite, SOAP), the
 * local media server's Range header and FTP replies. Pure functions, unit
 * tested in tests/core/NetRulesTest.cpp.
 */
namespace NetRules {

// ---- addresses -------------------------------------------------------------
struct ShareAddress {
    QString host;
    int port = 0;
    QString share;   // SMB share or NFS export ("/export/videos")
    QString path;    // folder inside it, "/" separated, no leading slash
    bool valid() const { return !host.isEmpty(); }
};
/** "\\\\nas\\Videos\\Kids", "smb://nas:4445/Videos", "nas/Videos" */
ShareAddress parseSmb(const QString &input);
/** "ftp://host:2121/films", "host", "host:2121" */
ShareAddress parseFtp(const QString &input);
/** "nfs://host/export/path", "host:/export/path" (export = first path segment unless given) */
ShareAddress parseNfs(const QString &input, const QString &exportHint = {});
QString joinPath(const QString &folder, const QString &name);
QString parentPath(const QString &path);

// ---- Jellyfin --------------------------------------------------------------
QString jellyfinServerUrl(const QString &input);
QString jellyfinAuthHeader(const QString &deviceId, const QString &version, const QString &token = {});
QString jellyfinAuthBody(const QString &user, const QString &password);
struct JellyfinAuth { QString token, userId, userName, serverId; };
std::optional<JellyfinAuth> parseJellyfinAuth(const QByteArray &json);
QString jellyfinError(int status);
qint64 ticksToMs(qint64 ticks);
qint64 msToTicks(qint64 ms);
QString jellyfinItemKey(const QString &serverId, const QString &itemId);
/** (server id, item id) of "jellyfin://<server>/<item>". */
std::optional<QPair<QString, QString>> parseJellyfinKey(const QString &key);
/** Items of /Users/{u}/Items, /Items/Resume, /Shows/NextUp or the bare array of /Items/Latest. */
QList<RemoteEntry> parseJellyfinItems(const QByteArray &json, const QString &base, const QString &serverId);
QString jellyfinStreamUrl(const QString &base, const QString &itemId);
QString jellyfinPlaybackBody(const QString &itemId, qint64 positionMs, bool paused, const QString &playSessionId);
bool shouldReportProgress(qint64 lastReportMs, qint64 nowMs, bool stateChanged);

// ---- WebDAV / Nextcloud ----------------------------------------------------
QString nextcloudDavRoot(const QString &serverInput, const QString &user);
QString webDavRoot(const QString &input);
QByteArray propfindBody();
/** Children of [requestUrl] in a 207 multistatus (folders first, natural order, no hidden). */
QList<RemoteEntry> parseMultistatus(const QByteArray &xml, const QString &requestUrl);
QString basicAuth(const QString &user, const QString &password);

// ---- DLNA / UPnP -----------------------------------------------------------
QByteArray ssdpSearch(const QString &searchTarget);
/** LOCATION of an SSDP reply whose ST/NT contains [wanted] (MediaServer, MediaRenderer, ...). */
QString ssdpLocation(const QByteArray &packet, const QString &wanted);
struct UpnpDevice { QString name, udn, manufacturer, controlUrl, renderingControlUrl, iconUrl; };
/** First device offering [serviceType] ("ContentDirectory", "AVTransport"). */
std::optional<UpnpDevice> parseDeviceDescription(const QByteArray &xml, const QString &location, const QString &serviceType);
QString resolveUrl(const QString &base, const QString &ref);
QString escapeXml(const QString &s);
QByteArray soapEnvelope(const QString &serviceType, const QString &action, const QList<QPair<QString, QString>> &args);
QByteArray browseBody(const QString &objectId, int start, int count);
struct BrowseResult { QList<RemoteEntry> entries; int returned = 0; int total = 0; QString fault; };
BrowseResult parseBrowseResponse(const QByteArray &xml);
qint64 parseUpnpDuration(const QString &value);
QString formatUpnpTime(qint64 ms);
QString soapFault(const QByteArray &xml);
QString xmlTag(const QByteArray &xml, const QString &localName);

// ---- local media server ----------------------------------------------------
struct ByteRange { qint64 start = 0; qint64 end = 0; bool satisfiable = true; };
/** Parsed "Range: bytes=a-b" for a file of [size]; nullopt = no/invalid header (send all). */
std::optional<ByteRange> parseRange(const QString &header, qint64 size);
QString mimeFor(const QString &name);
bool isValidToken(const QString &token);

// ---- FTP -------------------------------------------------------------------
struct FtpEntry { QString name; bool isDir = false; qint64 size = 0; };
std::optional<FtpEntry> parseMlsdLine(const QString &line);
/** Unix "ls -l" and DOS "MM-DD-YY  HH:MMAM  <DIR>" listings. */
std::optional<FtpEntry> parseListLine(const QString &line);
/** Port of a "227 Entering Passive Mode (h1,h2,h3,h4,p1,p2)" reply (-1 if none). */
int parsePasvPort(const QString &reply);
/** Port of a "229 Entering Extended Passive Mode (|||port|)" reply (-1 if none). */
int parseEpsvPort(const QString &reply);

// ---- shared ----------------------------------------------------------------
/** Files the network browser shows as playable. */
bool isPlayableName(const QString &name);
QString displayDuration(qint64 ms);
}
