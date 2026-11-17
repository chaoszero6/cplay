#pragma once
#include <QList>
#include <QString>
#include <optional>

/** Port of android/.../player/PlaybackRules.kt and the resume thresholds of LibraryRules.kt. */
namespace PlaybackRules {
constexpr double MinSpeed = 0.25;
constexpr double MaxSpeed = 4.0;
constexpr double DefaultSpeed = 1.0;
constexpr qint64 DefaultSeekStepMs = 10000;
/** Videos finished at or past this fraction count as watched. */
constexpr double FinishedThreshold = 0.95;
/** A resume point is only useful this far from both ends. */
constexpr qint64 MinResumeMs = 5000;

const QList<double> &speedPresets();
double clampSpeed(double speed);
double stepSpeed(double current, bool up);
qint64 clampSeek(qint64 targetMs, qint64 durationMs);
qint64 seekBy(qint64 positionMs, qint64 deltaMs, qint64 durationMs);
/** Where to start a video saved at [savedMs]; 0 means "from the beginning". */
qint64 resumeStartMs(qint64 savedMs, qint64 durationMs);
bool isFinished(qint64 positionMs, qint64 durationMs);
}

/** A-B repeat: first press sets A, second sets B (after A), third clears. */
struct AbRepeat {
    std::optional<qint64> aMs;
    std::optional<qint64> bMs;

    bool isActive() const { return aMs && bMs; }
    bool isArmed() const { return aMs && !bMs; }
    AbRepeat press(qint64 positionMs) const;
    std::optional<qint64> loopTargetMs(qint64 currentMs) const;
    QString label() const { return isArmed() ? QStringLiteral("A-") : QStringLiteral("A-B"); }
};
