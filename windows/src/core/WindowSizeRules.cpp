#include "WindowSizeRules.h"

#include <QRegularExpression>
#include <algorithm>

namespace WindowSizeRules {

Mode modeFromString(const QString &s)
{
    if (s == QLatin1String("custom")) return Mode::Custom;
    if (s == QLatin1String("fit")) return Mode::Fit;
    return Mode::Keep;
}

QString modeToString(Mode m)
{
    switch (m) {
    case Mode::Custom: return QStringLiteral("custom");
    case Mode::Fit: return QStringLiteral("fit");
    case Mode::Keep: break;
    }
    return QStringLiteral("keep");
}

static QSize bounded(QSize s, const QSize &workArea, const QSize &minimum)
{
    if (workArea.isValid()) s = s.boundedTo(workArea);
    if (minimum.isValid()) s = s.expandedTo(minimum);
    return s;
}

QSize sizeOnPlay(Mode mode, const QSize &current, const QSize &custom, const QSize &video, double fitScale,
                 const QSize &chrome, const QSize &workArea, const QSize &minimum)
{
    switch (mode) {
    case Mode::Keep:
        return {};
    case Mode::Custom:
        if (!custom.isValid() || custom.isEmpty()) return {};
        return bounded(custom, workArea, minimum);
    case Mode::Fit: {
        if (!video.isValid() || video.isEmpty()) return {};
        const double scale = fitScale > 0 ? fitScale : 1.0;
        QSize s(int(video.width() * scale) + chrome.width(), int(video.height() * scale) + chrome.height());
        if (workArea.isValid() && (s.width() > workArea.width() || s.height() > workArea.height())) {
            // Too big for the screen: shrink the video part, keeping its shape
            const double k = std::min(double(workArea.width() - chrome.width()) / video.width(),
                                      double(workArea.height() - chrome.height()) / video.height());
            s = QSize(int(video.width() * k) + chrome.width(), int(video.height() * k) + chrome.height());
        }
        return bounded(s, workArea, minimum);
    }
    }
    Q_UNUSED(current)
    return {};
}

QSize parseSize(const QString &text)
{
    static const QRegularExpression re(QStringLiteral("^\\s*(\\d{2,5})\\s*[xX×*,]\\s*(\\d{2,5})\\s*$"));
    const auto m = re.match(text);
    if (!m.hasMatch()) return {};
    const QSize s(m.captured(1).toInt(), m.captured(2).toInt());
    return s.width() >= 100 && s.height() >= 100 ? s : QSize();
}

} // namespace WindowSizeRules
