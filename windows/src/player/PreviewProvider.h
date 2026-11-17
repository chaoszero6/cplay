#pragma once
#include <QQuickAsyncImageProvider>
#include <atomic>
#include <memory>

class FramePreviewer;
class QThreadPool;

/**
 * "image://preview/<ms>/<percent-encoded location>": seek-bar preview frames.
 * Requests run one at a time on their own thread; while the user scrubs,
 * requests that are already out of date are skipped.
 */
class PreviewProvider : public QQuickAsyncImageProvider
{
public:
    PreviewProvider();
    ~PreviewProvider() override;
    QQuickImageResponse *requestImageResponse(const QString &id, const QSize &requestedSize) override;

private:
    std::shared_ptr<FramePreviewer> m_previewer;
    std::unique_ptr<QThreadPool> m_pool;
    std::shared_ptr<std::atomic<quint64>> m_latest;
};
