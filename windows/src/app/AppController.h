#pragma once

#include "core/ResumeStore.h"

#include <QObject>
#include <QPointer>
#include <QSettings>
#include <QStringList>
#include <QUrl>
#include <QVariantList>
#include <QWindow>

/**
 * App-wide state for QML ("app"): settings, resume positions, recent files,
 * window behaviour (always on top, keep awake) and files from the command line.
 */
class AppController : public QObject
{
    Q_OBJECT
    Q_PROPERTY(bool alwaysOnTop READ alwaysOnTop WRITE setAlwaysOnTop NOTIFY alwaysOnTopChanged)
    Q_PROPERTY(double volume READ volume WRITE setVolume NOTIFY volumeChanged)
    Q_PROPERTY(bool showRemaining READ showRemaining WRITE setShowRemaining NOTIFY showRemainingChanged)
    Q_PROPERTY(bool hardwareDecoding READ hardwareDecoding WRITE setHardwareDecoding NOTIFY hardwareDecodingChanged)
    Q_PROPERTY(QVariantList recent READ recent NOTIFY recentChanged)
    Q_PROPERTY(QString version READ version CONSTANT)
    Q_PROPERTY(QStringList startupFiles READ startupFiles CONSTANT)
    Q_PROPERTY(QStringList fileFilters READ fileFilters CONSTANT)

public:
    explicit AppController(QStringList startupFiles, QObject *parent = nullptr);

    void setWindow(QWindow *window);

    bool alwaysOnTop() const { return m_alwaysOnTop; }
    void setAlwaysOnTop(bool onTop);
    double volume() const { return m_volume; }
    void setVolume(double v);
    bool showRemaining() const { return m_showRemaining; }
    void setShowRemaining(bool v);
    bool hardwareDecoding() const { return m_hardwareDecoding; }
    void setHardwareDecoding(bool v);
    QVariantList recent() const;
    QString version() const;
    QStringList startupFiles() const { return m_startupFiles; }
    QStringList fileFilters() const;

    Q_INVOKABLE qint64 startPositionFor(const QString &location) const;
    Q_INVOKABLE void saveProgress(const QString &location, const QString &title, qint64 positionMs,
                                  qint64 durationMs, bool finished);
    Q_INVOKABLE void forget(const QString &location);
    Q_INVOKABLE void keepAwake(bool awake);
    Q_INVOKABLE QString displayName(const QString &location) const;
    Q_INVOKABLE bool isMedia(const QString &location) const;
    Q_INVOKABLE bool isSubtitle(const QString &location) const;
    Q_INVOKABLE bool isUrl(const QString &location) const;
    Q_INVOKABLE bool isPlaylist(const QString &location) const;
    /** file:///C:/x.mkv -> C:/x.mkv; URLs and paths pass through. */
    Q_INVOKABLE QString toLocation(const QUrl &url) const;
    /** Path or URL text back to a QUrl (for dialogs and tools). */
    Q_INVOKABLE QUrl toUrl(const QString &location) const;
    Q_INVOKABLE QString formatClock(qint64 ms) const;
    Q_INVOKABLE QString formatClockRemaining(qint64 durationMs, qint64 positionMs) const;
    Q_INVOKABLE QString formatSpeed(double speed) const;
    Q_INVOKABLE QString remainingLabel(qint64 durationMs, qint64 positionMs) const;
    Q_INVOKABLE double stepSpeed(double current, bool up) const;
    Q_INVOKABLE QVariantList speedPresets() const;
    /** Generic persisted settings for the player ("player/autoQueueFolder", ...). */
    Q_INVOKABLE QVariant setting(const QString &key, const QVariant &fallback) const;
    Q_INVOKABLE void setSetting(const QString &key, const QVariant &value);
    Q_INVOKABLE QString lastFolder() const;
    Q_INVOKABLE void setLastFolder(const QUrl &folder);

signals:
    void alwaysOnTopChanged();
    void volumeChanged();
    void showRemainingChanged();
    void hardwareDecodingChanged();
    void recentChanged();
    /** Another launch asked to open these files (single instance comes in phase 2). */
    void openRequested(const QStringList &files);

private:
    QSettings m_settings;
    ResumeStore m_resume;
    QPointer<QWindow> m_window;
    QStringList m_startupFiles;
    bool m_alwaysOnTop = false;
    double m_volume = 100;
    bool m_showRemaining = false;
    bool m_hardwareDecoding = true;
};
