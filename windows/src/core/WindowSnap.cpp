#include "WindowSnap.h"

#include <algorithm>
#include <cstdlib>

namespace WindowSnap {

QPoint snap(const QRect &window, const QRect &area, int threshold)
{
    int x = window.x();
    int y = window.y();
    const int w = window.width();
    const int h = window.height();
    const int areaRight = area.x() + area.width();   // one past the last pixel
    const int areaBottom = area.y() + area.height();

    if (std::abs(x - area.x()) <= threshold) x = area.x();
    else if (std::abs((x + w) - areaRight) <= threshold) x = areaRight - w;

    if (std::abs(y - area.y()) <= threshold) y = area.y();
    else if (std::abs((y + h) - areaBottom) <= threshold) y = areaBottom - h;
    return {x, y};
}

QRect areaFor(const QRect &window, const QList<QRect> &areas)
{
    if (areas.isEmpty()) return {};
    QRect best = areas.first();
    long long bestOverlap = -1;
    for (const QRect &a : areas) {
        const QRect i = a.intersected(window);
        const long long overlap = i.isValid() ? (long long)i.width() * i.height() : 0;
        if (overlap > bestOverlap) { bestOverlap = overlap; best = a; }
    }
    return best;
}

QRect restoreUnderCursor(const QSize &normalSize, const QPoint &cursor, double relativeX, int grabY, const QRect &area)
{
    const int w = std::min(normalSize.width(), area.width());
    const int h = std::min(normalSize.height(), area.height());
    int x = cursor.x() - int(std::clamp(relativeX, 0.0, 1.0) * w);
    int y = cursor.y() - std::clamp(grabY, 0, h - 1);
    x = std::clamp(x, area.x(), area.x() + area.width() - w);
    y = std::clamp(y, area.y(), area.y() + area.height() - h);
    return {x, y, w, h};
}

QRect keepOnScreen(const QRect &window, const QRect &area)
{
    const int w = std::min(window.width(), area.width());
    const int h = std::min(window.height(), area.height());
    const int x = std::clamp(window.x(), area.x(), area.x() + area.width() - w);
    const int y = std::clamp(window.y(), area.y(), area.y() + area.height() - h);
    return {x, y, w, h};
}

} // namespace WindowSnap
