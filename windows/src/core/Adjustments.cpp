#include "Adjustments.h"

#include <algorithm>
#include <cmath>

namespace Adjust {

qint64 clampDelay(qint64 ms) { return std::clamp<qint64>(ms, -MaxDelayMs, MaxDelayMs); }
qint64 stepDelay(qint64 ms, bool later) { return clampDelay(ms + (later ? DelayStepMs : -DelayStepMs)); }

QString delayLabel(const QString &what, qint64 ms)
{
    if (ms == 0) return what + QStringLiteral(" delay 0 s");
    QString n = QString::number(std::abs(ms) / 1000.0, 'f', 2);
    while (n.endsWith(u'0')) n.chop(1);
    if (n.endsWith(u'.')) n.chop(1);
    return what + QStringLiteral(" delay ") + (ms > 0 ? u'+' : u'-') + n + QStringLiteral(" s");
}

double clampSubScale(double s) { return std::clamp(std::round(s * 10.0) / 10.0, MinSubScale, MaxSubScale); }
double stepSubScale(double s, bool bigger) { return clampSubScale(s + (bigger ? SubScaleStep : -SubScaleStep)); }
int clampSubPos(int pos) { return std::clamp(pos, 0, 150); }

int clampPicture(int v) { return std::clamp(v, -100, 100); }
QString pictureLabel(const QString &what, int v)
{
    return what + u' ' + (v > 0 ? QStringLiteral("+") : QString()) + QString::number(clampPicture(v));
}

double clampZoom(double z) { return std::clamp(std::round(z * 10.0) / 10.0, -2.0, 2.0); }
double stepZoom(double z, bool in) { return clampZoom(z + (in ? ZoomStep : -ZoomStep)); }
int zoomPercent(double z) { return int(std::lround(std::pow(2.0, z) * 100.0)); }

int nextRotation(int degrees) { return ((degrees / 90 + 1) % 4) * 90; }

const QList<QPair<QString, QString>> &aspectPresets()
{
    static const QList<QPair<QString, QString>> p{
        {QStringLiteral("Original"), QStringLiteral("no")},
        {QStringLiteral("16:9"), QStringLiteral("16:9")},
        {QStringLiteral("4:3"), QStringLiteral("4:3")},
        {QStringLiteral("21:9"), QStringLiteral("2.333")},
        {QStringLiteral("2.35:1"), QStringLiteral("2.35")},
        {QStringLiteral("1.85:1"), QStringLiteral("1.85")},
        {QStringLiteral("1:1"), QStringLiteral("1")},
    };
    return p;
}

} // namespace Adjust
