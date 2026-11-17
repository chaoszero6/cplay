#pragma once
#include <QImage>
#include <QMutex>
#include <QSize>
#include <QString>

struct mpv_handle;
struct mpv_render_context;

/**
 * Grabs single frames for the seek-bar preview with its own hidden libmpv
 * instance (software rendering, no audio, keyframe seeks). Thread-safe:
 * one grab at a time. Works for local files and network URLs.
 */
class FramePreviewer
{
public:
    FramePreviewer();
    ~FramePreviewer();
    FramePreviewer(const FramePreviewer &) = delete;
    FramePreviewer &operator=(const FramePreviewer &) = delete;

    /** Frame of [source] near [positionMs], fitted inside [maxSize]; null image on failure. */
    QImage frameAt(const QString &source, qint64 positionMs, QSize maxSize, int timeoutMs = 4000);

private:
    bool open(const QString &source, int timeoutMs);
    bool waitFor(int eventId, int timeoutMs);

    QMutex m_lock;
    mpv_handle *m_mpv = nullptr;
    mpv_render_context *m_render = nullptr;
    QString m_source;
    bool m_loaded = false;
};
