#include "SelfTest.h"

#include <mpv/client.h>
#include <QElapsedTimer>
#include <QStringList>
#include <clocale>

namespace SelfTest {

namespace {

double getDouble(mpv_handle *h, const char *name)
{
    double v = -1;
    if (mpv_get_property(h, name, MPV_FORMAT_DOUBLE, &v) < 0) return -1;
    return v;
}

QString getString(mpv_handle *h, const char *name)
{
    char *s = mpv_get_property_string(h, name);
    QString out = s ? QString::fromUtf8(s) : QString();
    mpv_free(s);
    return out;
}

bool getFlag(mpv_handle *h, const char *name)
{
    int v = 0;
    mpv_get_property(h, name, MPV_FORMAT_FLAG, &v);
    return v != 0;
}

/** Pumps events until [done] is true or the deadline passes; false on error/timeout. */
template <typename Pred>
bool waitFor(mpv_handle *h, QElapsedTimer &clock, int timeoutMs, Pred done, QString &err)
{
    while (clock.elapsed() < timeoutMs) {
        mpv_event *e = mpv_wait_event(h, 0.05);
        if (e->event_id == MPV_EVENT_END_FILE) {
            auto *end = static_cast<mpv_event_end_file *>(e->data);
            if (end->reason == MPV_END_FILE_REASON_ERROR) {
                err = QString::fromUtf8(mpv_error_string(end->error));
                return false;
            }
        }
        if (done()) return true;
    }
    err = QStringLiteral("timed out");
    return false;
}

} // namespace

Result run(const QString &file, int timeoutMs)
{
    Result r;
    QStringList log;
    std::setlocale(LC_NUMERIC, "C"); // libmpv refuses to start under other numeric locales
    mpv_handle *h = mpv_create();
    if (!h) { r.log = QStringLiteral("mpv_create failed"); return r; }
    for (auto [k, v] : {std::pair{"vo", "null"}, {"ao", "null"}, {"keep-open", "yes"}, {"idle", "yes"},
                        {"terminal", "no"}, {"hwdec", "no"}, {"ytdl", "no"}})
        mpv_set_option_string(h, k, v);
    if (mpv_initialize(h) < 0) { mpv_terminate_destroy(h); r.log = QStringLiteral("mpv_initialize failed"); return r; }
    log << QStringLiteral("libmpv %1").arg(getString(h, "mpv-version"));

    QElapsedTimer clock;
    clock.start();
    QString err;
    const QByteArray path = file.toUtf8();
    const char *load[] = {"loadfile", path.constData(), nullptr};
    mpv_command(h, load);

    // 1. Starts and advances
    if (!waitFor(h, clock, timeoutMs, [&] { return getDouble(h, "time-pos") >= 0.5; }, err)) {
        log << QStringLiteral("FAIL start: %1").arg(err);
        r.log = log.join(u'\n');
        mpv_terminate_destroy(h);
        return r;
    }
    r.durationMs = qint64(getDouble(h, "duration") * 1000.0);
    r.audioCodec = getString(h, "current-tracks/audio/codec");
    r.videoCodec = getString(h, "current-tracks/video/codec");
    log << QStringLiteral("playing: duration %1 ms, video %2, audio %3")
               .arg(r.durationMs).arg(r.videoCodec, r.audioCodec.isEmpty() ? QStringLiteral("none") : r.audioCodec);

    // 2. Seek close to the end and reach it (keep-open keeps the last frame)
    const QByteArray nearEnd = QByteArray::number(std::max(0.0, r.durationMs / 1000.0 - 1.0), 'f', 3);
    const char *seek[] = {"seek", nearEnd.constData(), "absolute", nullptr};
    mpv_command(h, seek);
    if (!waitFor(h, clock, timeoutMs, [&] { return getFlag(h, "eof-reached"); }, err)) {
        log << QStringLiteral("FAIL reaching the end: %1").arg(err);
        r.log = log.join(u'\n');
        mpv_terminate_destroy(h);
        return r;
    }
    log << QStringLiteral("reached the end");

    // 3. Replay from the start after the end (the Android "black screen on replay" case)
    const char *seek0[] = {"seek", "0", "absolute", nullptr};
    mpv_command(h, seek0);
    mpv_set_property_string(h, "pause", "no");
    const bool replayed = waitFor(h, clock, timeoutMs, [&] {
        const double t = getDouble(h, "time-pos");
        return !getFlag(h, "eof-reached") && t >= 0.3 && t < 5.0;
    }, err);
    log << (replayed ? QStringLiteral("replayed from the start") : QStringLiteral("FAIL replay: %1").arg(err));
    r.ok = replayed;
    r.log = log.join(u'\n');
    mpv_terminate_destroy(h);
    return r;
}

} // namespace SelfTest
