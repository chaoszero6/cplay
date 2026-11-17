#include "AppController.h"

#include "core/MediaFiles.h"
#include "core/PlaybackRules.h"
#include "core/TimeFormat.h"
#include "platform/Platform.h"

#include <QDateTime>
#include <QFileInfo>
#include <QStandardPaths>
#include <QUrl>

static QString dataFile(const QString &name)
{
    return QStandardPaths::writableLocation(QStandardPaths::AppDataLocation) + u'/' + name;
}

AppController::AppController(QStringList startupFiles, QObject *parent)
    : QObject(parent),
      m_settings(QStandardPaths::writableLocation(QStandardPaths::AppDataLocation) + QStringLiteral("/settings.ini"),
                 QSettings::IniFormat),
      m_resume(dataFile(QStringLiteral("history.json"))),
      m_startupFiles(std::move(startupFiles))
{
    m_resume.load();
    m_alwaysOnTop = m_settings.value(QStringLiteral("window/alwaysOnTop"), false).toBool();
    m_volume = m_settings.value(QStringLiteral("player/volume"), 100.0).toDouble();
    m_showRemaining = m_settings.value(QStringLiteral("player/showRemaining"), false).toBool();
    m_hardwareDecoding = m_settings.value(QStringLiteral("player/hardwareDecoding"), true).toBool();
}

void AppController::setHardwareDecoding(bool v)
{
    if (v == m_hardwareDecoding) return;
    m_hardwareDecoding = v;
    m_settings.setValue(QStringLiteral("player/hardwareDecoding"), v);
    emit hardwareDecodingChanged();
}

void AppController::setWindow(QWindow *window)
{
    m_window = window;
    Platform::useDarkTitleBar(window, true);
    if (m_alwaysOnTop) Platform::setAlwaysOnTop(window, true);
}

void AppController::setAlwaysOnTop(bool onTop)
{
    if (onTop == m_alwaysOnTop) return;
    m_alwaysOnTop = onTop;
    Platform::setAlwaysOnTop(m_window, onTop);
    m_settings.setValue(QStringLiteral("window/alwaysOnTop"), onTop);
    emit alwaysOnTopChanged();
}

void AppController::setVolume(double v)
{
    v = std::clamp(v, 0.0, 200.0);
    if (qFuzzyCompare(v, m_volume)) return;
    m_volume = v;
    m_settings.setValue(QStringLiteral("player/volume"), v);
    emit volumeChanged();
}

void AppController::setShowRemaining(bool v)
{
    if (v == m_showRemaining) return;
    m_showRemaining = v;
    m_settings.setValue(QStringLiteral("player/showRemaining"), v);
    emit showRemainingChanged();
}

QVariantList AppController::recent() const
{
    QVariantList out;
    for (const auto &e : m_resume.recent(30)) {
        const bool local = !MediaFiles::isUrl(e.key);
        if (local && !QFileInfo::exists(e.key)) continue; // moved or deleted since
        out << QVariantMap{
            {"location", e.key},
            {"title", e.title.isEmpty() ? MediaFiles::displayName(e.key) : e.title},
            {"positionMs", e.positionMs},
            {"durationMs", e.durationMs},
            {"progress", TimeFormat::progressFraction(e.positionMs, e.durationMs)},
            {"finished", PlaybackRules::isFinished(e.positionMs, e.durationMs)},
            {"detail", local ? QFileInfo(e.key).absolutePath() : QUrl(e.key).host()},
            {"remaining", TimeFormat::remainingShortLabel(e.durationMs, e.positionMs)},
        };
    }
    return out;
}

QString AppController::version() const { return QStringLiteral(CHAOSPLAY_VERSION); }

QStringList AppController::fileFilters() const { return MediaFiles::openDialogFilters(); }

qint64 AppController::startPositionFor(const QString &location) const
{
    return m_resume.startPositionFor(location);
}

void AppController::saveProgress(const QString &location, const QString &title, qint64 positionMs,
                                 qint64 durationMs, bool finished)
{
    if (location.isEmpty() || (durationMs <= 0 && positionMs <= 0)) return;
    m_resume.update(location, title, positionMs, durationMs, finished, QDateTime::currentMSecsSinceEpoch());
    m_resume.save();
    emit recentChanged();
}

void AppController::forget(const QString &location)
{
    m_resume.remove(location);
    m_resume.save();
    emit recentChanged();
}

void AppController::keepAwake(bool awake) { Platform::keepAwake(awake); }
QString AppController::displayName(const QString &location) const { return MediaFiles::displayName(location); }
bool AppController::isMedia(const QString &location) const { return MediaFiles::isMedia(location) || MediaFiles::isUrl(location); }
bool AppController::isSubtitle(const QString &location) const { return MediaFiles::isSubtitle(location); }

QString AppController::toLocation(const QUrl &url) const
{
    return url.isLocalFile() ? url.toLocalFile() : url.toString();
}

QString AppController::formatClock(qint64 ms) const { return TimeFormat::formatClock(ms); }
QString AppController::formatClockRemaining(qint64 d, qint64 p) const { return TimeFormat::formatClockRemaining(d, p); }
QString AppController::formatSpeed(double s) const { return TimeFormat::formatSpeed(s); }
QString AppController::remainingLabel(qint64 d, qint64 p) const { return TimeFormat::remainingShortLabel(d, p); }
double AppController::stepSpeed(double current, bool up) const { return PlaybackRules::stepSpeed(current, up); }

QVariantList AppController::speedPresets() const
{
    QVariantList out;
    for (double s : PlaybackRules::speedPresets()) out << s;
    return out;
}

QString AppController::lastFolder() const
{
    const QString saved = m_settings.value(QStringLiteral("files/lastFolder")).toString();
    if (!saved.isEmpty() && QFileInfo(QUrl(saved).toLocalFile()).isDir()) return saved;
    return QUrl::fromLocalFile(QStandardPaths::writableLocation(QStandardPaths::MoviesLocation)).toString();
}

void AppController::setLastFolder(const QUrl &folder)
{
    m_settings.setValue(QStringLiteral("files/lastFolder"), folder.toString());
}
