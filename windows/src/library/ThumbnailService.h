#pragma once
#include <QHash>
#include <QImage>
#include <QMutex>
#include <QQuickAsyncImageProvider>
#include <QSize>
#include <QString>
#include <functional>
#include <memory>
#include <vector>

class FramePreviewer;
class QThreadPool;

/**
 * Library thumbnails: one frame ~10% into each video (LibraryRules), saved as
 * JPEG in the cache folder, named after the file's path + size + date so an
 * edited file gets a new picture. Also learns each file's duration on the way.
 * Thread-safe; several grabbers (hidden libmpv instances) work in parallel.
 */
class ThumbnailService
{
public:
    using ProbedFn = std::function<void(const QString &path, qint64 durationMs)>;
    ThumbnailService(QString cacheDir, int grabbers);
    ~ThumbnailService();

    /** Cached or newly made thumbnail of a local video (null image if it can't be decoded). */
    QImage thumbnail(const QString &path, QSize maxSize = QSize(320, 180));
    bool hasCached(const QString &path) const;
    /** Called (from a worker thread) whenever a file's duration becomes known. */
    void setProbedCallback(ProbedFn fn);
    void clearCache();
    QString cacheDir() const { return m_dir; }

private:
    QString cacheFile(const QString &path, QString *keyOut = nullptr) const;
    std::shared_ptr<FramePreviewer> acquire();
    void release(const std::shared_ptr<FramePreviewer> &p);

    QString m_dir;
    mutable QMutex m_lock;
    std::vector<std::shared_ptr<FramePreviewer>> m_free;
    int m_total = 0;
    int m_max = 1;
    ProbedFn m_probed;
    QHash<QString, QImage> m_memory; // small in-memory cache of recent thumbnails
};

/** "image://thumb/<percent-encoded path>". */
class ThumbnailProvider : public QQuickAsyncImageProvider
{
public:
    explicit ThumbnailProvider(std::shared_ptr<ThumbnailService> service);
    ~ThumbnailProvider() override;
    QQuickImageResponse *requestImageResponse(const QString &id, const QSize &requestedSize) override;

private:
    std::shared_ptr<ThumbnailService> m_service;
    std::unique_ptr<QThreadPool> m_pool;
};
