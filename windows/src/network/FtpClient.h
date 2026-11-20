#pragma once
#include "core/NetRules.h"

#include <QString>
#include <memory>

class QTcpSocket;

/**
 * Small blocking FTP client (passive mode, binary) for worker threads:
 * login, list a folder (MLSD, LIST fallback), file size, and reading a file
 * from any offset (REST + RETR). The data connection goes to the control
 * connection's peer, whatever address the server advertises (NAT-safe).
 */
class FtpClient
{
public:
    FtpClient();
    ~FtpClient();
    bool connectAndLogin(const QString &host, int port, const QString &user, const QString &password, int timeoutMs = 8000);
    QList<NetRules::FtpEntry> list(const QString &path);
    qint64 size(const QString &path);
    /** Starts sending [path] from [offset]; then call read() until it returns 0. */
    bool startRead(const QString &path, qint64 offset);
    qint64 read(char *buf, qint64 max);
    void abortRead();
    QString error() const { return m_error; }

private:
    int command(const QString &cmd, QString *replyText = nullptr);
    int readReply(QString *text);
    std::unique_ptr<QTcpSocket> openData();
    QByteArray readAllData(QTcpSocket *data);

    std::unique_ptr<QTcpSocket> m_ctrl;
    std::unique_ptr<QTcpSocket> m_data;
    QString m_error;
    int m_timeout = 8000;
    bool m_mlsd = true;
};
