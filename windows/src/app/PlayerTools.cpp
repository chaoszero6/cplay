#include "PlayerTools.h"

#include "core/Adjustments.h"
#include "core/MediaFiles.h"
#include "core/PlaybackRules.h"
#include "core/Playlists.h"
#include "core/SleepTimer.h"
#include "core/TimeFormat.h"

#include <QDateTime>
#include <QFile>
#include <QFileInfo>
#include <QSaveFile>
#include <QStringConverter>
#include <QTextStream>

PlayerTools::PlayerTools(const QString &dataDir, QObject *parent)
    : QObject(parent), m_store(dataDir + QStringLiteral("/bookmarks.json"))
{
    m_store.load();
}

void PlayerTools::commit(const QList<Bookmark> &all)
{
    m_store.setAll(all);
    m_store.save();
    ++m_revision;
    emit bookmarksChanged();
}

QVariantList PlayerTools::bookmarks(const QString &location) const
{
    QVariantList out;
    for (const auto &b : BookmarkRules::forLocation(m_store.all(), location))
        out << QVariantMap{{"positionMs", b.positionMs}, {"label", b.label}, {"time", TimeFormat::format(b.positionMs)}};
    return out;
}

bool PlayerTools::addBookmark(const QString &location, qint64 positionMs, const QString &label)
{
    const auto before = m_store.all();
    const auto after = BookmarkRules::add(before, {location, positionMs, label, QDateTime::currentSecsSinceEpoch()});
    if (after.size() == before.size()) return false;
    commit(after);
    return true;
}

void PlayerTools::removeBookmark(const QString &location, qint64 positionMs)
{
    commit(BookmarkRules::remove(m_store.all(), location, positionMs));
}

void PlayerTools::renameBookmark(const QString &location, qint64 positionMs, const QString &label)
{
    commit(BookmarkRules::rename(m_store.all(), location, positionMs, label));
}

void PlayerTools::clearBookmarks(const QString &location)
{
    QList<Bookmark> keep;
    for (const auto &b : m_store.all()) if (b.location != location) keep << b;
    commit(keep);
}

qint64 PlayerTools::nextBookmark(const QString &location, qint64 positionMs) const
{
    const auto b = BookmarkRules::nextAfter(BookmarkRules::forLocation(m_store.all(), location), positionMs);
    return b ? b->positionMs : -1;
}

qint64 PlayerTools::previousBookmark(const QString &location, qint64 positionMs) const
{
    const auto b = BookmarkRules::previousBefore(BookmarkRules::forLocation(m_store.all(), location), positionMs);
    return b ? b->positionMs : -1;
}

QVariantMap PlayerTools::abPress(qint64 aMs, qint64 bMs, qint64 positionMs) const
{
    AbRepeat ab;
    if (aMs >= 0) ab.aMs = aMs;
    if (bMs >= 0) ab.bMs = bMs;
    const AbRepeat next = ab.press(positionMs);
    return {{"a", next.aMs ? *next.aMs : -1}, {"b", next.bMs ? *next.bMs : -1}, {"label", next.label()},
            {"active", next.isActive()}, {"armed", next.isArmed()}};
}

QVariantList PlayerTools::sleepPresets() const
{
    QVariantList out;
    for (int m : SleepTimer::presetMinutes()) out << m;
    return out;
}

QString PlayerTools::sleepLabel(qint64 remainingMs) const
{
    return SleepTimer::label(remainingMs > 0 ? std::optional<qint64>(remainingMs) : std::nullopt);
}

QVariantMap PlayerTools::folderQueue(const QString &file) const
{
    const auto q = FolderQueue::forFile(file);
    return {{"files", q.files}, {"index", q.index}};
}

QStringList PlayerTools::mediaInFolder(const QUrl &folder) const
{
    return FolderQueue::mediaIn(folder.isLocalFile() ? folder.toLocalFile() : folder.toString());
}

QStringList PlayerTools::loadPlaylist(const QUrl &file) const
{
    const QString path = file.isLocalFile() ? file.toLocalFile() : file.toString();
    QFile f(path);
    if (!f.open(QIODevice::ReadOnly)) return {};
    QByteArray raw = f.readAll();
    // .m3u8 is UTF-8; old .m3u files from Windows players are often in the local code page
    auto decode = QStringDecoder(QStringDecoder::Utf8);
    QString text = decode(raw);
    if (decode.hasError()) text = QString::fromLocal8Bit(raw);
    QStringList out;
    const QString dir = QFileInfo(path).absolutePath();
    for (const auto &e : M3u::parse(text)) out << M3u::resolve(e.location, dir);
    return out;
}

bool PlayerTools::savePlaylist(const QUrl &file, const QStringList &locations, const QStringList &titles) const
{
    QList<M3u::Entry> entries;
    for (int i = 0; i < locations.size(); ++i)
        entries << M3u::Entry{locations[i], titles.value(i, MediaFiles::displayName(locations[i])), -1};
    QSaveFile f(file.isLocalFile() ? file.toLocalFile() : file.toString());
    if (!f.open(QIODevice::WriteOnly)) return false;
    f.write(M3u::write(entries).toUtf8());
    return f.commit();
}

qint64 PlayerTools::stepDelay(qint64 ms, bool later) const { return Adjust::stepDelay(ms, later); }
QString PlayerTools::delayLabel(const QString &what, qint64 ms) const { return Adjust::delayLabel(what, ms); }
double PlayerTools::stepSubScale(double s, bool bigger) const { return Adjust::stepSubScale(s, bigger); }
QString PlayerTools::pictureLabel(const QString &what, int v) const { return Adjust::pictureLabel(what, v); }
double PlayerTools::stepZoom(double z, bool in) const { return Adjust::stepZoom(z, in); }
int PlayerTools::zoomPercent(double z) const { return Adjust::zoomPercent(z); }
int PlayerTools::nextRotation(int d) const { return Adjust::nextRotation(d); }

QVariantList PlayerTools::aspectPresets() const
{
    QVariantList out;
    for (const auto &p : Adjust::aspectPresets()) out << QVariantMap{{"label", p.first}, {"value", p.second}};
    return out;
}
