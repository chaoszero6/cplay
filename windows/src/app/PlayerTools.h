#pragma once

#include "core/Bookmarks.h"

#include <QObject>
#include <QUrl>
#include <QVariantList>
#include <QVariantMap>

/**
 * Player helpers for QML ("tools"): bookmarks, A-B repeat, sleep timer,
 * folder queues, M3U playlists and the control box's limits and labels.
 * The rules themselves live in src/core and are unit tested.
 */
class PlayerTools : public QObject
{
    Q_OBJECT
    Q_PROPERTY(int bookmarkRevision READ bookmarkRevision NOTIFY bookmarksChanged)

public:
    explicit PlayerTools(const QString &dataDir, QObject *parent = nullptr);

    int bookmarkRevision() const { return m_revision; }

    // Bookmarks
    Q_INVOKABLE QVariantList bookmarks(const QString &location) const;
    /** True when added (false when one already sits within 1.5 s). */
    Q_INVOKABLE bool addBookmark(const QString &location, qint64 positionMs, const QString &label = {});
    Q_INVOKABLE void removeBookmark(const QString &location, qint64 positionMs);
    Q_INVOKABLE void renameBookmark(const QString &location, qint64 positionMs, const QString &label);
    Q_INVOKABLE void clearBookmarks(const QString &location);
    /** Position of the next / previous bookmark, or -1. */
    Q_INVOKABLE qint64 nextBookmark(const QString &location, qint64 positionMs) const;
    Q_INVOKABLE qint64 previousBookmark(const QString &location, qint64 positionMs) const;

    // A-B repeat: {a, b, label}; -1 = unset
    Q_INVOKABLE QVariantMap abPress(qint64 aMs, qint64 bMs, qint64 positionMs) const;

    // Sleep timer
    Q_INVOKABLE QVariantList sleepPresets() const;
    Q_INVOKABLE QString sleepLabel(qint64 remainingMs) const;

    // Queues and playlists
    /** {files, index}: the media files of [file]'s folder, starting at it. */
    Q_INVOKABLE QVariantMap folderQueue(const QString &file) const;
    Q_INVOKABLE QStringList mediaInFolder(const QUrl &folder) const;
    /** Locations from an .m3u/.m3u8 file (relative paths resolved). */
    Q_INVOKABLE QStringList loadPlaylist(const QUrl &file) const;
    Q_INVOKABLE bool savePlaylist(const QUrl &file, const QStringList &locations, const QStringList &titles) const;

    // Control box
    Q_INVOKABLE qint64 stepDelay(qint64 ms, bool later) const;
    Q_INVOKABLE QString delayLabel(const QString &what, qint64 ms) const;
    Q_INVOKABLE double stepSubScale(double scale, bool bigger) const;
    Q_INVOKABLE QString pictureLabel(const QString &what, int value) const;
    Q_INVOKABLE double stepZoom(double zoom, bool in) const;
    Q_INVOKABLE int zoomPercent(double zoom) const;
    Q_INVOKABLE int nextRotation(int degrees) const;
    Q_INVOKABLE QVariantList aspectPresets() const;

signals:
    void bookmarksChanged();

private:
    void commit(const QList<Bookmark> &all);
    BookmarkStore m_store;
    int m_revision = 0;
};
