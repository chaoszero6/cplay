#include "SingleInstance.h"

#include <QLocalServer>
#include <QLocalSocket>

SingleInstance::SingleInstance(const QString &key, QObject *parent) : QObject(parent), m_key(key) {}

bool SingleInstance::forwardToRunning(const QStringList &files)
{
    QLocalSocket socket;
    socket.connectToServer(m_key);
    if (!socket.waitForConnected(300)) return false;
    // An empty list still counts: it brings the running window to the front
    socket.write(files.join(u'\n').toUtf8() + "\n\x04");
    socket.flush();
    socket.waitForBytesWritten(1000);
    socket.disconnectFromServer();
    return true;
}

void SingleInstance::listen()
{
    m_server = new QLocalServer(this);
    m_server->setSocketOptions(QLocalServer::UserAccessOption);
    if (!m_server->listen(m_key)) {
        // A crashed instance can leave the name behind
        QLocalServer::removeServer(m_key);
        m_server->listen(m_key);
    }
    connect(m_server, &QLocalServer::newConnection, this, [this] {
        while (QLocalSocket *s = m_server->nextPendingConnection()) {
            auto buffer = std::make_shared<QByteArray>();
            connect(s, &QLocalSocket::readyRead, s, [this, s, buffer] {
                buffer->append(s->readAll());
                if (!buffer->endsWith("\x04")) return;
                buffer->chop(1);
                QStringList files = QString::fromUtf8(*buffer).split(u'\n', Qt::SkipEmptyParts);
                emit filesReceived(files);
                s->disconnectFromServer();
            });
            connect(s, &QLocalSocket::disconnected, s, &QObject::deleteLater);
        }
    });
}
