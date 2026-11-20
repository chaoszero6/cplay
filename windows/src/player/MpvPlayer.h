#pragma once

#include <QQuickFramebufferObject>
#include <QVariantList>
#include <QtQml/qqmlregistration.h>
#include <memory>

struct mpv_handle;
struct mpv_render_context;
struct mpv_event;

/**
 * The video surface and player core in one QML item ("MpvPlayer").
 *
 * libmpv decodes on its own threads; this item only drains mpv's event
 * queue on the UI thread (woken by mpv's wakeup callback) and renders the
 * current frame into Qt Quick's OpenGL framebuffer (spec 8.1).
 * All setters are asynchronous so the UI never waits on the decoder.
 */
class MpvPlayer : public QQuickFramebufferObject
{
    Q_OBJECT
    QML_ELEMENT

    Q_PROPERTY(QString source READ source NOTIFY sourceChanged)
    Q_PROPERTY(QString title READ title NOTIFY titleChanged)
    Q_PROPERTY(qint64 position READ position NOTIFY positionChanged)
    Q_PROPERTY(qint64 duration READ duration NOTIFY durationChanged)
    Q_PROPERTY(bool paused READ paused NOTIFY pausedChanged)
    Q_PROPERTY(bool buffering READ buffering NOTIFY bufferingChanged)
    Q_PROPERTY(bool idle READ idle NOTIFY idleChanged)
    Q_PROPERTY(bool ended READ ended NOTIFY endedChanged)
    Q_PROPERTY(double volume READ volume NOTIFY volumeChanged)
    Q_PROPERTY(bool muted READ muted NOTIFY mutedChanged)
    Q_PROPERTY(double speed READ speed NOTIFY speedChanged)
    Q_PROPERTY(int videoWidth READ videoWidth NOTIFY videoSizeChanged)
    Q_PROPERTY(int videoHeight READ videoHeight NOTIFY videoSizeChanged)
    Q_PROPERTY(QVariantList audioTracks READ audioTracks NOTIFY tracksChanged)
    Q_PROPERTY(QVariantList subtitleTracks READ subtitleTracks NOTIFY tracksChanged)
    Q_PROPERTY(int audioId READ audioId NOTIFY tracksChanged)
    Q_PROPERTY(int subtitleId READ subtitleId NOTIFY tracksChanged)
    Q_PROPERTY(QString hwdec READ hwdec NOTIFY hwdecChanged)
    Q_PROPERTY(QString errorString READ errorString NOTIFY errorStringChanged)
    Q_PROPERTY(bool firstFrameShown READ firstFrameShown NOTIFY firstFrameShownChanged)

public:
    explicit MpvPlayer(QQuickItem *parent = nullptr);
    ~MpvPlayer() override;

    Renderer *createRenderer() const override;

    QString source() const { return m_source; }
    QString title() const { return m_title; }
    qint64 position() const { return m_position; }
    qint64 duration() const { return m_duration; }
    bool paused() const { return m_paused; }
    bool buffering() const { return m_buffering; }
    bool idle() const { return m_idle; }
    bool ended() const { return m_ended; }
    double volume() const { return m_volume; }
    bool muted() const { return m_muted; }
    double speed() const { return m_speed; }
    int videoWidth() const { return m_videoWidth; }
    int videoHeight() const { return m_videoHeight; }
    QVariantList audioTracks() const { return m_audioTracks; }
    QVariantList subtitleTracks() const { return m_subtitleTracks; }
    int audioId() const { return m_audioId; }
    int subtitleId() const { return m_subtitleId; }
    QString hwdec() const { return m_hwdec; }
    QString errorString() const { return m_error; }
    bool firstFrameShown() const { return m_firstFrame; }

    /** Opens a file path or URL, starting at [startMs] (0 = beginning). */
    Q_INVOKABLE void open(const QString &location, qint64 startMs = 0);
    Q_INVOKABLE void stop();
    Q_INVOKABLE void play();
    Q_INVOKABLE void pause();
    Q_INVOKABLE void togglePause();
    Q_INVOKABLE void seekTo(qint64 ms);
    Q_INVOKABLE void seekBy(qint64 deltaMs);
    Q_INVOKABLE void frameStep(bool forward);
    Q_INVOKABLE void setSpeed(double speed);
    Q_INVOKABLE void setVolume(double volume);
    Q_INVOKABLE void setMuted(bool muted);
    Q_INVOKABLE void setAudioTrack(int id);    // 0 = off
    Q_INVOKABLE void setSubtitleTrack(int id); // 0 = off
    Q_INVOKABLE void addSubtitle(const QString &path);
    Q_INVOKABLE void screenshot();
    /** Generic escape hatch: set any mpv property ("audio-delay", "hwdec", ...). */
    Q_INVOKABLE void setMpvProperty(const QString &name, const QVariant &value);
    Q_INVOKABLE QVariant mpvProperty(const QString &name) const;
    /** Any mpv command, e.g. ["sub-reload", "2"]. */
    Q_INVOKABLE void mpvCommand(const QStringList &args) { command(args); }

    // Used by the renderer (render thread)
    std::shared_ptr<mpv_handle> handle() const { return m_mpv; }

signals:
    void sourceChanged();
    void titleChanged();
    void positionChanged();
    void durationChanged();
    void pausedChanged();
    void bufferingChanged();
    void idleChanged();
    void endedChanged();
    void volumeChanged();
    void mutedChanged();
    void speedChanged();
    void videoSizeChanged();
    void tracksChanged();
    void hwdecChanged();
    void errorStringChanged();
    void firstFrameShownChanged();
    /** A file finished loading and is about to play. */
    void fileLoaded();
    /** Playback reached the end of the file (the last frame stays on screen). */
    void endReached();
    void screenshotSaved(const QString &path);

private slots:
    void drainEvents();
    void onRendererReady();
    void onRendererFailed();

private:
    void handleEvent(const mpv_event *event);
    void command(const QStringList &args);
    void setProp(const char *name, const QString &value);
    void refreshTracks();

    std::shared_ptr<mpv_handle> m_mpv;
    QString m_source;
    QString m_title;
    qint64 m_position = 0;
    qint64 m_duration = 0;
    qint64 m_pendingStartMs = 0;
    bool m_paused = true;
    bool m_buffering = false;
    bool m_idle = true;
    bool m_ended = false;
    double m_volume = 100;
    bool m_muted = false;
    double m_speed = 1.0;
    int m_videoWidth = 0;
    int m_videoHeight = 0;
    QVariantList m_audioTracks;
    QVariantList m_subtitleTracks;
    int m_audioId = 0;
    int m_subtitleId = 0;
    QString m_hwdec;
    QString m_error;
    bool m_firstFrame = false;
    bool m_rendererReady = false;
    QStringList m_pendingLoad;
    QStringList m_pendingSubtitles;
};
