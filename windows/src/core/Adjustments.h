#pragma once
#include <QList>
#include <QPair>
#include <QString>

/**
 * Limits, steps and on-screen labels for the control box and its hotkeys:
 * audio / subtitle delay, subtitle size and position, picture (brightness,
 * contrast, saturation, hue, gamma), zoom, rotation and aspect presets.
 */
namespace Adjust {
constexpr qint64 DelayStepMs = 100;
constexpr qint64 MaxDelayMs = 10000;
qint64 clampDelay(qint64 ms);
qint64 stepDelay(qint64 ms, bool later);
/** "Audio delay +0.2 s", "Subtitle delay -1.5 s", "... 0 s". */
QString delayLabel(const QString &what, qint64 ms);

constexpr double MinSubScale = 0.3, MaxSubScale = 3.0, SubScaleStep = 0.1;
double clampSubScale(double s);
double stepSubScale(double s, bool bigger);
/** mpv sub-pos: 0 = top, 100 = bottom edge, up to 150 below the video. */
int clampSubPos(int pos);

/** Picture adjustments are -100..100 (mpv's own range), 0 = untouched. */
int clampPicture(int v);
QString pictureLabel(const QString &what, int v);   // "Brightness +10"

/** mpv video-zoom is log2: 0 = 100 %, 1 = 200 %, -1 = 50 %. */
constexpr double ZoomStep = 0.1;
double clampZoom(double z);
double stepZoom(double z, bool in);
int zoomPercent(double z);

int nextRotation(int degrees);                      // 0 -> 90 -> 180 -> 270 -> 0

/** Aspect presets: label and mpv video-aspect-override value ("no" = from the file). */
const QList<QPair<QString, QString>> &aspectPresets();
}
