#pragma once
#include <QNetworkAccessManager>
#include <QObject>
#include <QPointer>
#include <QSslSocket>
#include <QTimer>
#include <QVariantList>

class NetworkController;
class QUdpSocket;

/**
 * Play on TV ("tv" in QML): finds DLNA TVs (SSDP) and Chromecasts (mDNS),
 * sends the current video to one and controls it (play/pause/seek/stop/volume),
 * following its position. Files reach the TV through CPlay's LAN media server
 * (NetworkController::publishForTv), so logins stay on this PC.
 */
class TvController : public QObject
{
    Q_OBJECT
    Q_PROPERTY(QVariantList devices READ devices NOTIFY devicesChanged)
    Q_PROPERTY(bool searching READ searching NOTIFY devicesChanged)
    Q_PROPERTY(bool casting READ casting NOTIFY stateChanged)
    Q_PROPERTY(QString deviceName READ deviceName NOTIFY stateChanged)
    Q_PROPERTY(QString state READ state NOTIFY stateChanged)
    Q_PROPERTY(QString title READ title NOTIFY stateChanged)
    Q_PROPERTY(QString location READ location NOTIFY stateChanged)
    Q_PROPERTY(QString error READ error NOTIFY stateChanged)
    Q_PROPERTY(qint64 position READ position NOTIFY positionChanged)
    Q_PROPERTY(qint64 duration READ duration NOTIFY positionChanged)
    Q_PROPERTY(int volume READ volume NOTIFY stateChanged)

public:
    struct Device {
        QString id, name, kind, host, detail;   // kind: "dlna" / "chromecast"
        QString controlUrl, renderingUrl;       // DLNA
        int port = 8009;                        // Chromecast
    };

    explicit TvController(NetworkController *net, QObject *parent = nullptr);
    ~TvController() override;

    QVariantList devices() const;
    bool searching() const { return m_searching; }
    bool casting() const { return m_casting; }
    QString deviceName() const { return m_device.name; }
    QString state() const { return m_state; }
    QString title() const { return m_title; }
    QString location() const { return m_location; }
    QString error() const { return m_error; }
    qint64 position() const { return m_position; }
    qint64 duration() const { return m_duration; }
    int volume() const { return m_volume; }

    Q_INVOKABLE void discover();
    /** "http://.../desc.xml" (DLNA TV) or "host[:port]" (Chromecast). */
    Q_INVOKABLE void addDevice(const QString &address);
    Q_INVOKABLE void cast(const QString &deviceId, const QString &location, const QString &title, qint64 startMs, qint64 durationMs);
    Q_INVOKABLE void play();
    Q_INVOKABLE void pause();
    Q_INVOKABLE void togglePause();
    Q_INVOKABLE void seek(qint64 ms);
    Q_INVOKABLE void stop();
    Q_INVOKABLE void setVolume(int volume);

signals:
    void devicesChanged();
    void stateChanged();
    void positionChanged();
    /** Casting ended (stopped by the user, the TV, or an error) at this position. */
    void finished(qint64 positionMs, bool ended);
    void deviceAdded(bool ok, const QString &message);

private:
    void addOrUpdate(const Device &d);
    void setState(const QString &s, const QString &error = {});
    void finish(bool ended);
    // DLNA
    void soap(const QString &service, const QString &action, const QList<QPair<QString, QString>> &args,
              std::function<void(const QByteArray &, const QString &)> done = {});
    void dlnaPoll();
    void addDlnaDescription(const QString &location, bool manual);
    // Chromecast
    void castSend(const QString &dest, const char *ns, const QJsonObject &payload);
    void castMessage(const QString &source, const QString &ns, const QJsonObject &payload);
    void castMedia(const QJsonObject &payload);

    NetworkController *m_net;
    QNetworkAccessManager m_nam;
    QList<Device> m_devices;
    bool m_searching = false;
    QUdpSocket *m_ssdp = nullptr;
    QUdpSocket *m_mdns = nullptr;

    Device m_device;
    bool m_casting = false;
    QString m_state = QStringLiteral("idle");
    QString m_title, m_location, m_error, m_url, m_mime;
    qint64 m_position = 0, m_duration = 0, m_startMs = 0;
    int m_volume = 50;
    bool m_sawPlaying = false;
    bool m_seekPending = false;
    QTimer m_poll;
    int m_failedPolls = 0;
    // Chromecast session
    std::unique_ptr<QSslSocket> m_cast;
    QByteArray m_castBuffer;
    QString m_transportId;
    qint64 m_mediaSessionId = -1;
    int m_requestId = 1;
    bool m_loadSent = false;
    QTimer m_heartbeat;
};
