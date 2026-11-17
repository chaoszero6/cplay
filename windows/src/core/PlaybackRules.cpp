#include "PlaybackRules.h"
#include <algorithm>
#include <cmath>

namespace PlaybackRules {

const QList<double> &speedPresets()
{
    static const QList<double> presets{0.25, 0.5, 0.75, 1.0, 1.25, 1.5, 2.0, 3.0, 4.0};
    return presets;
}

double clampSpeed(double speed)
{
    if (std::isnan(speed)) return DefaultSpeed;
    return std::clamp(speed, MinSpeed, MaxSpeed);
}

double stepSpeed(double current, bool up)
{
    const double clamped = clampSpeed(current);
    const auto &p = speedPresets();
    // Snap to the nearest preset first so stepping is predictable
    qsizetype index = 0;
    for (qsizetype i = 1; i < p.size(); ++i)
        if (std::abs(p[i] - clamped) < std::abs(p[index] - clamped)) index = i;
    const qsizetype next = up ? std::min(index + 1, p.size() - 1) : std::max<qsizetype>(index - 1, 0);
    return p[next];
}

qint64 clampSeek(qint64 targetMs, qint64 durationMs)
{
    if (durationMs <= 0) return 0;
    return std::clamp<qint64>(targetMs, 0, durationMs);
}

qint64 seekBy(qint64 positionMs, qint64 deltaMs, qint64 durationMs)
{
    return clampSeek(positionMs + deltaMs, durationMs);
}

qint64 resumeStartMs(qint64 savedMs, qint64 durationMs)
{
    if (savedMs < MinResumeMs) return 0;
    if (durationMs > 0) {
        if (durationMs - savedMs < MinResumeMs) return 0;
        if (isFinished(savedMs, durationMs)) return 0;
    }
    return savedMs;
}

bool isFinished(qint64 positionMs, qint64 durationMs)
{
    return durationMs > 0 && double(positionMs) / double(durationMs) >= FinishedThreshold;
}

} // namespace PlaybackRules

AbRepeat AbRepeat::press(qint64 positionMs) const
{
    if (!aMs) return {positionMs, std::nullopt};
    if (!bMs) {
        if (positionMs > *aMs) return {aMs, positionMs};
        return {positionMs, std::nullopt}; // B behind A: restart from A at the new position
    }
    return {};
}

std::optional<qint64> AbRepeat::loopTargetMs(qint64 currentMs) const
{
    if (!isActive()) return std::nullopt;
    return currentMs >= *bMs ? aMs : std::nullopt;
}
