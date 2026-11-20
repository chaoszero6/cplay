#include "ThumbnailService.h"
#include "core/LibraryRules.h"
#include "player/FramePreviewer.h"

#include <QDir>
#include <QFile>
#include <QFileInfo>
#include <QQuickTextureFactory>
#include <QRunnable>
#include <QSaveFile>
#include <QThread>
#include <QThreadPool>
#include <QUrl>

ThumbnailService::ThumbnailService(QString cacheDir, int grabbers) : m_dir(std::move(cacheDir)), m_max(std::max(1, grabbers))
{
    QDir().mkpath(m_dir);
}

ThumbnailService::~ThumbnailService() = default;

void ThumbnailService::setProbedCallback(ProbedFn fn)
{
    QMutexLocker l(&m_lock);
    m_probed = std::move(fn);
}

QString ThumbnailService::cacheFile(const QString &path, QString *keyOut) const
{
    const QFileInfo info(path);
    const QString key = LibraryRules::thumbnailKey(info.absoluteFilePath(), info.size(), info.lastModified().toMSecsSinceEpoch());
    if (keyOut) *keyOut = key;
    return m_dir + u'/' + key + QStringLiteral(".jpg");
}

bool ThumbnailService::hasCached(const QString &path) const
{
    const QString file = cacheFile(path);
    return QFileInfo::exists(file) || QFileInfo::exists(file + QStringLiteral(".none"));
}

std::shared_ptr<FramePreviewer> ThumbnailService::acquire()
{
    for (;;) {
        {
            QMutexLocker l(&m_lock);
            if (!m_free.empty()) {
                auto p = m_free.back();
                m_free.pop_back();
                return p;
            }
            if (m_total < m_max) {
                ++m_total;
                l.unlock();
                return std::make_shared<FramePreviewer>();
            }
        }
        QThread::msleep(15);
    }
}

void ThumbnailService::release(const std::shared_ptr<FramePreviewer> &p)
{
    QMutexLocker l(&m_lock);
    m_free.push_back(p);
}

QImage ThumbnailService::thumbnail(const QString &path, QSize maxSize)
{
    if (!QFileInfo(path).isFile()) return {};
    QString key;
    const QString file = cacheFile(path, &key);
    {
        QMutexLocker l(&m_lock);
        const auto it = m_memory.constFind(key);
        if (it != m_memory.constEnd()) return *it;
    }
    QImage image;
    if (QFileInfo::exists(file)) {
        image.load(file);
    } else if (QFileInfo::exists(file + QStringLiteral(".none"))) {
        return {}; // tried before: not decodable
    } else {
        auto grabber = acquire();
        qint64 duration = 0;
        image = grabber->thumbnail(path, maxSize, &duration);
        release(grabber);
        if (image.isNull()) {
            QFile none(file + QStringLiteral(".none"));
            none.open(QIODevice::WriteOnly);
        } else {
            QSaveFile out(file);
            if (out.open(QIODevice::WriteOnly) && image.convertToFormat(QImage::Format_RGB888).save(&out, "JPG", 82)) out.commit();
        }
        ProbedFn cb;
        {
            QMutexLocker l(&m_lock);
            cb = m_probed;
        }
        if (cb && duration > 0) cb(path, duration);
    }
    if (!image.isNull()) {
        QMutexLocker l(&m_lock);
        if (m_memory.size() > 400) m_memory.clear();
        m_memory.insert(key, image);
    }
    return image;
}

void ThumbnailService::clearCache()
{
    {
        QMutexLocker l(&m_lock);
        m_memory.clear();
    }
    QDir dir(m_dir);
    for (const auto &f : dir.entryList({QStringLiteral("*.jpg"), QStringLiteral("*.none")}, QDir::Files)) dir.remove(f);
}

// ---- image provider --------------------------------------------------------

namespace {
class ThumbResponse : public QQuickImageResponse, public QRunnable
{
public:
    ThumbResponse(std::shared_ptr<ThumbnailService> s, QString path, QSize size)
        : m_service(std::move(s)), m_path(std::move(path)), m_size(size)
    {
        setAutoDelete(false);
    }
    void run() override
    {
        if (!m_cancelled.load()) {
            QImage img = m_service->thumbnail(m_path);
            if (!img.isNull() && m_size.isValid() && !m_size.isEmpty() && img.width() > m_size.width() * 1.5)
                img = img.scaled(m_size, Qt::KeepAspectRatio, Qt::SmoothTransformation);
            m_image = img;
        }
        emit finished();
    }
    void cancel() override { m_cancelled = true; }
    QQuickTextureFactory *textureFactory() const override { return QQuickTextureFactory::textureFactoryForImage(m_image); }

private:
    std::shared_ptr<ThumbnailService> m_service;
    QString m_path;
    QSize m_size;
    QImage m_image;
    std::atomic<bool> m_cancelled{false};
};
}

ThumbnailProvider::ThumbnailProvider(std::shared_ptr<ThumbnailService> service)
    : m_service(std::move(service)), m_pool(std::make_unique<QThreadPool>())
{
    m_pool->setMaxThreadCount(2);
}

ThumbnailProvider::~ThumbnailProvider()
{
    m_pool->clear();
    m_pool->waitForDone();
}

QQuickImageResponse *ThumbnailProvider::requestImageResponse(const QString &id, const QSize &requestedSize)
{
    // id = "<percent-encoded path>" (a "?v=" suffix only busts QML's cache)
    const QString path = QUrl::fromPercentEncoding(id.section(u'?', 0, 0).toUtf8());
    auto *response = new ThumbResponse(m_service, path, requestedSize);
    m_pool->start(response);
    return response;
}
