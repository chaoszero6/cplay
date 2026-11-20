#include "TvController.h"
#include "core/CastRules.h"
#include "core/NetRules.h"
#include "network/NetworkController.h"

#include <QJsonArray>
#include <QJsonDocument>
#include <QJsonObject>
#include <QNetworkReply>
#include <QUdpSocket>
#include <QUrl>

using namespace NetRules;

namespace {
const QString kAvTransport = QStringLiteral("urn:schemas-upnp-org:service:AVTransport:1");
const QString kRendering = QStringLiteral("urn:schemas-upnp-org:service:RenderingControl:1");

QString didl(const QString &title, const QString &url, const QString &mime, qint64 durationMs)
{
    const QString cls = mime.startsWith(QLatin1String("audio")) ? QStringLiteral("object.item.audioItem.musicTrack") : QStringLiteral("object.item.videoItem");
    const QString dur = durationMs > 0 ? QStringLiteral(" duration=\"%1.000\"").arg(formatUpnpTime(durationMs)) : QString();
    const QString flags = QStringLiteral("DLNA.ORG_OP=01;DLNA.ORG_CI=0;DLNA.ORG_FLAGS=01700000000000000000000000000000");
    return QStringLiteral("<DIDL-Lite xmlns=\"urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/\" xmlns:dc=\"http://purl.org/dc/elements/1.1/\" "
                          "xmlns:upnp=\"urn:schemas-upnp-org:metadata-1-0/upnp/\"><item id=\"0\" parentID=\"-1\" restricted=\"1\">"
                          "<dc:title>%1</dc:title><upnp:class>%2</upnp:class><res protocolInfo=\"http-get:*:%3:%4\"%5>%6</res></item></DIDL-Lite>")
        .arg(escapeXml(title), cls, mime.isEmpty() ? QStringLiteral("video/mp4") : mime, flags, dur, escapeXml(url));
}
}

TvController::TvController(NetworkController *net, QObject *parent) : QObject(parent), m_net(net)
{
    m_poll.setInterval(1000);
    connect(&m_poll, &QTimer::timeout, this, [this] {
        if (m_device.kind == QLatin1String("dlna")) dlnaPoll();
        else if (m_cast && !m_transportId.isEmpty() && m_mediaSessionId >= 0)
            castSend(m_transportId, CastRules::NsMedia, {{"type", "GET_STATUS"}, {"mediaSessionId", m_mediaSessionId}, {"requestId", m_requestId++}});
    });
    m_heartbeat.setInterval(5000);
    connect(&m_heartbeat, &QTimer::timeout, this, [this] {
        if (m_cast) castSend(QStringLiteral("receiver-0"), CastRules::NsHeartbeat, {{"type", "PING"}});
    });
}

TvController::~TvController()
{
    if (m_casting) stop();
}

QVariantList TvController::devices() const
{
    QVariantList out;
    for (const auto &d : m_devices)
        out << QVariantMap{{"id", d.id}, {"name", d.name}, {"kind", d.kind}, {"host", d.host}, {"detail", d.detail}};
    return out;
}

void TvController::addOrUpdate(const Device &d)
{
    for (auto &x : m_devices)
        if (x.id == d.id) { x = d; emit devicesChanged(); return; }
    m_devices << d;
    emit devicesChanged();
}

// ---- discovery ------------------------------------------------------------------

void TvController::addDlnaDescription(const QString &location, bool manual)
{
    QNetworkRequest req{QUrl(location)};
    req.setTransferTimeout(5000);
    QNetworkReply *reply = m_nam.get(req);
    connect(reply, &QNetworkReply::finished, this, [this, reply, location, manual] {
        reply->deleteLater();
        const auto dev = parseDeviceDescription(reply->readAll(), location, QStringLiteral("AVTransport"));
        if (!dev) { if (manual) emit deviceAdded(false, QStringLiteral("No DLNA TV at this address")); return; }
        Device d;
        d.id = dev->udn.isEmpty() ? location : dev->udn;
        d.name = dev->name;
        d.kind = QStringLiteral("dlna");
        d.host = QUrl(location).host();
        d.detail = dev->manufacturer.isEmpty() ? QStringLiteral("DLNA TV") : dev->manufacturer + QStringLiteral(" · DLNA");
        d.controlUrl = dev->controlUrl;
        d.renderingUrl = dev->renderingControlUrl;
        addOrUpdate(d);
        if (manual) emit deviceAdded(true, d.name);
    });
}

void TvController::discover()
{
    if (m_searching) return;
    m_searching = true;
    emit devicesChanged();
    delete m_ssdp;
    delete m_mdns;
    m_ssdp = new QUdpSocket(this);
    m_ssdp->bind(QHostAddress::AnyIPv4, 0);
    connect(m_ssdp, &QUdpSocket::readyRead, this, [this] {
        while (m_ssdp && m_ssdp->hasPendingDatagrams()) {
            QByteArray p(int(m_ssdp->pendingDatagramSize()), Qt::Uninitialized);
            m_ssdp->readDatagram(p.data(), p.size());
            const QString loc = ssdpLocation(p, QStringLiteral("MediaRenderer"));
            const QString loc2 = loc.isEmpty() ? ssdpLocation(p, QStringLiteral("AVTransport")) : loc;
            if (!loc2.isEmpty()) addDlnaDescription(loc2, false);
        }
    });
    m_mdns = new QUdpSocket(this);
    m_mdns->bind(QHostAddress::AnyIPv4, 0);
    connect(m_mdns, &QUdpSocket::readyRead, this, [this] {
        while (m_mdns && m_mdns->hasPendingDatagrams()) {
            QByteArray p(int(m_mdns->pendingDatagramSize()), Qt::Uninitialized);
            QHostAddress from;
            m_mdns->readDatagram(p.data(), p.size(), &from);
            for (auto cd : CastRules::parseMdns(p)) {
                if (cd.host.isEmpty()) cd.host = QHostAddress(from.toIPv4Address()).toString();
                Device d;
                d.id = QStringLiteral("cast:") + cd.id;
                d.name = cd.name;
                d.kind = QStringLiteral("chromecast");
                d.host = cd.host;
                d.port = cd.port;
                d.detail = cd.model.isEmpty() ? QStringLiteral("Chromecast") : cd.model;
                addOrUpdate(d);
            }
        }
    });
    const QByteArray ssdpQuery = ssdpSearch(QStringLiteral("urn:schemas-upnp-org:device:MediaRenderer:1"));
    const QByteArray mdnsQuery = CastRules::mdnsQuery();
    for (int i = 0; i < 3; ++i) {
        QTimer::singleShot(i * 800, this, [this, ssdpQuery, mdnsQuery] {
            if (m_ssdp) m_ssdp->writeDatagram(ssdpQuery, QHostAddress(QStringLiteral("239.255.255.250")), 1900);
            if (m_mdns) m_mdns->writeDatagram(mdnsQuery, QHostAddress(QStringLiteral("224.0.0.251")), 5353);
        });
    }
    QTimer::singleShot(4500, this, [this] {
        m_searching = false;
        emit devicesChanged();
    });
}

void TvController::addDevice(const QString &address)
{
    const QString a = address.trimmed();
    if (a.startsWith(QLatin1String("http"), Qt::CaseInsensitive)) { addDlnaDescription(a, true); return; }
    // Chromecast by address: host or host:port
    const QString host = a.section(u':', 0, 0);
    const int port = a.contains(u':') ? a.section(u':', 1).toInt() : 8009;
    if (host.isEmpty() || port <= 0) { emit deviceAdded(false, QStringLiteral("Type the TV's address")); return; }
    Device d;
    d.id = QStringLiteral("cast:%1:%2").arg(host).arg(port);
    d.name = QStringLiteral("Chromecast (%1)").arg(host);
    d.kind = QStringLiteral("chromecast");
    d.host = host;
    d.port = port;
    d.detail = QStringLiteral("Chromecast");
    addOrUpdate(d);
    emit deviceAdded(true, d.name);
}

// ---- session ---------------------------------------------------------------------

void TvController::setState(const QString &s, const QString &error)
{
    m_state = s;
    if (!error.isEmpty()) m_error = error;
    emit stateChanged();
}

void TvController::finish(bool ended)
{
    if (!m_casting) return;
    m_poll.stop();
    m_heartbeat.stop();
    m_casting = false;
    if (m_cast) {
        m_cast->disconnect(this);
        m_cast->abort();
        m_cast.reset();
    }
    setState(m_error.isEmpty() ? QStringLiteral("stopped") : QStringLiteral("error"));
    emit finished(m_position, ended);
}

void TvController::cast(const QString &deviceId, const QString &location, const QString &title, qint64 startMs, qint64 durationMs)
{
    if (m_casting) stop();
    Device dev;
    for (const auto &d : m_devices) if (d.id == deviceId) dev = d;
    m_error.clear();
    if (dev.id.isEmpty()) { setState(QStringLiteral("error"), QStringLiteral("That TV is no longer available")); return; }
    m_device = dev;
    m_location = location;
    m_title = title;
    m_position = startMs;
    m_startMs = startMs;
    m_duration = durationMs;
    m_sawPlaying = false;
    m_seekPending = startMs > 1000;
    m_failedPolls = 0;
    m_url = m_net->publishForTv(location, dev.host, &m_mime);
    if (m_url.isEmpty()) { setState(QStringLiteral("error"), QStringLiteral("This video can't be sent to a TV")); return; }
    m_casting = true;
    setState(QStringLiteral("connecting"));
    emit positionChanged();

    if (dev.kind == QLatin1String("dlna")) {
        soap(kAvTransport, QStringLiteral("SetAVTransportURI"),
             {{"InstanceID", "0"}, {"CurrentURI", m_url}, {"CurrentURIMetaData", didl(title, m_url, m_mime, durationMs)}},
             [this](const QByteArray &, const QString &err) {
            if (!err.isEmpty()) { setState(QStringLiteral("error"), err); finish(false); return; }
            soap(kAvTransport, QStringLiteral("Play"), {{"InstanceID", "0"}, {"Speed", "1"}}, [this](const QByteArray &, const QString &e2) {
                if (!e2.isEmpty()) { setState(QStringLiteral("error"), e2); finish(false); return; }
                setState(QStringLiteral("buffering"));
                m_poll.start();
            });
        });
        return;
    }

    // Chromecast: TLS to port 8009, CONNECT, LAUNCH the default media receiver, then LOAD
    m_cast = std::make_unique<QSslSocket>();
    m_cast->setPeerVerifyMode(QSslSocket::VerifyNone); // Cast devices use self-signed device certificates
    m_castBuffer.clear();
    m_transportId.clear();
    m_mediaSessionId = -1;
    m_loadSent = false;
    connect(m_cast.get(), &QSslSocket::encrypted, this, [this] {
        castSend(QStringLiteral("receiver-0"), CastRules::NsConnection, {{"type", "CONNECT"}});
        castSend(QStringLiteral("receiver-0"), CastRules::NsReceiver, {{"type", "LAUNCH"}, {"appId", CastRules::DefaultMediaReceiver}, {"requestId", m_requestId++}});
        m_heartbeat.start();
    });
    connect(m_cast.get(), &QSslSocket::readyRead, this, [this] {
        m_castBuffer += m_cast->readAll();
        for (const auto &m : CastRules::takeFrames(m_castBuffer))
            castMessage(m.sourceId, m.ns, QJsonDocument::fromJson(m.payload.toUtf8()).object());
    });
    connect(m_cast.get(), &QSslSocket::errorOccurred, this, [this](QAbstractSocket::SocketError) {
        if (!m_casting) return;
        setState(QStringLiteral("error"), QStringLiteral("Lost the connection to the TV: ") + (m_cast ? m_cast->errorString() : QString()));
        finish(false);
    });
    connect(m_cast.get(), &QSslSocket::sslErrors, this, [this](const QList<QSslError> &) { if (m_cast) m_cast->ignoreSslErrors(); });
    m_cast->connectToHostEncrypted(dev.host, quint16(dev.port));
}

void TvController::play()
{
    if (!m_casting) return;
    if (m_device.kind == QLatin1String("dlna")) soap(kAvTransport, QStringLiteral("Play"), {{"InstanceID", "0"}, {"Speed", "1"}});
    else if (m_mediaSessionId >= 0) castSend(m_transportId, CastRules::NsMedia, {{"type", "PLAY"}, {"mediaSessionId", m_mediaSessionId}, {"requestId", m_requestId++}});
}

void TvController::pause()
{
    if (!m_casting) return;
    if (m_device.kind == QLatin1String("dlna")) soap(kAvTransport, QStringLiteral("Pause"), {{"InstanceID", "0"}});
    else if (m_mediaSessionId >= 0) castSend(m_transportId, CastRules::NsMedia, {{"type", "PAUSE"}, {"mediaSessionId", m_mediaSessionId}, {"requestId", m_requestId++}});
}

void TvController::togglePause()
{
    if (m_state == QLatin1String("playing")) pause();
    else play();
}

void TvController::seek(qint64 ms)
{
    if (!m_casting) return;
    m_position = std::max<qint64>(0, ms);
    emit positionChanged();
    if (m_device.kind == QLatin1String("dlna"))
        soap(kAvTransport, QStringLiteral("Seek"), {{"InstanceID", "0"}, {"Unit", "REL_TIME"}, {"Target", formatUpnpTime(m_position)}});
    else if (m_mediaSessionId >= 0)
        castSend(m_transportId, CastRules::NsMedia, {{"type", "SEEK"}, {"mediaSessionId", m_mediaSessionId}, {"currentTime", double(m_position) / 1000.0}, {"requestId", m_requestId++}});
}

void TvController::stop()
{
    if (!m_casting) return;
    if (m_device.kind == QLatin1String("dlna")) soap(kAvTransport, QStringLiteral("Stop"), {{"InstanceID", "0"}});
    else if (m_cast && m_mediaSessionId >= 0) {
        castSend(m_transportId, CastRules::NsMedia, {{"type", "STOP"}, {"mediaSessionId", m_mediaSessionId}, {"requestId", m_requestId++}});
        m_cast->flush();
    }
    finish(false);
}

void TvController::setVolume(int volume)
{
    m_volume = std::clamp(volume, 0, 100);
    emit stateChanged();
    if (!m_casting) return;
    if (m_device.kind == QLatin1String("dlna")) {
        if (!m_device.renderingUrl.isEmpty())
            soap(kRendering, QStringLiteral("SetVolume"), {{"InstanceID", "0"}, {"Channel", "Master"}, {"DesiredVolume", QString::number(m_volume)}});
    } else {
        castSend(QStringLiteral("receiver-0"), CastRules::NsReceiver,
                 {{"type", "SET_VOLUME"}, {"volume", QJsonObject{{"level", m_volume / 100.0}}}, {"requestId", m_requestId++}});
    }
}

// ---- DLNA ------------------------------------------------------------------------

void TvController::soap(const QString &service, const QString &action, const QList<QPair<QString, QString>> &args,
                        std::function<void(const QByteArray &, const QString &)> done)
{
    const QString url = service == kRendering ? m_device.renderingUrl : m_device.controlUrl;
    QNetworkRequest req{QUrl(url)};
    req.setTransferTimeout(8000);
    req.setRawHeader("Content-Type", "text/xml; charset=\"utf-8\"");
    req.setRawHeader("SOAPAction", ('"' + service + u'#' + action + '"').toUtf8());
    QNetworkReply *reply = m_nam.post(req, soapEnvelope(service, action, args));
    connect(reply, &QNetworkReply::finished, this, [reply, done] {
        reply->deleteLater();
        const QByteArray body = reply->readAll();
        const int status = reply->attribute(QNetworkRequest::HttpStatusCodeAttribute).toInt();
        QString err = soapFault(body);
        if (err.isEmpty() && status != 200) err = status == 0 ? QStringLiteral("The TV did not answer") : QStringLiteral("The TV answered %1").arg(status);
        if (done) done(body, err);
    });
}

void TvController::dlnaPoll()
{
    soap(kAvTransport, QStringLiteral("GetPositionInfo"), {{"InstanceID", "0"}}, [this](const QByteArray &xml, const QString &err) {
        if (!m_casting) return;
        if (!err.isEmpty()) {
            if (++m_failedPolls >= 6) { setState(QStringLiteral("error"), QStringLiteral("The TV stopped answering")); finish(false); }
            return;
        }
        m_failedPolls = 0;
        const qint64 pos = parseUpnpDuration(xmlTag(xml, QStringLiteral("RelTime")));
        const qint64 dur = parseUpnpDuration(xmlTag(xml, QStringLiteral("TrackDuration")));
        if (!m_seekPending) m_position = pos;
        if (dur > 0) m_duration = dur;
        emit positionChanged();
    });
    soap(kAvTransport, QStringLiteral("GetTransportInfo"), {{"InstanceID", "0"}}, [this](const QByteArray &xml, const QString &err) {
        if (!m_casting || !err.isEmpty()) return;
        const QString st = xmlTag(xml, QStringLiteral("CurrentTransportState"));
        if (st == QLatin1String("PLAYING")) {
            m_sawPlaying = true;
            if (m_seekPending) { m_seekPending = false; seek(m_startMs); }
            if (m_state != QLatin1String("playing")) setState(QStringLiteral("playing"));
        } else if (st == QLatin1String("PAUSED_PLAYBACK")) {
            if (m_state != QLatin1String("paused")) setState(QStringLiteral("paused"));
        } else if (st == QLatin1String("TRANSITIONING")) {
            if (m_state != QLatin1String("buffering")) setState(QStringLiteral("buffering"));
        } else if ((st == QLatin1String("STOPPED") || st == QLatin1String("NO_MEDIA_PRESENT")) && m_sawPlaying) {
            // Stopped on the TV (remote or end of the video)
            finish(m_duration > 0 && m_position > m_duration - 5000);
        }
    });
}

// ---- Chromecast --------------------------------------------------------------------

void TvController::castSend(const QString &dest, const char *ns, const QJsonObject &payload)
{
    if (!m_cast) return;
    CastRules::CastMessage m{QStringLiteral("sender-0"), dest, QString::fromLatin1(ns),
                             QString::fromUtf8(QJsonDocument(payload).toJson(QJsonDocument::Compact))};
    m_cast->write(CastRules::encodeFrame(m));
}

void TvController::castMessage(const QString &source, const QString &ns, const QJsonObject &p)
{
    const QString type = p.value("type").toString();
    if (ns == QLatin1String(CastRules::NsHeartbeat)) {
        if (type == QLatin1String("PING")) castSend(source, CastRules::NsHeartbeat, {{"type", "PONG"}});
        return;
    }
    if (ns == QLatin1String(CastRules::NsReceiver) && type == QLatin1String("RECEIVER_STATUS")) {
        const QJsonObject status = p.value("status").toObject();
        const QJsonObject vol = status.value("volume").toObject();
        if (vol.contains("level")) m_volume = qRound(vol.value("level").toDouble() * 100);
        for (const auto &a : status.value("applications").toArray()) {
            const QJsonObject app = a.toObject();
            if (app.value("appId").toString() != QLatin1String(CastRules::DefaultMediaReceiver)) continue;
            const QString transport = app.value("transportId").toString();
            if (transport.isEmpty() || m_loadSent) continue;
            m_transportId = transport;
            m_loadSent = true;
            castSend(m_transportId, CastRules::NsConnection, {{"type", "CONNECT"}});
            QJsonObject media{{"contentId", m_url}, {"contentType", m_mime.isEmpty() ? QStringLiteral("video/mp4") : m_mime},
                              {"streamType", "BUFFERED"},
                              {"metadata", QJsonObject{{"metadataType", 0}, {"title", m_title}}}};
            if (m_duration > 0) media.insert("duration", double(m_duration) / 1000.0);
            castSend(m_transportId, CastRules::NsMedia, {{"type", "LOAD"}, {"media", media}, {"autoplay", true},
                                                         {"currentTime", double(m_startMs) / 1000.0}, {"requestId", m_requestId++}});
            setState(QStringLiteral("buffering"));
            m_poll.start();
        }
        emit stateChanged();
        return;
    }
    if (ns == QLatin1String(CastRules::NsMedia)) {
        if (type == QLatin1String("MEDIA_STATUS")) castMedia(p);
        else if (type == QLatin1String("LOAD_FAILED") || type == QLatin1String("LOAD_CANCELLED") || type == QLatin1String("INVALID_REQUEST")) {
            setState(QStringLiteral("error"), QStringLiteral("The TV could not play this video (") + type + u')');
            finish(false);
        }
        return;
    }
    if (ns == QLatin1String(CastRules::NsConnection) && type == QLatin1String("CLOSE") && source == m_transportId) {
        finish(false); // the app on the TV was closed
    }
}

void TvController::castMedia(const QJsonObject &p)
{
    const QJsonArray list = p.value("status").toArray();
    if (list.isEmpty()) return;
    const QJsonObject s = list.first().toObject();
    if (s.contains("mediaSessionId")) m_mediaSessionId = qint64(s.value("mediaSessionId").toDouble());
    if (s.contains("currentTime")) m_position = qint64(s.value("currentTime").toDouble() * 1000);
    const double dur = s.value("media").toObject().value("duration").toDouble();
    if (dur > 0) m_duration = qint64(dur * 1000);
    emit positionChanged();
    const QString ps = s.value("playerState").toString();
    if (ps == QLatin1String("PLAYING")) { m_sawPlaying = true; setState(QStringLiteral("playing")); }
    else if (ps == QLatin1String("PAUSED")) setState(QStringLiteral("paused"));
    else if (ps == QLatin1String("BUFFERING")) setState(QStringLiteral("buffering"));
    else if (ps == QLatin1String("IDLE") && m_sawPlaying) {
        const QString reason = s.value("idleReason").toString();
        if (reason == QLatin1String("ERROR")) m_error = QStringLiteral("The TV could not play this video");
        finish(reason == QLatin1String("FINISHED"));
    }
}
