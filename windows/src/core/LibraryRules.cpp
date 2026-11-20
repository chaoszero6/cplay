#include "LibraryRules.h"

#include <QCryptographicHash>
#include <QDir>
#include <QFileInfo>
#include <QHash>
#include <QRegularExpression>
#include <algorithm>

namespace LibraryRules {

Tab tabFromString(const QString &s)
{
    if (s == QLatin1String("folders")) return Tab::Folders;
    if (s == QLatin1String("recent")) return Tab::Recent;
    if (s == QLatin1String("favorites")) return Tab::Favorites;
    return Tab::All;
}

QString tabToString(Tab t)
{
    switch (t) {
    case Tab::Folders: return QStringLiteral("folders");
    case Tab::Recent: return QStringLiteral("recent");
    case Tab::Favorites: return QStringLiteral("favorites");
    case Tab::All: break;
    }
    return QStringLiteral("all");
}

QStringList sortKeys()
{
    return {QStringLiteral("newest"), QStringLiteral("oldest"), QStringLiteral("title"),
            QStringLiteral("title-desc"), QStringLiteral("longest"), QStringLiteral("largest")};
}

Sort sortFromString(const QString &s)
{
    const int i = sortKeys().indexOf(s);
    return i < 0 ? Sort::NewestFirst : Sort(i);
}

QString sortToString(Sort s) { return sortKeys().value(int(s), QStringLiteral("newest")); }

QString sortLabel(Sort s)
{
    switch (s) {
    case Sort::NewestFirst: return QStringLiteral("Newest first");
    case Sort::OldestFirst: return QStringLiteral("Oldest first");
    case Sort::TitleAsc: return QStringLiteral("Title (A-Z)");
    case Sort::TitleDesc: return QStringLiteral("Title (Z-A)");
    case Sort::LongestFirst: return QStringLiteral("Longest first");
    case Sort::LargestFirst: return QStringLiteral("Largest first");
    }
    return {};
}

bool naturalLess(const QString &a, const QString &b)
{
    // Runs of digits compare by value ("2" < "10"), everything else case-insensitively
    int i = 0, j = 0;
    const int na = a.size(), nb = b.size();
    while (i < na && j < nb) {
        const QChar ca = a.at(i), cb = b.at(j);
        if (ca.isDigit() && cb.isDigit()) {
            int si = i, sj = j;
            while (si < na && a.at(si) == u'0') ++si;
            while (sj < nb && b.at(sj) == u'0') ++sj;
            int ei = si, ej = sj;
            while (ei < na && a.at(ei).isDigit()) ++ei;
            while (ej < nb && b.at(ej).isDigit()) ++ej;
            const int li = ei - si, lj = ej - sj;
            if (li != lj) return li < lj;
            for (int k = 0; k < li; ++k)
                if (a.at(si + k) != b.at(sj + k)) return a.at(si + k) < b.at(sj + k);
            i = ei;
            j = ej;
            continue;
        }
        const QChar la = ca.toCaseFolded(), lb = cb.toCaseFolded();
        if (la != lb) return la < lb;
        ++i;
        ++j;
    }
    if ((i < na) != (j < nb)) return j < nb;
    return a < b;
}

QList<LibraryVideo> sorted(QList<LibraryVideo> v, Sort sort)
{
    auto byTitle = [](const LibraryVideo &x, const LibraryVideo &y) { return naturalLess(x.name, y.name); };
    switch (sort) {
    case Sort::NewestFirst:
        std::stable_sort(v.begin(), v.end(), [&](const LibraryVideo &x, const LibraryVideo &y) {
            return x.modifiedMs != y.modifiedMs ? x.modifiedMs > y.modifiedMs : byTitle(x, y);
        });
        break;
    case Sort::OldestFirst:
        std::stable_sort(v.begin(), v.end(), [&](const LibraryVideo &x, const LibraryVideo &y) {
            return x.modifiedMs != y.modifiedMs ? x.modifiedMs < y.modifiedMs : byTitle(x, y);
        });
        break;
    case Sort::TitleAsc:
        std::stable_sort(v.begin(), v.end(), byTitle);
        break;
    case Sort::TitleDesc:
        std::stable_sort(v.begin(), v.end(), [&](const LibraryVideo &x, const LibraryVideo &y) { return byTitle(y, x); });
        break;
    case Sort::LongestFirst:
        std::stable_sort(v.begin(), v.end(), [&](const LibraryVideo &x, const LibraryVideo &y) {
            return x.durationMs != y.durationMs ? x.durationMs > y.durationMs : byTitle(x, y);
        });
        break;
    case Sort::LargestFirst:
        std::stable_sort(v.begin(), v.end(), [&](const LibraryVideo &x, const LibraryVideo &y) {
            return x.sizeBytes != y.sizeBytes ? x.sizeBytes > y.sizeBytes : byTitle(x, y);
        });
        break;
    }
    return v;
}

QList<LibraryVideo> search(const QList<LibraryVideo> &videos, const QString &query)
{
    const QStringList words = query.simplified().split(u' ', Qt::SkipEmptyParts);
    if (words.isEmpty()) return videos;
    QList<LibraryVideo> out;
    for (const auto &v : videos) {
        const QString hay = v.name + u' ' + QFileInfo(v.folder).fileName();
        bool all = true;
        for (const auto &w : words)
            if (!hay.contains(w, Qt::CaseInsensitive)) { all = false; break; }
        if (all) out << v;
    }
    return out;
}

bool isNew(const LibraryVideo &v, qint64 nowMs)
{
    if (v.modifiedMs <= 0) return false;
    // A date slightly in the future (clock skew, copied mtime) still counts as new
    return nowMs - v.modifiedMs <= RecentWindowMs;
}

QList<LibraryVideo> forTab(const QList<LibraryVideo> &videos, Tab tab, qint64 nowMs, const QSet<QString> &favorites)
{
    if (tab == Tab::All || tab == Tab::Folders) return videos;
    QList<LibraryVideo> out;
    for (const auto &v : videos) {
        if (tab == Tab::Recent && isNew(v, nowMs)) out << v;
        else if (tab == Tab::Favorites && favorites.contains(v.path)) out << v;
    }
    return out;
}

QList<LibraryVideo> inFolder(const QList<LibraryVideo> &videos, const QString &folder)
{
    QList<LibraryVideo> out;
    for (const auto &v : videos) if (v.folder == folder) out << v;
    return out;
}

QList<LibraryFolder> folders(const QList<LibraryVideo> &videos)
{
    QHash<QString, LibraryFolder> map;
    QHash<QString, QString> coverName;
    for (const auto &v : videos) {
        auto &f = map[v.folder];
        if (f.path.isEmpty()) {
            f.path = v.folder;
            f.name = QFileInfo(v.folder).fileName();
            if (f.name.isEmpty()) f.name = v.folder; // drive root "C:/"
        }
        ++f.count;
        f.totalBytes += v.sizeBytes;
        f.latestMs = std::max(f.latestMs, v.modifiedMs);
        if (f.coverPath.isEmpty() || naturalLess(v.name, coverName.value(v.folder))) {
            f.coverPath = v.path;
            coverName[v.folder] = v.name;
        }
    }
    QList<LibraryFolder> out = map.values();
    std::sort(out.begin(), out.end(), [](const LibraryFolder &a, const LibraryFolder &b) {
        return a.name.compare(b.name, Qt::CaseInsensitive) != 0 ? naturalLess(a.name, b.name) : a.path < b.path;
    });
    return out;
}

QString emptyMessage(Tab tab, bool searching)
{
    if (searching) return QStringLiteral("No videos match your search");
    switch (tab) {
    case Tab::All: return QStringLiteral("No videos found. Add a folder");
    case Tab::Folders: return QStringLiteral("No folders with videos yet");
    case Tab::Recent: return QStringLiteral("Nothing new in the last 7 days");
    case Tab::Favorites: return QStringLiteral("No favorites yet. Right-click a video to add one");
    }
    return {};
}

bool skipDirectory(const QString &dirName)
{
    if (dirName.startsWith(u'.') || dirName.startsWith(u'$')) return true; // hidden, $RECYCLE.BIN
    static const QStringList skip{QStringLiteral("System Volume Information"), QStringLiteral("node_modules"),
                                  QStringLiteral("Windows"), QStringLiteral("Program Files"),
                                  QStringLiteral("Program Files (x86)"), QStringLiteral("AppData"),
                                  QStringLiteral("@eaDir"), QStringLiteral("#recycle")};
    return skip.contains(dirName, Qt::CaseInsensitive);
}

static QString norm(const QString &p)
{
    // "\" is a separator on Windows; normalised the same way on every platform (tests run on Linux)
    QString s = QDir::cleanPath(QString(p).replace(u'\\', u'/'));
    if (s.endsWith(u'/') && s.size() > 1 && !s.endsWith(QLatin1String(":/"))) s.chop(1);
    return s;
}

bool isUnder(const QString &path, const QStringList &roots)
{
    const QString p = norm(path);
    for (const auto &r : roots) {
        const QString root = norm(r);
        if (p.compare(root, Qt::CaseInsensitive) == 0) return true;
        const QString prefix = root.endsWith(u'/') ? root : root + u'/';
        if (p.startsWith(prefix, Qt::CaseInsensitive)) return true;
    }
    return false;
}

QStringList addRoot(const QStringList &roots, const QString &folder)
{
    const QString f = norm(folder);
    if (f.isEmpty() || isUnder(f, roots)) return roots;
    QStringList out;
    for (const auto &r : roots) if (!isUnder(r, {f})) out << r;
    out << f;
    return out;
}

QString thumbnailKey(const QString &path, qint64 sizeBytes, qint64 modifiedMs)
{
    const QByteArray data = norm(path).toLower().toUtf8() + '|' + QByteArray::number(sizeBytes) + '|' + QByteArray::number(modifiedMs / 1000);
    return QString::fromLatin1(QCryptographicHash::hash(data, QCryptographicHash::Sha1).toHex());
}

qint64 thumbnailPositionMs(qint64 durationMs)
{
    if (durationMs <= 0) return 1000;
    if (durationMs < 4000) return durationMs / 2;
    return std::clamp(durationMs / 10, qint64(1000), qint64(60000));
}

QString renameTarget(const QString &path, const QString &newName, QString *error)
{
    auto fail = [&](const QString &msg) { if (error) *error = msg; return QString(); };
    QString name = newName.trimmed();
    const QFileInfo info(path);
    const QString ext = info.suffix();
    if (!ext.isEmpty() && name.endsWith(u'.' + ext, Qt::CaseInsensitive)) name.chop(ext.size() + 1);
    name = name.trimmed();
    if (name.isEmpty()) return fail(QStringLiteral("Type a name"));
    static const QRegularExpression bad(QStringLiteral("[<>:\"/\\\\|?*\\x00-\\x1F]"));
    if (name.contains(bad)) return fail(QStringLiteral("A name can't contain < > : \" / \\ | ? *"));
    if (name.endsWith(u'.') || name.endsWith(u' ')) return fail(QStringLiteral("A name can't end with a dot or a space"));
    static const QRegularExpression reserved(QStringLiteral("^(CON|PRN|AUX|NUL|COM[1-9]|LPT[1-9])$"), QRegularExpression::CaseInsensitiveOption);
    if (reserved.match(name).hasMatch()) return fail(QStringLiteral("Windows reserves that name"));
    if (error) error->clear();
    return info.absolutePath() + u'/' + name + (ext.isEmpty() ? QString() : u'.' + ext);
}

QString sizeLabel(qint64 bytes)
{
    if (bytes <= 0) return QStringLiteral("0 B");
    const double kb = 1024.0, mb = kb * 1024, gb = mb * 1024;
    if (bytes >= gb) return QString::number(bytes / gb, 'f', 2) + QStringLiteral(" GB");
    if (bytes >= mb) return QString::number(bytes / mb, 'f', bytes >= 100 * mb ? 0 : 1) + QStringLiteral(" MB");
    if (bytes >= kb) return QString::number(qRound(bytes / kb)) + QStringLiteral(" KB");
    return QString::number(bytes) + QStringLiteral(" B");
}

QString countLabel(int count)
{
    return count == 1 ? QStringLiteral("1 video") : QString::number(count) + QStringLiteral(" videos");
}

} // namespace LibraryRules
