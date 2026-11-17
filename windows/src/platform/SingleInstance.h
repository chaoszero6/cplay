#pragma once
#include <QObject>
#include <QStringList>

class QLocalServer;

/**
 * One CPlay window: a second launch (double-click a video, "Open with")
 * passes its files to the running window and exits.
 */
class SingleInstance : public QObject
{
    Q_OBJECT
public:
    explicit SingleInstance(const QString &key, QObject *parent = nullptr);
    /** True when another instance took [files]; this process should then exit. */
    bool forwardToRunning(const QStringList &files);
    /** Starts listening for later launches. */
    void listen();

signals:
    void filesReceived(const QStringList &files);

private:
    QString m_key;
    QLocalServer *m_server = nullptr;
};
