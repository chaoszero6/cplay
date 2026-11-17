#pragma once
#include <QString>
#include <QPair>

/** Port of android/.../player/TimeFormat.kt: labels for the seek bar and lists. */
namespace TimeFormat {
QString format(qint64 positionMs);                       // "1:02:03", "12:34", "0:00"
QString formatRemaining(qint64 durationMs, qint64 positionMs); // "-1:00"
QString formatClock(qint64 positionMs);                  // fixed width "00:12:34"
QString formatClockRemaining(qint64 durationMs, qint64 positionMs); // "-00:01:00"
QPair<QString, QString> seekBarLabels(qint64 durationMs, qint64 positionMs);
QString formatSeekDelta(qint64 deltaMs, qint64 targetMs); // "+0:23 (1:12:40)"
QString formatSpeed(double speed);                        // "1x", "1.25x"
double progressFraction(qint64 positionMs, qint64 durationMs);
QString remainingShortLabel(qint64 durationMs, qint64 positionMs); // "5 min left"
}
