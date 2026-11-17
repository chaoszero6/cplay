#include "SleepTimer.h"
#include <algorithm>

namespace SleepTimer {
static constexpr qint64 MsPerMinute = 60000;

const QList<int> &presetMinutes()
{
    static const QList<int> p{5, 10, 15, 30, 45, 60, 90};
    return p;
}

QString label(std::optional<qint64> remainingMs)
{
    if (!remainingMs || *remainingMs <= 0) return QStringLiteral("Sleep");
    return QStringLiteral("Sleep %1m").arg(remainingMinutes(*remainingMs));
}

qint64 minutesToMs(int minutes) { return qint64(std::max(0, minutes)) * MsPerMinute; }

int remainingMinutes(qint64 remainingMs)
{
    if (remainingMs <= 0) return 0;
    return int((remainingMs + MsPerMinute - 1) / MsPerMinute);
}

std::optional<qint64> tick(std::optional<qint64> remainingMs, qint64 elapsedMs)
{
    if (!remainingMs) return std::nullopt;
    const qint64 left = *remainingMs - std::max<qint64>(0, elapsedMs);
    if (left <= 0) return std::nullopt;
    return left;
}

bool shouldPause(std::optional<qint64> remainingMs, qint64 elapsedMs)
{
    return remainingMs && *remainingMs - std::max<qint64>(0, elapsedMs) <= 0;
}
}
