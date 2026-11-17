#include "PreviewProvider.h"
#include "FramePreviewer.h"

#include <QQuickTextureFactory>
#include <QRunnable>
#include <QThreadPool>
#include <QUrl>

namespace {

class PreviewResponse : public QQuickImageResponse, public QRunnable
{
public:
    PreviewResponse(std::shared_ptr<FramePreviewer> previewer, std::shared_ptr<std::atomic<quint64>> latest,
                    quint64 ticket, QString source, qint64 ms, QSize size)
        : m_previewer(std::move(previewer)), m_latest(std::move(latest)), m_ticket(ticket),
          m_source(std::move(source)), m_ms(ms), m_size(size)
    {
        setAutoDelete(false);
    }

    void run() override
    {
        // Out of date already (the mouse moved on): answer at once with nothing
        if (m_latest->load() == m_ticket) m_image = m_previewer->frameAt(m_source, m_ms, m_size);
        emit finished();
    }

    QQuickTextureFactory *textureFactory() const override { return QQuickTextureFactory::textureFactoryForImage(m_image); }

private:
    std::shared_ptr<FramePreviewer> m_previewer;
    std::shared_ptr<std::atomic<quint64>> m_latest;
    quint64 m_ticket;
    QString m_source;
    qint64 m_ms;
    QSize m_size;
    QImage m_image;
};

} // namespace

PreviewProvider::PreviewProvider()
    : m_previewer(std::make_shared<FramePreviewer>()), m_pool(std::make_unique<QThreadPool>()),
      m_latest(std::make_shared<std::atomic<quint64>>(0))
{
    m_pool->setMaxThreadCount(1);
}

PreviewProvider::~PreviewProvider()
{
    m_pool->clear();
    m_pool->waitForDone();
}

QQuickImageResponse *PreviewProvider::requestImageResponse(const QString &id, const QSize &requestedSize)
{
    const qint64 ms = id.section(u'/', 0, 0).toLongLong();
    const QString source = QUrl::fromPercentEncoding(id.section(u'/', 1).toUtf8());
    const QSize size = requestedSize.isValid() && !requestedSize.isEmpty() ? requestedSize : QSize(224, 126);
    const quint64 ticket = ++*m_latest;
    auto *response = new PreviewResponse(m_previewer, m_latest, ticket, source, ms, size);
    m_pool->start(response);
    return response;
}
