#pragma once
#include <QList>
#include <QString>
#include <optional>

/** Sleep timer (port of android/.../player/SleepTimer.kt): pause after N minutes. */
namespace SleepTimer {
const QList<int> &presetMinutes();     // 5, 10, 15, 30, 45, 60, 90
QString label(std::optional<qint64> remainingMs); // "Sleep 15m" / "Sleep"
qint64 minutesToMs(int minutes);
int remainingMinutes(qint64 remainingMs); // rounded up
/** New remaining time after [elapsedMs]; nullopt when idle or just expired. */
std::optional<qint64> tick(std::optional<qint64> remainingMs, qint64 elapsedMs);
bool shouldPause(std::optional<qint64> remainingMs, qint64 elapsedMs);
}
