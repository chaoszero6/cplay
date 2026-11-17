#include "TimeFormat.h"
#include <cmath>
#include <cstdlib>
#include <algorithm>

namespace TimeFormat {

QString format(qint64 positionMs)
{
    if (positionMs <= 0) return QStringLiteral("0:00");
    const qint64 total = positionMs / 1000;
    const qint64 h = total / 3600, m = (total % 3600) / 60, s = total % 60;
    if (h > 0) return QString::asprintf("%lld:%02lld:%02lld", h, m, s);
    return QString::asprintf("%lld:%02lld", m, s);
}

QString formatRemaining(qint64 durationMs, qint64 positionMs)
{
    const qint64 remaining = durationMs - positionMs;
    if (remaining <= 0) return QStringLiteral("-0:00");
    return QStringLiteral("-") + format(remaining);
}

QString formatClock(qint64 positionMs)
{
    if (positionMs <= 0) return QStringLiteral("00:00:00");
    const qint64 total = positionMs / 1000;
    return QString::asprintf("%02lld:%02lld:%02lld", total / 3600, (total % 3600) / 60, total % 60);
}

QString formatClockRemaining(qint64 durationMs, qint64 positionMs)
{
    const qint64 remaining = durationMs - positionMs;
    if (remaining <= 0) return QStringLiteral("-00:00:00");
    return QStringLiteral("-") + formatClock(remaining);
}

QPair<QString, QString> seekBarLabels(qint64 durationMs, qint64 positionMs)
{
    return {formatClock(positionMs), formatClockRemaining(durationMs, positionMs)};
}

QString formatSeekDelta(qint64 deltaMs, qint64 targetMs)
{
    const QString sign = deltaMs < 0 ? QStringLiteral("-") : QStringLiteral("+");
    return sign + format(std::llabs(deltaMs)) + QStringLiteral(" (") + format(targetMs) + QStringLiteral(")");
}

QString formatSpeed(double speed)
{
    if (speed == std::floor(speed)) return QString::number(qint64(speed)) + QStringLiteral("x");
    QString s = QString::asprintf("%.2f", speed);
    while (s.endsWith(u'0')) s.chop(1);
    if (s.endsWith(u'.')) s.chop(1);
    return s + QStringLiteral("x");
}

double progressFraction(qint64 positionMs, qint64 durationMs)
{
    if (durationMs <= 0) return 0.0;
    return std::clamp(double(positionMs) / double(durationMs), 0.0, 1.0);
}

QString remainingShortLabel(qint64 durationMs, qint64 positionMs)
{
    const qint64 remaining = durationMs - positionMs;
    if (remaining <= 0) return QStringLiteral("Finished");
    const qint64 minutes = remaining / 60000;
    if (minutes < 1) return QStringLiteral("<1 min left");
    if (minutes < 60) return QStringLiteral("%1 min left").arg(minutes);
    return QStringLiteral("%1h %2m left").arg(minutes / 60).arg(minutes % 60);
}

} // namespace TimeFormat
