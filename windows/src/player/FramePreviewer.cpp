#include "FramePreviewer.h"
#include "LocationResolver.h"
#include "core/LibraryRules.h"

#include <mpv/client.h>
#include <mpv/render.h>

#include <QElapsedTimer>
#include <QThread>
#include <QUrl>
#include <clocale>

FramePreviewer::FramePreviewer()
{
    std::setlocale(LC_NUMERIC, "C");
    m_mpv = mpv_create();
    if (!m_mpv) return;
    const std::pair<const char *, const char *> options[] = {
        {"vo", "libmpv"}, {"hwdec", "no"}, {"aid", "no"}, {"sid", "no"}, {"ao", "null"},
        {"pause", "yes"}, {"keep-open", "always"}, {"idle", "yes"}, {"terminal", "no"},
        {"osd-level", "0"}, {"hr-seek", "no"}, {"ytdl", "no"}, {"cache", "no"},
        {"demuxer-readahead-secs", "1"}, {"vd-lavc-skiploopfilter", "all"}, {"vd-lavc-fast", "yes"},
    };
    for (const auto &[k, v] : options) mpv_set_option_string(m_mpv, k, v);
    if (mpv_initialize(m_mpv) < 0) { mpv_terminate_destroy(m_mpv); m_mpv = nullptr; return; }
    mpv_render_param params[]{
        {MPV_RENDER_PARAM_API_TYPE, const_cast<char *>(MPV_RENDER_API_TYPE_SW)},
        {MPV_RENDER_PARAM_INVALID, nullptr}};
    if (mpv_render_context_create(&m_render, m_mpv, params) < 0) m_render = nullptr;
}

FramePreviewer::~FramePreviewer()
{
    if (m_render) mpv_render_context_free(m_render);
    if (m_mpv) mpv_terminate_destroy(m_mpv);
}

bool FramePreviewer::waitFor(int eventId, int timeoutMs)
{
    QElapsedTimer t;
    t.start();
    while (t.elapsed() < timeoutMs) {
        mpv_event *e = mpv_wait_event(m_mpv, 0.05);
        if (e->event_id == eventId) return true;
        if (e->event_id == MPV_EVENT_END_FILE) {
            const auto *end = static_cast<mpv_event_end_file *>(e->data);
            if (end->reason == MPV_END_FILE_REASON_ERROR) return false;
        }
    }
    return false;
}

bool FramePreviewer::open(const QString &source, int timeoutMs)
{
    if (m_loaded && source == m_source) return true;
    m_loaded = false;
    m_source = source;
    const ResolvedLocation r = LocationResolver::resolve(source);
    if (!r.error.isEmpty() || r.url.isEmpty()) return false;
    setHeaders(m_mpv, r.httpHeaders);
    const QByteArray path = r.url.toUtf8();
    const char *cmd[] = {"loadfile", path.constData(), nullptr};
    if (mpv_command(m_mpv, cmd) < 0) return false;
    m_loaded = waitFor(MPV_EVENT_FILE_LOADED, timeoutMs);
    return m_loaded;
}

void FramePreviewer::setHeaders(mpv_handle *mpv, const QStringList &headers)
{
    // As a node list: header values may contain commas
    QList<QByteArray> data;
    for (const auto &h : headers) data << h.toUtf8();
    std::vector<mpv_node> items(size_t(data.size()));
    for (qsizetype i = 0; i < data.size(); ++i) {
        items[size_t(i)].format = MPV_FORMAT_STRING;
        items[size_t(i)].u.string = data[i].data();
    }
    mpv_node_list list{int(items.size()), items.data(), nullptr};
    mpv_node node;
    node.format = MPV_FORMAT_NODE_ARRAY;
    node.u.list = &list;
    mpv_set_property(mpv, "http-header-fields", MPV_FORMAT_NODE, &node);
}

QImage FramePreviewer::frameAt(const QString &source, qint64 positionMs, QSize maxSize, int timeoutMs)
{
    QMutexLocker guard(&m_lock);
    if (!m_mpv || !m_render || source.isEmpty() || maxSize.isEmpty()) return {};
    QElapsedTimer t;
    t.start();
    if (!open(source, timeoutMs)) return {};
    return grab(positionMs, maxSize, timeoutMs, t);
}

QImage FramePreviewer::thumbnail(const QString &source, QSize maxSize, qint64 *durationMs, int timeoutMs)
{
    QMutexLocker guard(&m_lock);
    if (durationMs) *durationMs = 0;
    if (!m_mpv || !m_render || source.isEmpty() || maxSize.isEmpty()) return {};
    QElapsedTimer t;
    t.start();
    m_loaded = false; // always reload: the library asks for each file once
    if (!open(source, timeoutMs)) return {};
    double seconds = 0;
    mpv_get_property(m_mpv, "duration", MPV_FORMAT_DOUBLE, &seconds);
    const qint64 duration = qint64(seconds * 1000);
    if (durationMs) *durationMs = duration;
    QImage image = grab(LibraryRules::thumbnailPositionMs(duration), maxSize, timeoutMs, t);
    // Leave the file so it is not kept open (it may be renamed or deleted next)
    const char *stop[] = {"stop", nullptr};
    mpv_command(m_mpv, stop);
    m_loaded = false;
    m_source.clear();
    return image;
}

QImage FramePreviewer::grab(qint64 positionMs, QSize maxSize, int timeoutMs, const QElapsedTimer &t)
{
    const QByteArray target = QByteArray::number(std::max<qint64>(0, positionMs) / 1000.0, 'f', 3);
    const char *seek[] = {"seek", target.constData(), "absolute+keyframes", nullptr};
    if (mpv_command(m_mpv, seek) < 0) return {};
    if (!waitFor(MPV_EVENT_PLAYBACK_RESTART, int(timeoutMs - t.elapsed()))) return {};

    // Fit the picture's shape inside maxSize
    int64_t dw = 0, dh = 0;
    mpv_get_property(m_mpv, "video-params/dw", MPV_FORMAT_INT64, &dw);
    mpv_get_property(m_mpv, "video-params/dh", MPV_FORMAT_INT64, &dh);
    QSize size = maxSize;
    if (dw > 0 && dh > 0) size = QSize(int(dw), int(dh)).scaled(maxSize, Qt::KeepAspectRatio);
    size = size.expandedTo(QSize(16, 16));

    // Wait until the paused frame reached the (software) video output
    while (!(mpv_render_context_update(m_render) & MPV_RENDER_UPDATE_FRAME)) {
        if (t.elapsed() > timeoutMs) break;
        QThread::msleep(5);
    }
    QImage image(size, QImage::Format_RGBX8888);
    image.fill(Qt::black);
    int wh[2] = {size.width(), size.height()};
    size_t stride = size_t(image.bytesPerLine());
    mpv_render_param params[]{
        {MPV_RENDER_PARAM_SW_SIZE, wh},
        {MPV_RENDER_PARAM_SW_FORMAT, const_cast<char *>("rgb0")},
        {MPV_RENDER_PARAM_SW_STRIDE, &stride},
        {MPV_RENDER_PARAM_SW_POINTER, image.bits()},
        {MPV_RENDER_PARAM_INVALID, nullptr}};
    if (mpv_render_context_render(m_render, params) < 0) return {};
    return image;
}
