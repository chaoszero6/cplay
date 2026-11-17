#pragma once
#include <QList>
#include <QString>
#include <optional>

/** A saved point in a video (port of android/.../player/Bookmarks.kt). */
struct Bookmark {
    QString location;
    qint64 positionMs = 0;
    QString label;
    qint64 createdSec = 0;
    bool operator==(const Bookmark &o) const { return location == o.location && positionMs == o.positionMs && label == o.label; }
};

namespace BookmarkRules {
/** Two bookmarks closer than this are the same point; the second press is ignored. */
constexpr qint64 DedupeWindowMs = 1500;
constexpr int MaxPerVideo = 100;

QList<Bookmark> forLocation(const QList<Bookmark> &all, const QString &location);
QString defaultLabel(qint64 positionMs);
/** Adds unless one sits within DedupeWindowMs; keeps at most MaxPerVideo per video (oldest dropped). */
QList<Bookmark> add(const QList<Bookmark> &all, Bookmark b);
QList<Bookmark> remove(const QList<Bookmark> &all, const QString &location, qint64 positionMs);
QList<Bookmark> rename(const QList<Bookmark> &all, const QString &location, qint64 positionMs, const QString &label);
std::optional<Bookmark> nextAfter(const QList<Bookmark> &mine, qint64 positionMs);
std::optional<Bookmark> previousBefore(const QList<Bookmark> &mine, qint64 positionMs);
}

/** All bookmarks in one JSON file. */
class BookmarkStore
{
public:
    explicit BookmarkStore(QString filePath) : m_path(std::move(filePath)) {}
    void load();
    bool save() const;
    QList<Bookmark> all() const { return m_all; }
    void setAll(QList<Bookmark> all) { m_all = std::move(all); }
private:
    QString m_path;
    QList<Bookmark> m_all;
};
