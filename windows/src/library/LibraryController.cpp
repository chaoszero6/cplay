#include "LibraryController.h"
#include "ThumbnailService.h"
#include "core/MediaFiles.h"
#include "core/PlaybackRules.h"
#include "core/ResumeStore.h"
#include "core/TimeFormat.h"

#include <QDateTime>
#include <QDesktopServices>
#include <QDir>
#include <QDirIterator>
#include <QFile>
#include <QFileInfo>
#include <QJsonArray>
#include <QJsonDocument>
#include <QJsonObject>
#include <QProcess>
#include <QSaveFile>
#include <QStandardPaths>
#include <QThreadPool>
#include <QUrl>

using namespace LibraryRules;

namespace {

QThreadPool *workPool()
{
    static QThreadPool *pool = [] {
        auto *p = new QThreadPool;
        p->setMaxThreadCount(1); // scans and background thumbnails, one at a time
        return p;
    }();
    return pool;
}

QString norm(const QString &p) { return QDir::cleanPath(QDir::fromNativeSeparators(p)); }

void scanDir(const QString &dir, int depth, const std::atomic<bool> &alive, QList<LibraryVideo> &out, QStringList &dirs)
{
    if (depth > 16 || !alive.load()) return;
    dirs << dir;
    const QFileInfoList entries = QDir(dir).entryInfoList(QDir::Files | QDir::Dirs | QDir::NoDotAndDotDot | QDir::Readable);
    for (const QFileInfo &e : entries) {
        if (e.isDir()) {
            if (e.isSymLink() || skipDirectory(e.fileName())) continue;
            scanDir(e.absoluteFilePath(), depth + 1, alive, out, dirs);
        } else if (MediaFiles::isVideo(e.fileName())) {
            LibraryVideo v;
            v.path = e.absoluteFilePath();
            v.name = e.completeBaseName();
            v.folder = e.absolutePath();
            v.sizeBytes = e.size();
            v.modifiedMs = e.lastModified().toMSecsSinceEpoch();
            out << v;
        }
    }
}

QString dateLabel(qint64 ms)
{
    if (ms <= 0) return {};
    const QDate d = QDateTime::fromMSecsSinceEpoch(ms).date();
    const QDate today = QDate::currentDate();
    if (d == today) return QStringLiteral("Today");
    if (d == today.addDays(-1)) return QStringLiteral("Yesterday");
    return QLocale().toString(d, d.year() == today.year() ? QStringLiteral("d MMM") : QStringLiteral("d MMM yyyy"));
}

} // namespace

// ---- model ----------------------------------------------------------------

int LibraryModel::rowCount(const QModelIndex &parent) const { return parent.isValid() ? 0 : int(m_rows.size()); }

QHash<int, QByteArray> LibraryModel::roleNames() const
{
    return {{PathRole, "path"}, {NameRole, "name"}, {FolderRole, "folder"}, {FolderNameRole, "folderName"},
            {SizeRole, "size"}, {SizeLabelRole, "sizeLabel"}, {DateLabelRole, "dateLabel"}, {DurationRole, "duration"},
            {DurationLabelRole, "durationLabel"}, {ProgressRole, "progress"}, {FinishedRole, "finished"},
            {FavoriteRole, "favorite"}, {IsNewRole, "isNew"}, {ThumbRole, "thumb"}, {IsFolderRole, "isFolder"},
            {CountLabelRole, "countLabel"}};
}

static QString thumbFor(const QString &path)
{
    return QStringLiteral("image://thumb/") + QString::fromLatin1(QUrl::toPercentEncoding(path));
}

QVariant LibraryModel::data(const QModelIndex &index, int role) const
{
    if (!index.isValid() || index.row() >= m_rows.size()) return {};
    const Row &r = m_rows.at(index.row());
    if (r.folder) {
        const LibraryFolder &f = r.dir;
        switch (role) {
        case PathRole: return f.path;
        case NameRole: return f.name;
        case FolderRole: return QFileInfo(f.path).absolutePath();
        case FolderNameRole: return QDir::toNativeSeparators(f.path);
        case SizeRole: return f.totalBytes;
        case SizeLabelRole: return sizeLabel(f.totalBytes);
        case DateLabelRole: return dateLabel(f.latestMs);
        case ThumbRole: return f.coverPath.isEmpty() ? QString() : thumbFor(f.coverPath);
        case IsFolderRole: return true;
        case CountLabelRole: return countLabel(f.count);
        case DurationRole: return 0;
        case DurationLabelRole: return QString();
        case ProgressRole: return 0.0;
        case FinishedRole: case FavoriteRole: case IsNewRole: return false;
        }
        return {};
    }
    const LibraryVideo &v = r.video;
    const qint64 duration = durationOf ? durationOf(v.path) : v.durationMs;
    switch (role) {
    case PathRole: return v.path;
    case NameRole: return v.name;
    case FolderRole: return v.folder;
    case FolderNameRole: return QFileInfo(v.folder).fileName();
    case SizeRole: return v.sizeBytes;
    case SizeLabelRole: return sizeLabel(v.sizeBytes);
    case DateLabelRole: return dateLabel(v.modifiedMs);
    case DurationRole: return duration;
    case DurationLabelRole: return duration > 0 ? TimeFormat::format(duration) : QString();
    case ProgressRole: return progressOf ? progressOf(v.path) : 0.0;
    case FinishedRole: return finishedOf ? finishedOf(v.path) : false;
    case FavoriteRole: return favoriteOf ? favoriteOf(v.path) : false;
    case IsNewRole: return isNew(v, QDateTime::currentMSecsSinceEpoch());
    case ThumbRole: return thumbFor(v.path);
    case IsFolderRole: return false;
    case CountLabelRole: return QString();
    }
    return {};
}

void LibraryModel::reset(QList<Row> rows)
{
    beginResetModel();
    m_rows = std::move(rows);
    endResetModel();
}

void LibraryModel::refreshPath(const QString &path)
{
    for (int i = 0; i < m_rows.size(); ++i)
        if (!m_rows[i].folder && m_rows[i].video.path == path) emit dataChanged(index(i), index(i));
}

void LibraryModel::refreshAll()
{
    if (!m_rows.isEmpty()) emit dataChanged(index(0), index(int(m_rows.size()) - 1));
}

// ---- controller -------------------------------------------------------------

LibraryController::LibraryController(const QString &dataDir, ResumeStore *resume, std::shared_ptr<ThumbnailService> thumbs,
                                     QObject *parent)
    : QObject(parent), m_dataDir(dataDir), m_resume(resume), m_thumbs(std::move(thumbs)),
      m_alive(std::make_shared<std::atomic<bool>>(true))
{
    m_model.progressOf = [this](const QString &p) {
        const auto e = m_resume->entry(p);
        return e ? TimeFormat::progressFraction(e->positionMs, e->durationMs) : 0.0;
    };
    m_model.finishedOf = [this](const QString &p) {
        const auto e = m_resume->entry(p);
        return e && ((e->playCount > 0 && e->positionMs == 0) || PlaybackRules::isFinished(e->positionMs, e->durationMs));
    };
    m_model.favoriteOf = [this](const QString &p) { return m_favorites.contains(p); };
    m_model.durationOf = [this](const QString &p) {
        const QFileInfo i(p);
        const qint64 d = m_durations.value(thumbnailKey(p, i.size(), i.lastModified().toMSecsSinceEpoch()), 0);
        if (d > 0) return d;
        const auto e = m_resume->entry(p);
        return e ? e->durationMs : qint64(0);
    };

    m_rescanTimer.setSingleShot(true);
    m_rescanTimer.setInterval(2500);
    connect(&m_rescanTimer, &QTimer::timeout, this, &LibraryController::rescan);
    connect(&m_watcher, &QFileSystemWatcher::directoryChanged, this, [this] { m_rescanTimer.start(); });
    m_saveDurationsTimer.setSingleShot(true);
    m_saveDurationsTimer.setInterval(3000);
    connect(&m_saveDurationsTimer, &QTimer::timeout, this, &LibraryController::saveDurations);

    auto alive = m_alive;
    m_thumbs->setProbedCallback([this, alive](const QString &path, qint64 ms) {
        if (!alive->load()) return;
        QMetaObject::invokeMethod(this, [this, path, ms] { onProbed(path, ms); }, Qt::QueuedConnection);
    });
    load();
    rescan();
}

LibraryController::~LibraryController()
{
    m_alive->store(false);
    m_thumbs->setProbedCallback({});
    workPool()->clear();
    workPool()->waitForDone();
    saveDurations();
}

QString LibraryController::defaultFolder() const
{
    return norm(QStandardPaths::writableLocation(QStandardPaths::MoviesLocation));
}

void LibraryController::load()
{
    QFile f(m_dataDir + QStringLiteral("/library.json"));
    bool first = true;
    if (f.open(QIODevice::ReadOnly)) {
        const QJsonObject o = QJsonDocument::fromJson(f.readAll()).object();
        first = !o.contains(QStringLiteral("folders"));
        for (const auto &v : o.value(QStringLiteral("folders")).toArray()) m_roots << v.toString();
        for (const auto &v : o.value(QStringLiteral("favorites")).toArray()) m_favorites.insert(v.toString());
        m_tab = tabFromString(o.value(QStringLiteral("tab")).toString());
        m_sort = sortFromString(o.value(QStringLiteral("sort")).toString());
        const QString mode = o.value(QStringLiteral("view")).toString();
        if (mode == QLatin1String("list")) m_viewMode = mode;
    }
    if (first) {
        const QString videos = defaultFolder();
        if (!videos.isEmpty()) m_roots << videos;
    }
    QFile d(m_dataDir + QStringLiteral("/durations.json"));
    if (d.open(QIODevice::ReadOnly)) {
        const QJsonObject o = QJsonDocument::fromJson(d.readAll()).object();
        for (auto it = o.begin(); it != o.end(); ++it) m_durations.insert(it.key(), qint64(it.value().toDouble()));
    }
}

void LibraryController::saveState() const
{
    QJsonArray folders, favs;
    for (const auto &r : m_roots) folders << r;
    for (const auto &p : m_favorites) favs << p;
    const QJsonObject o{{"folders", folders}, {"favorites", favs}, {"tab", tabToString(m_tab)},
                        {"sort", sortToString(m_sort)}, {"view", m_viewMode}};
    QDir().mkpath(m_dataDir);
    QSaveFile f(m_dataDir + QStringLiteral("/library.json"));
    if (f.open(QIODevice::WriteOnly)) {
        f.write(QJsonDocument(o).toJson(QJsonDocument::Compact));
        f.commit();
    }
}

void LibraryController::saveDurations() const
{
    QJsonObject o;
    for (auto it = m_durations.begin(); it != m_durations.end(); ++it) o.insert(it.key(), double(it.value()));
    QDir().mkpath(m_dataDir);
    QSaveFile f(m_dataDir + QStringLiteral("/durations.json"));
    if (f.open(QIODevice::WriteOnly)) {
        f.write(QJsonDocument(o).toJson(QJsonDocument::Compact));
        f.commit();
    }
}

void LibraryController::rescan()
{
    if (m_scanning) { m_rescanAgain = true; return; }
    m_scanning = true;
    emit scanningChanged();
    const QStringList roots = m_roots;
    auto alive = m_alive;
    workPool()->start([this, roots, alive] {
        QList<LibraryVideo> videos;
        QStringList dirs;
        for (const auto &r : roots) if (QFileInfo(r).isDir()) scanDir(norm(r), 0, *alive, videos, dirs);
        if (!alive->load()) return;
        QMetaObject::invokeMethod(this, [this, videos, dirs] { applyScan(videos, dirs); }, Qt::QueuedConnection);
    });
}

void LibraryController::applyScan(QList<LibraryVideo> videos, QStringList dirs)
{
    for (auto &v : videos) {
        v.durationMs = m_durations.value(thumbnailKey(v.path, v.sizeBytes, v.modifiedMs), 0);
        if (v.durationMs == 0) {
            const auto e = m_resume->entry(v.path);
            if (e) v.durationMs = e->durationMs;
        }
    }
    m_videos = std::move(videos);
    // Watch the folders for new, renamed or deleted videos (bounded: huge trees rescan on demand)
    if (!m_watcher.directories().isEmpty()) m_watcher.removePaths(m_watcher.directories());
    if (dirs.size() > 3000) dirs = dirs.mid(0, 3000);
    if (!dirs.isEmpty()) m_watcher.addPaths(dirs);
    m_scanning = false;
    emit scanningChanged();
    emit videosChanged();
    rebuild();
    emit continueWatchingChanged();
    if (m_rescanAgain) { m_rescanAgain = false; rescan(); return; }
    prefetchThumbnails();
}

void LibraryController::prefetchThumbnails()
{
    // Make missing thumbnails (and learn durations) in the background, newest first
    QStringList todo;
    for (const auto &v : sorted(m_videos, Sort::NewestFirst)) if (!m_thumbs->hasCached(v.path)) todo << v.path;
    if (todo.isEmpty()) return;
    auto alive = m_alive;
    auto thumbs = m_thumbs;
    workPool()->start([todo, alive, thumbs] {
        for (const auto &p : todo) {
            if (!alive->load()) return;
            thumbs->thumbnail(p);
        }
    });
}

void LibraryController::onProbed(const QString &path, qint64 durationMs)
{
    const QFileInfo i(path);
    m_durations.insert(thumbnailKey(path, i.size(), i.lastModified().toMSecsSinceEpoch()), durationMs);
    for (auto &v : m_videos) if (v.path == path) v.durationMs = durationMs;
    m_model.refreshPath(path);
    m_saveDurationsTimer.start();
    emit durationProbed(path, durationMs);
}

void LibraryController::rebuild()
{
    QList<LibraryModel::Row> rows;
    const qint64 now = QDateTime::currentMSecsSinceEpoch();
    if (m_tab == Tab::Folders && m_openFolder.isEmpty()) {
        QList<LibraryVideo> base = m_videos;
        QList<LibraryFolder> dirs = LibraryRules::folders(base);
        if (!m_query.trimmed().isEmpty()) {
            QList<LibraryFolder> keep;
            const QStringList words = m_query.simplified().split(u' ', Qt::SkipEmptyParts);
            for (const auto &f : dirs) {
                bool all = true;
                for (const auto &w : words) if (!f.name.contains(w, Qt::CaseInsensitive)) { all = false; break; }
                if (all) keep << f;
            }
            dirs = keep;
        }
        for (const auto &f : dirs) { LibraryModel::Row r; r.folder = true; r.dir = f; rows << r; }
    } else {
        QList<LibraryVideo> list = m_openFolder.isEmpty() ? forTab(m_videos, m_tab, now, m_favorites) : inFolder(m_videos, m_openFolder);
        list = sorted(search(list, m_query), m_openFolder.isEmpty() ? m_sort : (m_sort == Sort::NewestFirst ? Sort::TitleAsc : m_sort));
        for (const auto &v : list) { LibraryModel::Row r; r.video = v; rows << r; }
    }
    m_model.reset(std::move(rows));
    emit viewChanged();
}

void LibraryController::setTab(const QString &t)
{
    const Tab tab = tabFromString(t);
    if (tab == m_tab && m_openFolder.isEmpty()) return;
    m_tab = tab;
    m_openFolder.clear();
    saveState();
    rebuild();
}

void LibraryController::setSort(const QString &s)
{
    const Sort sort = sortFromString(s);
    if (sort == m_sort) return;
    m_sort = sort;
    saveState();
    rebuild();
}

void LibraryController::setQuery(const QString &q)
{
    if (q == m_query) return;
    m_query = q;
    rebuild();
}

void LibraryController::setOpenFolder(const QString &f)
{
    if (f == m_openFolder) return;
    m_openFolder = f;
    rebuild();
}

void LibraryController::setViewMode(const QString &m)
{
    const QString mode = m == QLatin1String("list") ? m : QStringLiteral("grid");
    if (mode == m_viewMode) return;
    m_viewMode = mode;
    saveState();
    emit viewChanged();
}

QString LibraryController::emptyMessage() const
{
    if (m_roots.isEmpty()) return QStringLiteral("Add a folder with videos to build your library");
    if (m_scanning && m_videos.isEmpty()) return QStringLiteral("Looking for videos…");
    return LibraryRules::emptyMessage(m_tab, !m_query.trimmed().isEmpty());
}

QVariantList LibraryController::sortOptions() const
{
    QVariantList out;
    for (const auto &k : sortKeys()) out << QVariantMap{{"key", k}, {"label", sortLabel(sortFromString(k))}};
    return out;
}

QVariantList LibraryController::continueWatching() const
{
    // Started, not finished, most recent first (local files that still exist, and network videos)
    QVariantList out;
    for (const auto &e : m_resume->recent(60)) {
        if (out.size() >= ContinueWatchingLimit) break;
        if (e.positionMs <= 0 || PlaybackRules::isFinished(e.positionMs, e.durationMs)) continue;
        const bool local = !MediaFiles::isUrl(e.key);
        if (local && !QFileInfo::exists(e.key)) continue;
        out << QVariantMap{{"location", e.key},
                           {"title", local ? QFileInfo(e.key).completeBaseName() : (e.title.isEmpty() ? MediaFiles::displayName(e.key) : e.title)},
                           {"progress", TimeFormat::progressFraction(e.positionMs, e.durationMs)},
                           {"remaining", TimeFormat::remainingShortLabel(e.durationMs, e.positionMs)},
                           {"thumb", local ? thumbFor(e.key) : QString()},
                           {"local", local}};
    }
    return out;
}

bool LibraryController::addFolder(const QUrl &folder) { return addFolderPath(folder.toLocalFile()); }

bool LibraryController::addFolderPath(const QString &folder)
{
    const QString f = norm(folder);
    if (f.isEmpty() || !QFileInfo(f).isDir()) return false;
    const QStringList next = addRoot(m_roots, f);
    if (next == m_roots) return false;
    m_roots = next;
    saveState();
    emit foldersChanged();
    rescan();
    return true;
}

void LibraryController::removeFolder(const QString &folder)
{
    if (!m_roots.removeAll(folder)) return;
    saveState();
    emit foldersChanged();
    rescan();
}

void LibraryController::setFavorite(const QString &path, bool favorite)
{
    if (favorite == m_favorites.contains(path)) return;
    if (favorite) m_favorites.insert(path);
    else m_favorites.remove(path);
    ++m_favRevision;
    saveState();
    emit favoritesChanged();
    if (m_tab == Tab::Favorites && m_openFolder.isEmpty()) rebuild();
    else m_model.refreshPath(path);
}

QString LibraryController::rename(const QString &path, const QString &newName)
{
    QString error;
    const QString target = renameTarget(path, newName, &error);
    if (target.isEmpty()) return error;
    if (target == path) return {};
    if (QFileInfo::exists(target) && target.compare(path, Qt::CaseInsensitive) != 0)
        return QStringLiteral("A file with that name already exists");
    if (!QFile::rename(path, target)) return QStringLiteral("Windows would not rename the file (is it open in another program?)");
    m_resume->rename(path, target);
    m_resume->save();
    if (m_favorites.remove(path)) { m_favorites.insert(target); saveState(); }
    // Subtitles named like the video follow it
    const QString oldBase = QFileInfo(path).completeBaseName(), newBase = QFileInfo(target).completeBaseName();
    for (const QString &sub : MediaFiles::siblingSubtitles(path)) {
        const QFileInfo si(sub);
        if (si.completeBaseName().startsWith(oldBase))
            QFile::rename(sub, si.absolutePath() + u'/' + newBase + si.fileName().mid(oldBase.size()));
    }
    for (auto &v : m_videos) {
        if (v.path != path) continue;
        const QFileInfo i(target);
        v.path = target;
        v.name = i.completeBaseName();
    }
    rebuild();
    emit continueWatchingChanged();
    return {};
}

QString LibraryController::moveToRecycleBin(const QString &path)
{
    if (!QFileInfo::exists(path)) return QStringLiteral("The file is already gone");
    if (!QFile::moveToTrash(path)) return QStringLiteral("Could not move the file to the Recycle Bin (is it open in another program?)");
    m_videos.erase(std::remove_if(m_videos.begin(), m_videos.end(), [&](const LibraryVideo &v) { return v.path == path; }), m_videos.end());
    m_resume->remove(path);
    m_resume->save();
    if (m_favorites.remove(path)) saveState();
    emit videosChanged();
    rebuild();
    emit continueWatchingChanged();
    return {};
}

void LibraryController::showInFolder(const QString &path) const
{
#ifdef Q_OS_WIN
    QProcess::startDetached(QStringLiteral("explorer.exe"), {QStringLiteral("/select,") + QDir::toNativeSeparators(path)});
#else
    QDesktopServices::openUrl(QUrl::fromLocalFile(QFileInfo(path).absolutePath()));
#endif
}

QStringList LibraryController::visiblePaths() const
{
    QStringList out;
    for (const auto &r : m_model.rows()) if (!r.folder) out << r.video.path;
    return out;
}

QStringList LibraryController::pathsInFolder(const QString &folder) const
{
    QList<LibraryVideo> list = sorted(inFolder(m_videos, folder), Sort::TitleAsc);
    QStringList out;
    for (const auto &v : list) out << v.path;
    return out;
}

QVariantMap LibraryController::info(const QString &path) const
{
    const QFileInfo i(path);
    const qint64 duration = m_model.durationOf(path);
    const auto e = m_resume->entry(path);
    return {{"name", i.fileName()},
            {"folder", QDir::toNativeSeparators(i.absolutePath())},
            {"size", sizeLabel(i.size()) + QStringLiteral("  (") + QLocale().toString(i.size()) + QStringLiteral(" bytes)")},
            {"modified", QLocale().toString(i.lastModified(), QLocale::LongFormat)},
            {"duration", duration > 0 ? TimeFormat::format(duration) : QStringLiteral("Unknown")},
            {"watched", e ? (e->playCount > 0 ? QStringLiteral("Played %1 time(s)").arg(e->playCount)
                                              : QStringLiteral("Stopped at ") + TimeFormat::format(e->positionMs))
                          : QStringLiteral("Not played yet")}};
}

QString LibraryController::thumbUrl(const QString &path) const { return thumbFor(path); }

QString LibraryController::folderLabel(const QString &folder) const
{
    const QString name = QFileInfo(folder).fileName();
    return name.isEmpty() ? QDir::toNativeSeparators(folder) : name;
}

void LibraryController::clearThumbnails()
{
    m_thumbs->clearCache();
    m_model.refreshAll();
    prefetchThumbnails();
}

void LibraryController::refreshProgress()
{
    m_model.refreshAll();
    emit continueWatchingChanged();
}
