#pragma once
#include <QList>
#include <QPoint>
#include <QRect>

/**
 * "Magnetic" window edges, as in classic desktop players: while the window is
 * dragged, an edge that comes within [threshold] pixels of a screen edge
 * sticks to it; near a corner both edges stick, so it docks into the corner.
 */
namespace WindowSnap {
constexpr int DefaultThreshold = 18;

/** Top-left position for [window] after snapping to [area] (a screen's work area). */
QPoint snap(const QRect &window, const QRect &area, int threshold = DefaultThreshold);

/** The work area the window overlaps most (falls back to the first area). */
QRect areaFor(const QRect &window, const QList<QRect> &areas);

/**
 * Geometry for a maximized window that the user starts dragging: back to
 * [normalSize], placed so the cursor keeps the same relative x and the grab
 * height from the top, clamped inside [area].
 */
QRect restoreUnderCursor(const QSize &normalSize, const QPoint &cursor, double relativeX, int grabY, const QRect &area);

/** Moves [window] fully inside [area] when it is (partly) off-screen, shrinking it if needed. */
QRect keepOnScreen(const QRect &window, const QRect &area);
}
