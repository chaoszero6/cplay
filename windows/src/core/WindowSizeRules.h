#pragma once
#include <QRect>
#include <QSize>
#include <QString>

/**
 * "Window size on play" (spec 8.3): what happens to the window when a video starts.
 *  - Keep:   never resize; the size the user dragged to is kept and remembered
 *  - Custom: always the user's own width x height
 *  - Fit:    the video's size x scale, plus the window chrome
 * The result always fits the screen's work area and the window's minimum size.
 */
namespace WindowSizeRules {
enum class Mode { Keep, Custom, Fit };

Mode modeFromString(const QString &s);
QString modeToString(Mode m);

/** Window size to use when a video of [video] pixels starts; invalid QSize = leave it alone. */
QSize sizeOnPlay(Mode mode, const QSize &current, const QSize &custom, const QSize &video, double fitScale,
                 const QSize &chrome, const QSize &workArea, const QSize &minimum);

/** Parses "1280x720", "1280 x 720", "1280*720"; invalid QSize when it is not a size. */
QSize parseSize(const QString &text);
}
