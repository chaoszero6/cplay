#include "MpvPlayer.h"
#include "FramePreviewer.h"
#include "LocationResolver.h"

#include <mpv/client.h>
#include <mpv/render_gl.h>

#include <QDir>
#include <QOpenGLContext>
#include <QOpenGLExtraFunctions>
#include <QOpenGLFramebufferObject>
#include <QQuickOpenGLUtils>
#include <QQuickWindow>
#include <QStandardPaths>
#include <QUrl>
#include <cmath>

namespace {

void *getProcAddress(void *, const char *name)
{
    QOpenGLContext *ctx = QOpenGLContext::currentContext();
    return ctx ? reinterpret_cast<void *>(ctx->getProcAddress(QByteArray(name))) : nullptr;
}

QVariant nodeToVariant(const mpv_node *node)
{
    switch (node->format) {
    case MPV_FORMAT_STRING: return QString::fromUtf8(node->u.string);
    case MPV_FORMAT_FLAG: return bool(node->u.flag);
    case MPV_FORMAT_INT64: return qint64(node->u.int64);
    case MPV_FORMAT_DOUBLE: return node->u.double_;
    case MPV_FORMAT_NODE_ARRAY: {
        QVariantList list;
        for (int i = 0; i < node->u.list->num; ++i) list.append(nodeToVariant(&node->u.list->values[i]));
        return list;
    }
    case MPV_FORMAT_NODE_MAP: {
        QVariantMap map;
        for (int i = 0; i < node->u.list->num; ++i)
            map.insert(QString::fromUtf8(node->u.list->keys[i]), nodeToVariant(&node->u.list->values[i]));
        return map;
    }
    default: return {};
    }
}

/** "English · AC3 5.1" style label for a track. */
QString trackLabel(const QVariantMap &t)
{
    QStringList parts;
    const QString title = t.value("title").toString();
    const QString lang = t.value("lang").toString();
    if (!title.isEmpty()) parts << title;
    if (!lang.isEmpty() && !title.contains(lang, Qt::CaseInsensitive)) parts << lang.toUpper();
    QString codec = t.value("codec").toString().toUpper();
    if (codec == QLatin1String("SUBRIP")) codec = QStringLiteral("SRT");
    const int channels = t.value("demux-channel-count").toInt();
    if (channels == 6) codec += QStringLiteral(" 5.1");
    else if (channels == 8) codec += QStringLiteral(" 7.1");
    else if (channels == 2) codec += QStringLiteral(" stereo");
    if (!codec.isEmpty()) parts << codec;
    if (t.value("external").toBool()) parts << QStringLiteral("external");
    if (parts.isEmpty()) parts << QStringLiteral("Track %1").arg(t.value("id").toInt());
    return parts.join(QStringLiteral(" · "));
}

enum ObservedId : uint64_t {
    ObsTimePos = 1, ObsDuration, ObsPause, ObsCache, ObsIdle, ObsEof, ObsVolume, ObsMute, ObsSpeed,
    ObsTracks, ObsWidth, ObsHeight, ObsTitle, ObsHwdec, ObsAid, ObsSid
};

class MpvRenderer : public QQuickFramebufferObject::Renderer
{
public:
    explicit MpvRenderer(const MpvPlayer *item) : m_item(item), m_mpv(item->handle()) {}

    ~MpvRenderer() override
    {
        // Freed on the render thread, before the mpv handle (shared_ptr) can go
        if (m_ctx) mpv_render_context_free(m_ctx);
    }

    QOpenGLFramebufferObject *createFramebufferObject(const QSize &size) override
    {
        if (qEnvironmentVariableIsSet("CHAOSPLAY_DEBUG_RENDER")) qInfo("render: new framebuffer %dx%d", size.width(), size.height());
        return QQuickFramebufferObject::Renderer::createFramebufferObject(size);
    }

    /** mpv's GL renderer is created in the render phase (not during scene-graph sync). */
    bool ensureContext()
    {
        if (m_ctx) return true;
        if (m_failed) return false;
        mpv_opengl_init_params gl{getProcAddress, nullptr};
        mpv_render_param params[]{
            {MPV_RENDER_PARAM_API_TYPE, const_cast<char *>(MPV_RENDER_API_TYPE_OPENGL)},
            {MPV_RENDER_PARAM_OPENGL_INIT_PARAMS, &gl},
            {MPV_RENDER_PARAM_INVALID, nullptr}};
        auto *item = const_cast<MpvPlayer *>(m_item);
        QQuickOpenGLUtils::resetOpenGLState();
        if (mpv_render_context_create(&m_ctx, m_mpv.get(), params) < 0) {
            qWarning("CPlay: could not create the mpv OpenGL renderer");
            m_ctx = nullptr;
            m_failed = true;
            QMetaObject::invokeMethod(item, "onRendererFailed", Qt::QueuedConnection);
            return false;
        }
        QQuickOpenGLUtils::resetOpenGLState();
        mpv_render_context_set_update_callback(m_ctx, [](void *ctx) {
            QMetaObject::invokeMethod(static_cast<MpvPlayer *>(ctx), "update", Qt::QueuedConnection);
        }, item);
        if (qEnvironmentVariableIsSet("CHAOSPLAY_DEBUG_RENDER")) qInfo("render: mpv renderer created");
        // Files opened before this point were held back: load them now
        QMetaObject::invokeMethod(item, "onRendererReady", Qt::QueuedConnection);
        return true;
    }

    void render() override
    {
        if (!ensureContext()) return;
        // Qt Quick leaves its own GL state bound (buffers, VAO, unpack settings);
        // mpv expects a clean default state, or its video uploads come out black
        QQuickOpenGLUtils::resetOpenGLState();
        // resetOpenGLState() does not cover pixel-transfer state; Qt's glyph-cache
        // uploads (text, icon font) leave it changed and mpv's video plane uploads
        // then come out black while its OSD still draws
        if (QOpenGLContext *ctx = QOpenGLContext::currentContext()) {
            QOpenGLExtraFunctions *f = ctx->extraFunctions();
            f->glBindBuffer(GL_PIXEL_UNPACK_BUFFER, 0);
            f->glBindBuffer(GL_PIXEL_PACK_BUFFER, 0);
            f->glPixelStorei(GL_UNPACK_ALIGNMENT, 4);
            f->glPixelStorei(GL_UNPACK_ROW_LENGTH, 0);
            f->glPixelStorei(GL_UNPACK_SKIP_ROWS, 0);
            f->glPixelStorei(GL_UNPACK_SKIP_PIXELS, 0);
            f->glPixelStorei(GL_PACK_ALIGNMENT, 4);
            f->glPixelStorei(GL_PACK_ROW_LENGTH, 0);
            f->glDisable(GL_SCISSOR_TEST);
            f->glDisable(GL_STENCIL_TEST);
            f->glDisable(GL_DEPTH_TEST);
            f->glColorMask(GL_TRUE, GL_TRUE, GL_TRUE, GL_TRUE);
        }
        QOpenGLFramebufferObject *target = framebufferObject();
        mpv_opengl_fbo fbo{int(target->handle()), target->width(), target->height(), 0};
        int flipY = 0;
        mpv_render_param params[]{
            {MPV_RENDER_PARAM_OPENGL_FBO, &fbo},
            {MPV_RENDER_PARAM_FLIP_Y, &flipY},
            {MPV_RENDER_PARAM_INVALID, nullptr}};
        mpv_render_context_render(m_ctx, params);
        QQuickOpenGLUtils::resetOpenGLState();
        if (qEnvironmentVariableIsSet("CHAOSPLAY_DEBUG_RENDER") && (++m_frames % 30 == 1))
            qInfo("render: frame %d into fbo %u %dx%d", m_frames, target->handle(), target->width(), target->height());
    }

private:
    const MpvPlayer *m_item;
    std::shared_ptr<mpv_handle> m_mpv;
    mpv_render_context *m_ctx = nullptr;
    bool m_failed = false;
    int m_frames = 0;
};

} // namespace

MpvPlayer::MpvPlayer(QQuickItem *parent) : QQuickFramebufferObject(parent)
{
    mpv_handle *h = mpv_create();
    if (!h) qFatal("CPlay: libmpv could not be initialised");
    m_mpv.reset(h, [](mpv_handle *p) { mpv_terminate_destroy(p); });

    const QString shots = QStandardPaths::writableLocation(QStandardPaths::PicturesLocation) + QStringLiteral("/ChaosPlay");
    const std::pair<const char *, QByteArray> options[] = {
        {"vo", "libmpv"},
        {"hwdec", "auto-safe"},          // GPU decoding where it is reliable (spec 8.1)
        {"keep-open", "yes"},            // last frame stays; replay after the end just works
        {"idle", "yes"},
        {"osc", "no"},
        {"input-default-bindings", "no"},
        {"input-vo-keyboard", "no"},
        {"terminal", "no"},
        {"ytdl", "no"},
        {"sub-auto", "fuzzy"},           // "Movie.en.srt" next to "Movie.mkv" loads by itself
        {"audio-client-name", "CPlay"},
        {"screenshot-directory", QDir::toNativeSeparators(shots).toUtf8()},
        {"screenshot-template", "CPlay-%F-%P"},
        {"volume-max", "200"},
        {"cache", "yes"},
        {"demuxer-max-bytes", "150MiB"},
    };
    for (const auto &[name, value] : options) mpv_set_option_string(h, name, value.constData());
    // Decoder override for support and tests ("no" = software); Settings sets it too
    const QByteArray hw = qgetenv("CHAOSPLAY_HWDEC");
    if (!hw.isEmpty()) mpv_set_option_string(h, "hwdec", hw.constData());
    if (mpv_initialize(h) < 0) qFatal("CPlay: libmpv failed to start");

    mpv_observe_property(h, ObsTimePos, "time-pos", MPV_FORMAT_DOUBLE);
    mpv_observe_property(h, ObsDuration, "duration", MPV_FORMAT_DOUBLE);
    mpv_observe_property(h, ObsPause, "pause", MPV_FORMAT_FLAG);
    mpv_observe_property(h, ObsCache, "paused-for-cache", MPV_FORMAT_FLAG);
    mpv_observe_property(h, ObsIdle, "idle-active", MPV_FORMAT_FLAG);
    mpv_observe_property(h, ObsEof, "eof-reached", MPV_FORMAT_FLAG);
    mpv_observe_property(h, ObsVolume, "volume", MPV_FORMAT_DOUBLE);
    mpv_observe_property(h, ObsMute, "mute", MPV_FORMAT_FLAG);
    mpv_observe_property(h, ObsSpeed, "speed", MPV_FORMAT_DOUBLE);
    mpv_observe_property(h, ObsTracks, "track-list", MPV_FORMAT_NODE);
    mpv_observe_property(h, ObsWidth, "video-params/dw", MPV_FORMAT_INT64);
    mpv_observe_property(h, ObsHeight, "video-params/dh", MPV_FORMAT_INT64);
    mpv_observe_property(h, ObsTitle, "media-title", MPV_FORMAT_STRING);
    mpv_observe_property(h, ObsHwdec, "hwdec-current", MPV_FORMAT_STRING);
    mpv_observe_property(h, ObsAid, "aid", MPV_FORMAT_INT64);
    mpv_observe_property(h, ObsSid, "sid", MPV_FORMAT_INT64);

    // mpv warnings and errors go to the Qt log (CPlay.log support later)
    mpv_request_log_messages(h, "warn");

    mpv_set_wakeup_callback(h, [](void *ctx) {
        QMetaObject::invokeMethod(static_cast<MpvPlayer *>(ctx), "drainEvents", Qt::QueuedConnection);
    }, this);
}

MpvPlayer::~MpvPlayer()
{
    // Stop mpv calling back into a dead object; the renderer may still hold
    // the handle a little longer on the render thread (shared_ptr).
    if (m_mpv) mpv_set_wakeup_callback(m_mpv.get(), nullptr, nullptr);
}

QQuickFramebufferObject::Renderer *MpvPlayer::createRenderer() const
{
    return new MpvRenderer(this);
}

void MpvPlayer::drainEvents()
{
    while (m_mpv) {
        mpv_event *event = mpv_wait_event(m_mpv.get(), 0);
        if (event->event_id == MPV_EVENT_NONE) break;
        handleEvent(event);
    }
}

void MpvPlayer::handleEvent(const mpv_event *event)
{
    switch (event->event_id) {
    case MPV_EVENT_PROPERTY_CHANGE: {
        const auto *prop = static_cast<mpv_event_property *>(event->data);
        const bool has = prop->format != MPV_FORMAT_NONE && prop->data;
        auto dbl = [&] { return has ? *static_cast<double *>(prop->data) : 0.0; };
        auto flag = [&] { return has && *static_cast<int *>(prop->data); };
        auto i64 = [&] { return has ? *static_cast<int64_t *>(prop->data) : int64_t(0); };
        auto str = [&] { return has ? QString::fromUtf8(*static_cast<char **>(prop->data)) : QString(); };
        switch (event->reply_userdata) {
        case ObsTimePos: {
            const qint64 p = qint64(std::llround(dbl() * 1000.0));
            if (p != m_position) { m_position = p; emit positionChanged(); }
            if (has && !m_firstFrame) { m_firstFrame = true; emit firstFrameShownChanged(); }
            break;
        }
        case ObsDuration: {
            const qint64 d = qint64(std::llround(dbl() * 1000.0));
            if (d != m_duration) { m_duration = d; emit durationChanged(); }
            break;
        }
        case ObsPause: if (flag() != m_paused) { m_paused = flag(); emit pausedChanged(); } break;
        case ObsCache: if (flag() != m_buffering) { m_buffering = flag(); emit bufferingChanged(); } break;
        case ObsIdle: if (flag() != m_idle) { m_idle = flag(); emit idleChanged(); } break;
        case ObsEof: {
            const bool e = flag();
            if (e != m_ended) {
                m_ended = e;
                emit endedChanged();
                if (e) emit endReached();
            }
            break;
        }
        case ObsVolume: if (dbl() != m_volume) { m_volume = dbl(); emit volumeChanged(); } break;
        case ObsMute: if (flag() != m_muted) { m_muted = flag(); emit mutedChanged(); } break;
        case ObsSpeed: if (has && dbl() != m_speed) { m_speed = dbl(); emit speedChanged(); } break;
        case ObsTracks: refreshTracks(); break;
        case ObsWidth: if (int(i64()) != m_videoWidth) { m_videoWidth = int(i64()); emit videoSizeChanged(); } break;
        case ObsHeight: if (int(i64()) != m_videoHeight) { m_videoHeight = int(i64()); emit videoSizeChanged(); } break;
        case ObsTitle: if (str() != m_title) { m_title = str(); emit titleChanged(); } break;
        case ObsHwdec: if (str() != m_hwdec) { m_hwdec = str(); emit hwdecChanged(); } break;
        case ObsAid: if (int(i64()) != m_audioId) { m_audioId = int(i64()); emit tracksChanged(); } break;
        case ObsSid: if (int(i64()) != m_subtitleId) { m_subtitleId = int(i64()); emit tracksChanged(); } break;
        }
        break;
    }
    case MPV_EVENT_FILE_LOADED:
        if (!m_error.isEmpty()) { m_error.clear(); emit errorStringChanged(); }
        // Server-side subtitles (Jellyfin, DLNA): offered, not selected
        for (const QString &sub : std::as_const(m_pendingSubtitles))
            command({QStringLiteral("sub-add"), sub, QStringLiteral("auto")});
        m_pendingSubtitles.clear();
        emit fileLoaded();
        break;
    case MPV_EVENT_END_FILE: {
        const auto *end = static_cast<mpv_event_end_file *>(event->data);
        if (end->reason == MPV_END_FILE_REASON_ERROR) {
            m_error = QString::fromUtf8(mpv_error_string(end->error));
            if (m_error.contains(QLatin1String("unrecognized file format")))
                m_error = QStringLiteral("This file is not a video CPlay can read.");
            else if (m_error.contains(QLatin1String("loading failed")))
                m_error = QStringLiteral("Could not open this video. Check the file or the network connection.");
            emit errorStringChanged();
        }
        break;
    }
    case MPV_EVENT_LOG_MESSAGE: {
        const auto *msg = static_cast<mpv_event_log_message *>(event->data);
        qWarning("mpv[%s] %s: %s", msg->level, msg->prefix, QByteArray(msg->text).trimmed().constData());
        break;
    }
    case MPV_EVENT_COMMAND_REPLY: {
        // screenshot-to-file replies carry no path; report the folder instead
        if (event->reply_userdata == 77 && event->error >= 0) {
            QVariant dir = mpvProperty(QStringLiteral("screenshot-directory"));
            emit screenshotSaved(dir.toString());
        }
        break;
    }
    default:
        break;
    }
}

void MpvPlayer::refreshTracks()
{
    mpv_node node;
    if (mpv_get_property(m_mpv.get(), "track-list", MPV_FORMAT_NODE, &node) < 0) return;
    const QVariantList all = nodeToVariant(&node).toList();
    mpv_free_node_contents(&node);
    QVariantList audio, subs;
    for (const QVariant &v : all) {
        const QVariantMap t = v.toMap();
        QVariantMap item{
            {"id", t.value("id").toInt()},
            {"label", trackLabel(t)},
            {"selected", t.value("selected").toBool()},
            {"codec", t.value("codec").toString()},
            {"lang", t.value("lang").toString()},
        };
        const QString type = t.value("type").toString();
        if (type == QLatin1String("audio")) audio << item;
        else if (type == QLatin1String("sub")) subs << item;
    }
    m_audioTracks = audio;
    m_subtitleTracks = subs;
    emit tracksChanged();
}

void MpvPlayer::command(const QStringList &args)
{
    QList<QByteArray> utf8;
    for (const QString &a : args) utf8 << a.toUtf8();
    QList<const char *> argv;
    for (const QByteArray &a : utf8) argv << a.constData();
    argv << nullptr;
    mpv_command_async(m_mpv.get(), 0, argv.data());
}

void MpvPlayer::setProp(const char *name, const QString &value)
{
    const QByteArray v = value.toUtf8();
    const char *data = v.constData();
    mpv_set_property_async(m_mpv.get(), 0, name, MPV_FORMAT_STRING, &data);
}

void MpvPlayer::onRendererReady()
{
    m_rendererReady = true;
    if (!m_pendingLoad.isEmpty()) {
        const QStringList args = m_pendingLoad;
        m_pendingLoad.clear();
        command(args);
        setProp("pause", QStringLiteral("no"));
    }
}

void MpvPlayer::onRendererFailed()
{
    m_pendingLoad.clear();
    m_error = QStringLiteral("Video output could not start. Update the graphics driver, or start CPlay with "
                             "--software-opengl.");
    emit errorStringChanged();
}

void MpvPlayer::open(const QString &location, qint64 startMs)
{
    const ResolvedLocation resolved = LocationResolver::resolve(location);
    const QString target = resolved.url;
    m_pendingSubtitles = resolved.subtitles;
    m_source = location;
    m_pendingStartMs = startMs;
    m_firstFrame = false;
    m_ended = false;
    m_position = startMs;
    if (!m_error.isEmpty()) { m_error.clear(); emit errorStringChanged(); }
    emit sourceChanged();
    emit firstFrameShownChanged();
    if (!resolved.error.isEmpty() || target.isEmpty()) {
        m_error = resolved.error.isEmpty() ? QStringLiteral("Could not open this video.") : resolved.error;
        emit errorStringChanged();
        return;
    }
    if (m_mpv) FramePreviewer::setHeaders(m_mpv.get(), resolved.httpHeaders);
    emit endedChanged();
    emit positionChanged();
    QStringList args{QStringLiteral("loadfile"), target, QStringLiteral("replace")};
    if (startMs > 0) {
        // mpv 0.38+: loadfile <url> <flags> <index> <options>
        args << QStringLiteral("-1") << QStringLiteral("start=%1").arg(double(startMs) / 1000.0, 0, 'f', 3);
    }
    if (!m_rendererReady) {
        // Loading before the OpenGL renderer exists leaves mpv without video output
        // (sound but a black picture). Hold the file until the renderer is up.
        m_pendingLoad = args;
        update();
        return;
    }
    command(args);
    setProp("pause", QStringLiteral("no"));
}

void MpvPlayer::stop() { command({QStringLiteral("stop")}); }
void MpvPlayer::play()
{
    // At the end (keep-open), "play" means start over: never a frozen/black frame
    if (m_ended) seekTo(0);
    setProp("pause", QStringLiteral("no"));
}
void MpvPlayer::pause() { setProp("pause", QStringLiteral("yes")); }
void MpvPlayer::togglePause() { m_paused ? play() : pause(); }

void MpvPlayer::seekTo(qint64 ms)
{
    command({QStringLiteral("seek"), QString::number(double(std::max<qint64>(0, ms)) / 1000.0, 'f', 3),
             QStringLiteral("absolute+exact")});
}

void MpvPlayer::seekBy(qint64 deltaMs)
{
    qint64 target = m_position + deltaMs;
    if (m_duration > 0) target = std::min(target, m_duration);
    seekTo(std::max<qint64>(0, target));
}

void MpvPlayer::frameStep(bool forward)
{
    command({forward ? QStringLiteral("frame-step") : QStringLiteral("frame-back-step")});
}

void MpvPlayer::setSpeed(double speed) { setProp("speed", QString::number(speed, 'f', 3)); }
void MpvPlayer::setVolume(double volume) { setProp("volume", QString::number(std::clamp(volume, 0.0, 200.0), 'f', 1)); }
void MpvPlayer::setMuted(bool muted) { setProp("mute", muted ? QStringLiteral("yes") : QStringLiteral("no")); }
void MpvPlayer::setAudioTrack(int id) { setProp("aid", id > 0 ? QString::number(id) : QStringLiteral("no")); }
void MpvPlayer::setSubtitleTrack(int id) { setProp("sid", id > 0 ? QString::number(id) : QStringLiteral("no")); }

void MpvPlayer::addSubtitle(const QString &path)
{
    QString local = path.startsWith(QLatin1String("file://")) ? QUrl(path).toLocalFile() : path;
    command({QStringLiteral("sub-add"), local, QStringLiteral("select")});
}

void MpvPlayer::screenshot()
{
    QDir().mkpath(QDir::fromNativeSeparators(mpvProperty(QStringLiteral("screenshot-directory")).toString()));
    const char *args[] = {"screenshot", "video", nullptr};
    mpv_command_async(m_mpv.get(), 77, args);
}

void MpvPlayer::setMpvProperty(const QString &name, const QVariant &value)
{
    QString v;
    if (value.typeId() == QMetaType::Bool) v = value.toBool() ? QStringLiteral("yes") : QStringLiteral("no");
    else v = value.toString();
    setProp(name.toUtf8().constData(), v);
}

QVariant MpvPlayer::mpvProperty(const QString &name) const
{
    mpv_node node;
    if (mpv_get_property(m_mpv.get(), name.toUtf8().constData(), MPV_FORMAT_NODE, &node) < 0) return {};
    QVariant v = nodeToVariant(&node);
    mpv_free_node_contents(&node);
    return v;
}
