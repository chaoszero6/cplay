#pragma once
#include <QList>
#include <QString>
#include <QStringList>

/** M3U playlists (port of the M3u object in android/.../data/Playlists.kt, plus desktop paths). */
namespace M3u {
struct Entry {
    QString location;
    QString title;          // empty when the file gave none
    qint64 durationSec = -1;
};
QList<Entry> parse(const QString &text);
/** #EXTM3U with #EXTINF lines; titles are made single-line. */
QString write(const QList<Entry> &entries);
/** Absolute path or URL for an entry of a playlist saved in [playlistDir] (relative paths, "\" separators). */
QString resolve(const QString &location, const QString &playlistDir);
}

/** "Open a video, the rest of its folder follows" (classic desktop-player behaviour). */
namespace FolderQueue {
/** Media files in [folder], in natural order ("Ep 2" before "Ep 10"). */
QStringList mediaIn(const QString &folder);
struct Queue { QStringList files; int index = 0; };
/** Queue for [file]: every media file of its folder, starting at [file]. */
Queue forFile(const QString &file);
/** Natural "less than" used for the order above. */
bool naturalLess(const QString &a, const QString &b);
}
