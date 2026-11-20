#pragma once
#include <QList>
#include <QSet>
#include <QString>
#include <QStringList>

/** One video file found by the library scanner. */
struct LibraryVideo {
    QString path;        // absolute, "/" separators
    QString name;        // file name without extension
    QString folder;      // absolute folder path
    qint64 sizeBytes = 0;
    qint64 modifiedMs = 0; // epoch ms (used as "date added", like the Android MediaStore date)
    qint64 durationMs = 0; // 0 until probed
};

struct LibraryFolder {
    QString path;
    QString name;
    int count = 0;
    qint64 totalBytes = 0;
    qint64 latestMs = 0;
    QString coverPath;   // first video in natural order
};

/**
 * Library rules shared with the Android app (player/LibraryRules.kt, LibraryTabs.kt):
 * tabs, sort orders, search, folder grouping, "new" tag, continue watching.
 * Pure functions, unit tested in tests/core/LibraryRulesTest.cpp.
 */
namespace LibraryRules {

enum class Tab { All, Folders, Recent, Favorites };
enum class Sort { NewestFirst, OldestFirst, TitleAsc, TitleDesc, LongestFirst, LargestFirst };

constexpr qint64 RecentWindowMs = 7LL * 24 * 60 * 60 * 1000;
constexpr int ContinueWatchingLimit = 10;

Tab tabFromString(const QString &s);
QString tabToString(Tab t);
Sort sortFromString(const QString &s);
QString sortToString(Sort s);
QString sortLabel(Sort s);
QStringList sortKeys();

/** Natural, case-insensitive "less than" ("Ep 2" before "Ep 10"). */
bool naturalLess(const QString &a, const QString &b);

QList<LibraryVideo> sorted(QList<LibraryVideo> videos, Sort sort);
/** Every word of [query] must appear in the name or folder name (case-insensitive). */
QList<LibraryVideo> search(const QList<LibraryVideo> &videos, const QString &query);
/** The videos a tab shows (Folders: all, grouped by the caller). */
QList<LibraryVideo> forTab(const QList<LibraryVideo> &videos, Tab tab, qint64 nowMs, const QSet<QString> &favorites);
/** Videos directly inside [folder]. */
QList<LibraryVideo> inFolder(const QList<LibraryVideo> &videos, const QString &folder);
/** Folders that hold videos, in natural name order. */
QList<LibraryFolder> folders(const QList<LibraryVideo> &videos);
bool isNew(const LibraryVideo &v, qint64 nowMs);
QString emptyMessage(Tab tab, bool searching);

/** Folder names the scanner never enters (hidden, system, recycle bin). */
bool skipDirectory(const QString &dirName);
/** Folder [path] is one of [roots] or inside one. */
bool isUnder(const QString &path, const QStringList &roots);
/** Adds [folder] to [roots] unless already covered; drops roots that are inside it. */
QStringList addRoot(const QStringList &roots, const QString &folder);

/** Thumbnail cache file name for a video version (path + size + mtime). */
QString thumbnailKey(const QString &path, qint64 sizeBytes, qint64 modifiedMs);
/** Where the thumbnail frame is taken: 10% in, between 1 s and 60 s; middle of short clips. */
qint64 thumbnailPositionMs(qint64 durationMs);

/** New path when renaming [path] to [newName] (extension kept); empty + [error] when invalid. */
QString renameTarget(const QString &path, const QString &newName, QString *error);

QString sizeLabel(qint64 bytes);
QString countLabel(int count);
}
