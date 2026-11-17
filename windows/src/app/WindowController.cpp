#include "WindowController.h"

#include "core/WindowSizeRules.h"
#include "core/WindowSnap.h"

#include <QCursor>
#include <QGuiApplication>
#include <QScreen>

WindowController::WindowController(const QString &settingsFile, QObject *parent)
    : QObject(parent), m_settings(settingsFile, QSettings::IniFormat)
{
    m_magnetic = m_settings.value(QStringLiteral("window/magnetic"), true).toBool();
    m_sizeMode = WindowSizeRules::modeToString(
        WindowSizeRules::modeFromString(m_settings.value(QStringLiteral("window/sizeOnPlay"), QStringLiteral("keep")).toString()));
    m_customSize = m_settings.value(QStringLiteral("window/customSize")).toSize();
    m_fitScale = m_settings.value(QStringLiteral("window/fitScale"), 1.0).toDouble();
}

void WindowController::setSizeMode(const QString &mode)
{
    const QString m = WindowSizeRules::modeToString(WindowSizeRules::modeFromString(mode));
    if (m == m_sizeMode) return;
    m_sizeMode = m;
    // "Always use my size" with no size yet: take the current window size
    if (m == QLatin1String("custom") && !m_customSize.isValid() && m_window) {
        m_customSize = m_window->geometry().size();
        m_settings.setValue(QStringLiteral("window/customSize"), m_customSize);
    }
    m_settings.setValue(QStringLiteral("window/sizeOnPlay"), m_sizeMode);
    emit sizeSettingsChanged();
}

QString WindowController::customSizeText() const
{
    return m_customSize.isValid() ? QStringLiteral("%1×%2").arg(m_customSize.width()).arg(m_customSize.height()) : QString();
}

void WindowController::setFitScale(double scale)
{
    if (scale <= 0 || qFuzzyCompare(scale, m_fitScale)) return;
    m_fitScale = scale;
    m_settings.setValue(QStringLiteral("window/fitScale"), scale);
    emit sizeSettingsChanged();
}

void WindowController::useCurrentSizeAsCustom()
{
    if (!m_window) return;
    m_customSize = m_window->geometry().size();
    m_settings.setValue(QStringLiteral("window/customSize"), m_customSize);
    m_sizeMode = QStringLiteral("custom");
    m_settings.setValue(QStringLiteral("window/sizeOnPlay"), m_sizeMode);
    emit sizeSettingsChanged();
}

bool WindowController::setCustomSizeText(const QString &text)
{
    const QSize s = WindowSizeRules::parseSize(text);
    if (!s.isValid()) return false;
    m_customSize = s;
    m_sizeMode = QStringLiteral("custom");
    m_settings.setValue(QStringLiteral("window/customSize"), s);
    m_settings.setValue(QStringLiteral("window/sizeOnPlay"), m_sizeMode);
    emit sizeSettingsChanged();
    if (m_window && m_window->visibility() != QWindow::FullScreen && !m_maximized) {
        const QRect area = workAreaFor(m_window->geometry());
        QRect r(m_window->geometry().topLeft(), s.boundedTo(area.size()).expandedTo(m_window->minimumSize()));
        m_window->setGeometry(WindowSnap::keepOnScreen(r, area));
        saveGeometry();
    }
    return true;
}

void WindowController::applySizeOnPlay(int videoWidth, int videoHeight, int chromeWidth, int chromeHeight)
{
    // Fullscreen and maximized windows are left alone; so is "keep"
    if (!m_window || m_window->visibility() == QWindow::FullScreen || m_maximized) return;
    const QRect area = workAreaFor(m_window->geometry());
    const QSize size = WindowSizeRules::sizeOnPlay(
        WindowSizeRules::modeFromString(m_sizeMode), m_window->geometry().size(), m_customSize,
        QSize(videoWidth, videoHeight), m_fitScale, QSize(chromeWidth, chromeHeight), area.size(), m_window->minimumSize());
    if (!size.isValid() || size == m_window->geometry().size()) return;
    // Grow/shrink around the current centre, then keep it on screen
    QRect r(QPoint(0, 0), size);
    r.moveCenter(m_window->geometry().center());
    m_window->setGeometry(WindowSnap::keepOnScreen(r, area));
    saveGeometry();
}

void WindowController::setWindow(QWindow *window) { m_window = window; }

void WindowController::setMagnetic(bool on)
{
    if (on == m_magnetic) return;
    m_magnetic = on;
    m_settings.setValue(QStringLiteral("window/magnetic"), on);
    emit magneticChanged();
}

QRect WindowController::workAreaFor(const QRect &r) const
{
    QList<QRect> areas;
    for (QScreen *s : QGuiApplication::screens()) areas << s->availableGeometry();
    // Prefer the screen under the cursor while dragging, like Windows does
    if (QScreen *s = QGuiApplication::screenAt(QCursor::pos()); s && m_dragging) return s->availableGeometry();
    return WindowSnap::areaFor(r, areas);
}

void WindowController::setMaximized(bool m)
{
    if (m == m_maximized) return;
    m_maximized = m;
    emit maximizedChanged();
}

void WindowController::startDrag()
{
    if (!m_window || m_window->visibility() == QWindow::FullScreen) return;
    const QPoint cursor = QCursor::pos();
    const QRect g = m_window->geometry();
    m_grabOffset = cursor - g.topLeft();
    m_grabRelX = g.width() > 0 ? double(m_grabOffset.x()) / g.width() : 0.5;
    // A maximized window un-maximizes only once the mouse really moves
    m_pendingRestore = m_maximized;
    m_dragging = true;
    emit draggingChanged();
}

void WindowController::dragMove()
{
    if (!m_dragging || !m_window) return;
    const QPoint cursor = QCursor::pos();
    if (m_pendingRestore) {
        if ((cursor - (m_window->geometry().topLeft() + m_grabOffset)).manhattanLength() < 6) return;
        const QSize normal = m_normalGeometry.isValid() ? m_normalGeometry.size() : QSize(960, 600);
        const QRect r = WindowSnap::restoreUnderCursor(normal, cursor, m_grabRelX, m_grabOffset.y(), workAreaFor(m_window->geometry()));
        m_window->setGeometry(r);
        m_grabOffset = cursor - r.topLeft();
        m_pendingRestore = false;
        setMaximized(false);
        return;
    }
    QRect target(cursor - m_grabOffset, m_window->geometry().size());
    if (m_magnetic) target.moveTopLeft(WindowSnap::snap(target, workAreaFor(target)));
    m_window->setPosition(target.topLeft());
}

void WindowController::endDrag()
{
    if (!m_dragging) return;
    m_dragging = false;
    m_pendingRestore = false;
    emit draggingChanged();
    saveGeometry();
}

void WindowController::toggleMaximize()
{
    if (!m_window) return;
    if (m_window->visibility() == QWindow::FullScreen) return;
    if (m_maximized) {
        m_window->setGeometry(m_normalGeometry.isValid() ? m_normalGeometry : QRect(100, 100, 960, 600));
        setMaximized(false);
    } else {
        m_normalGeometry = m_window->geometry();
        // Our own maximize: a frameless window maximized by the OS covers the taskbar
        m_window->setGeometry(workAreaFor(m_window->geometry()));
        setMaximized(true);
    }
    saveGeometry();
}

void WindowController::minimize()
{
    if (m_window) m_window->showMinimized();
}

void WindowController::fitVideo(double scale, int videoWidth, int videoHeight, int chromeWidth, int chromeHeight)
{
    if (!m_window || videoWidth <= 0 || videoHeight <= 0) return;
    if (m_window->visibility() == QWindow::FullScreen) m_window->showNormal();
    setMaximized(false);
    const QRect area = workAreaFor(m_window->geometry());
    QSize size(int(videoWidth * scale) + chromeWidth, int(videoHeight * scale) + chromeHeight);
    size = size.boundedTo(area.size()).expandedTo(m_window->minimumSize());
    const QPoint centre = m_window->geometry().center();
    QRect r(QPoint(centre.x() - size.width() / 2, centre.y() - size.height() / 2), size);
    m_window->setGeometry(WindowSnap::keepOnScreen(r, area));
    saveGeometry();
}

void WindowController::center()
{
    if (!m_window) return;
    const QRect area = workAreaFor(m_window->geometry());
    QRect r = m_window->geometry();
    r.moveCenter(area.center());
    m_window->setGeometry(WindowSnap::keepOnScreen(r, area));
}

void WindowController::setMini(bool on)
{
    if (!m_window) return;
    if (m_window->visibility() == QWindow::FullScreen) m_window->showNormal();
    if (on) {
        if (!m_beforeMini.isValid()) m_beforeMini = m_window->geometry();
        setMaximized(false);
        const QRect area = workAreaFor(m_window->geometry());
        const QSize size(480, 270);
        m_window->setGeometry(QRect(area.right() + 1 - size.width() - 16, area.bottom() + 1 - size.height() - 16,
                                    size.width(), size.height()));
    } else if (m_beforeMini.isValid()) {
        m_window->setGeometry(m_beforeMini);
        m_beforeMini = {};
    }
}

void WindowController::restoreGeometry()
{
    if (!m_window) return;
    const QRect saved = m_settings.value(QStringLiteral("window/geometry")).toRect();
    const QRect normal = m_settings.value(QStringLiteral("window/normalGeometry")).toRect();
    if (saved.isValid()) {
        m_window->setGeometry(WindowSnap::keepOnScreen(saved, workAreaFor(saved)));
        m_normalGeometry = normal;
        setMaximized(m_settings.value(QStringLiteral("window/maximized"), false).toBool());
    } else {
        center();
    }
}

void WindowController::saveGeometry()
{
    if (!m_window || m_window->visibility() == QWindow::FullScreen) return;
    m_settings.setValue(QStringLiteral("window/geometry"), m_window->geometry());
    m_settings.setValue(QStringLiteral("window/normalGeometry"), m_normalGeometry);
    m_settings.setValue(QStringLiteral("window/maximized"), m_maximized);
}
