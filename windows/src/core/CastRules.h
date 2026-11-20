#pragma once
#include <QByteArray>
#include <QList>
#include <QString>
#include <optional>

/**
 * Chromecast wire formats (Play on TV): the CASTV2 CastMessage protobuf with
 * its 4-byte length frame, and mDNS (_googlecast._tcp) queries and answers.
 * Hand-written (no protobuf/mDNS libraries); unit tested in CastRulesTest.
 */
namespace CastRules {

constexpr const char *NsConnection = "urn:x-cast:com.google.cast.tp.connection";
constexpr const char *NsHeartbeat = "urn:x-cast:com.google.cast.tp.heartbeat";
constexpr const char *NsReceiver = "urn:x-cast:com.google.cast.receiver";
constexpr const char *NsMedia = "urn:x-cast:com.google.cast.media";
constexpr const char *DefaultMediaReceiver = "CC1AD845";

struct CastMessage {
    QString sourceId;
    QString destinationId;
    QString ns;
    QString payload;   // JSON text
};

/** Length-prefixed protobuf frame for one message. */
QByteArray encodeFrame(const CastMessage &m);
/** Parses one protobuf message body (without the length prefix). */
std::optional<CastMessage> decodeMessage(const QByteArray &body);
/** Pops complete frames off the front of [buffer]. */
QList<CastMessage> takeFrames(QByteArray &buffer);

// ---- mDNS --------------------------------------------------------------------
struct CastDevice {
    QString id;          // instance name
    QString name;        // friendly name (TXT fn=)
    QString model;       // TXT md=
    QString host;        // IPv4
    int port = 8009;
};
QByteArray mdnsQuery(const QString &service = QStringLiteral("_googlecast._tcp.local"));
/** Devices announced in one mDNS answer packet (may be incomplete without A records). */
QList<CastDevice> parseMdns(const QByteArray &packet, const QString &service = QStringLiteral("_googlecast._tcp.local"));
}
