#include "Bookmarks.h"
#include "TimeFormat.h"

#include <QDir>
#include <QFile>
#include <QFileInfo>
#include <QJsonArray>
#include <QJsonDocument>
#include <QJsonObject>
#include <QSaveFile>
#include <algorithm>
#include <cstdlib>

namespace BookmarkRules {

static void sortByPosition(QList<Bookmark> &l)
{
    std::stable_sort(l.begin(), l.end(), [](const Bookmark &a, const Bookmark &b) { return a.positionMs < b.positionMs; });
}

QList<Bookmark> forLocation(const QList<Bookmark> &all, const QString &location)
{
    QList<Bookmark> out;
    for (const auto &b : all) if (b.location == location) out << b;
    sortByPosition(out);
    return out;
}

QString defaultLabel(qint64 positionMs) { return QStringLiteral("Bookmark at ") + TimeFormat::format(positionMs); }

QList<Bookmark> add(const QList<Bookmark> &all, Bookmark b)
{
    b.positionMs = std::max<qint64>(0, b.positionMs);
    for (const auto &x : all)
        if (x.location == b.location && std::llabs(x.positionMs - b.positionMs) < DedupeWindowMs) return all;
    b.label = b.label.trimmed();
    if (b.label.isEmpty()) b.label = defaultLabel(b.positionMs);
    QList<Bookmark> result = all;
    result << b;
    QList<int> mine;
    for (int i = 0; i < result.size(); ++i) if (result[i].location == b.location) mine << i;
    if (mine.size() > MaxPerVideo) {
        int oldest = mine.first();
        for (int i : mine) if (result[i].createdSec < result[oldest].createdSec) oldest = i;
        result.removeAt(oldest);
    }
    return result;
}

QList<Bookmark> remove(const QList<Bookmark> &all, const QString &location, qint64 positionMs)
{
    QList<Bookmark> out;
    for (const auto &b : all) if (!(b.location == location && b.positionMs == positionMs)) out << b;
    return out;
}

QList<Bookmark> rename(const QList<Bookmark> &all, const QString &location, qint64 positionMs, const QString &label)
{
    QList<Bookmark> out = all;
    for (auto &b : out)
        if (b.location == location && b.positionMs == positionMs)
            b.label = label.trimmed().isEmpty() ? defaultLabel(positionMs) : label.trimmed();
    return out;
}

std::optional<Bookmark> nextAfter(const QList<Bookmark> &mine, qint64 positionMs)
{
    QList<Bookmark> s = mine;
    sortByPosition(s);
    for (const auto &b : s) if (b.positionMs > positionMs + 500) return b;
    return std::nullopt;
}

std::optional<Bookmark> previousBefore(const QList<Bookmark> &mine, qint64 positionMs)
{
    QList<Bookmark> s = mine;
    sortByPosition(s);
    for (auto it = s.crbegin(); it != s.crend(); ++it) if (it->positionMs < positionMs - 2000) return *it;
    return std::nullopt;
}

} // namespace BookmarkRules

void BookmarkStore::load()
{
    m_all.clear();
    QFile f(m_path);
    if (!f.open(QIODevice::ReadOnly)) return;
    for (const auto &v : QJsonDocument::fromJson(f.readAll()).object().value("bookmarks").toArray()) {
        const auto o = v.toObject();
        Bookmark b{o.value("location").toString(), qint64(o.value("position").toDouble()),
                   o.value("label").toString(), qint64(o.value("created").toDouble())};
        if (!b.location.isEmpty()) m_all << b;
    }
}

bool BookmarkStore::save() const
{
    QDir().mkpath(QFileInfo(m_path).absolutePath());
    QJsonArray arr;
    for (const auto &b : m_all)
        arr.append(QJsonObject{{"location", b.location}, {"position", double(b.positionMs)}, {"label", b.label}, {"created", double(b.createdSec)}});
    QSaveFile f(m_path);
    if (!f.open(QIODevice::WriteOnly)) return false;
    f.write(QJsonDocument(QJsonObject{{"version", 1}, {"bookmarks", arr}}).toJson(QJsonDocument::Compact));
    return f.commit();
}
