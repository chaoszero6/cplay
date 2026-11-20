#pragma once
#include "core/LibraryRules.h"

#include <QAbstractListModel>
#include <QFileSystemWatcher>
#include <QHash>
#include <QObject>
#include <QSet>
#include <QTimer>
#include <QVariantList>
#include <atomic>
#include <memory>

class ResumeStore;
class ThumbnailService;

/** The rows the library grid/list shows: videos, or folders on the Folders tab. */
class LibraryModel : public QAbstractListModel
{
    Q_OBJECT
public:
    enum Role {
        PathRole = Qt::UserRole + 1, NameRole, FolderRole, FolderNameRole, SizeRole, SizeLabelRole, DateLabelRole,
        DurationRole, DurationLabelRole, ProgressRole, FinishedRole, FavoriteRole, IsNewRole, ThumbRole,
        IsFolderRole, CountLabelRole
    };
    using QAbstractListModel::QAbstractListModel;
    int rowCount(const QModelIndex &parent = {}) const override;
    QVariant data(const QModelIndex &index, int role) const override;
    QHash<int, QByteArray> roleNames() const override;

    struct Row {
        bool folder = false;
        LibraryVideo video;      // video rows
        LibraryFolder dir;       // folder rows
    };
    void reset(QList<Row> rows);
    const QList<Row> &rows() const { return m_rows; }
    /** Duration/progress/favourite of one file changed. */
    void refreshPath(const QString &path);
    void refreshAll();

    std::function<double(const QString &)> progressOf;
    std::function<bool(const QString &)> finishedOf;
    std::function<bool(const QString &)> favoriteOf;
    std::function<qint64(const QString &)> durationOf;

private:
    QList<Row> m_rows;
};

/**
 * The local video library ("library" in QML): watched folders, scanning,
 * favourites, thumbnails and the tab/sort/search state of the library page.
 * Rules live in core/LibraryRules (unit tested); files are only read, except
 * for Rename and Delete (to the Recycle Bin), which the user asks for.
 */
class LibraryController : public QObject
{
    Q_OBJECT
    Q_PROPERTY(LibraryModel *model READ model CONSTANT)
    Q_PROPERTY(QStringList folders READ folders NOTIFY foldersChanged)
    Q_PROPERTY(bool scanning READ scanning NOTIFY scanningChanged)
    Q_PROPERTY(int videoCount READ videoCount NOTIFY videosChanged)
    Q_PROPERTY(QString tab READ tab WRITE setTab NOTIFY viewChanged)
    Q_PROPERTY(QString sort READ sort WRITE setSort NOTIFY viewChanged)
    Q_PROPERTY(QString query READ query WRITE setQuery NOTIFY viewChanged)
    Q_PROPERTY(QString openFolder READ openFolder WRITE setOpenFolder NOTIFY viewChanged)
    Q_PROPERTY(QString viewMode READ viewMode WRITE setViewMode NOTIFY viewChanged)
    Q_PROPERTY(QString emptyMessage READ emptyMessage NOTIFY viewChanged)
    Q_PROPERTY(QVariantList sortOptions READ sortOptions CONSTANT)
    Q_PROPERTY(QVariantList continueWatching READ continueWatching NOTIFY continueWatchingChanged)
    Q_PROPERTY(int favoriteRevision READ favoriteRevision NOTIFY favoritesChanged)

public:
    LibraryController(const QString &dataDir, ResumeStore *resume, std::shared_ptr<ThumbnailService> thumbs,
                      QObject *parent = nullptr);
    ~LibraryController() override;

    LibraryModel *model() { return &m_model; }
    QStringList folders() const { return m_roots; }
    bool scanning() const { return m_scanning; }
    int videoCount() const { return int(m_videos.size()); }
    QString tab() const { return LibraryRules::tabToString(m_tab); }
    void setTab(const QString &t);
    QString sort() const { return LibraryRules::sortToString(m_sort); }
    void setSort(const QString &s);
    QString query() const { return m_query; }
    void setQuery(const QString &q);
    QString openFolder() const { return m_openFolder; }
    void setOpenFolder(const QString &f);
    QString viewMode() const { return m_viewMode; }
    void setViewMode(const QString &m);
    QString emptyMessage() const;
    QVariantList sortOptions() const;
    QVariantList continueWatching() const;
    int favoriteRevision() const { return m_favRevision; }

    Q_INVOKABLE void rescan();
    Q_INVOKABLE bool addFolder(const QUrl &folder);
    Q_INVOKABLE bool addFolderPath(const QString &folder);
    Q_INVOKABLE void removeFolder(const QString &folder);
    Q_INVOKABLE bool isFavorite(const QString &path) const { return m_favorites.contains(path); }
    Q_INVOKABLE void setFavorite(const QString &path, bool favorite);
    /** Empty string on success, else a message for the user. */
    Q_INVOKABLE QString rename(const QString &path, const QString &newName);
    Q_INVOKABLE QString moveToRecycleBin(const QString &path);
    Q_INVOKABLE void showInFolder(const QString &path) const;
    /** All videos the page currently shows, in order (to queue them). */
    Q_INVOKABLE QStringList visiblePaths() const;
    /** Videos of one folder in natural order (play a folder). */
    Q_INVOKABLE QStringList pathsInFolder(const QString &folder) const;
    Q_INVOKABLE QVariantMap info(const QString &path) const;
    Q_INVOKABLE QString thumbUrl(const QString &path) const;
    Q_INVOKABLE QString folderLabel(const QString &folder) const;
    Q_INVOKABLE void clearThumbnails();
    /** Progress changed somewhere (after playback). */
    Q_INVOKABLE void refreshProgress();
    Q_INVOKABLE QString defaultFolder() const;

signals:
    void foldersChanged();
    void scanningChanged();
    void videosChanged();
    void viewChanged();
    void continueWatchingChanged();
    void favoritesChanged();
    void durationProbed(const QString &path, qint64 durationMs);

private:
    void load();
    void saveState() const;
    void saveDurations() const;
    void applyScan(QList<LibraryVideo> videos, QStringList dirs);
    void rebuild();
    void onProbed(const QString &path, qint64 durationMs);
    void prefetchThumbnails();

    QString m_dataDir;
    ResumeStore *m_resume;
    std::shared_ptr<ThumbnailService> m_thumbs;
    LibraryModel m_model;
    QStringList m_roots;
    QSet<QString> m_favorites;
    QHash<QString, qint64> m_durations; // thumbnail key -> ms
    QList<LibraryVideo> m_videos;
    QFileSystemWatcher m_watcher;
    QTimer m_rescanTimer;
    QTimer m_saveDurationsTimer;
    LibraryRules::Tab m_tab = LibraryRules::Tab::All;
    LibraryRules::Sort m_sort = LibraryRules::Sort::NewestFirst;
    QString m_query;
    QString m_openFolder;
    QString m_viewMode = QStringLiteral("grid");
    bool m_scanning = false;
    bool m_rescanAgain = false;
    int m_favRevision = 0;
    std::shared_ptr<std::atomic<bool>> m_alive;
};
