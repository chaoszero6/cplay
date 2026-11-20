#include "FtpClient.h"

#include <QTcpSocket>

FtpClient::FtpClient() = default;

FtpClient::~FtpClient()
{
    abortRead();
    if (m_ctrl && m_ctrl->state() == QAbstractSocket::ConnectedState) {
        m_ctrl->write("QUIT\r\n");
        m_ctrl->waitForBytesWritten(500);
        m_ctrl->disconnectFromHost();
    }
}

int FtpClient::readReply(QString *text)
{
    // Multi-line replies: "123-first", ..., "123 last"
    QString code;
    QStringList lines;
    for (;;) {
        while (!m_ctrl->canReadLine()) {
            if (!m_ctrl->waitForReadyRead(m_timeout)) {
                m_error = QStringLiteral("The FTP server did not answer");
                return -1;
            }
        }
        const QString line = QString::fromUtf8(m_ctrl->readLine()).trimmed();
        lines << line;
        if (code.isEmpty() && line.size() >= 3) code = line.left(3);
        if (line.size() >= 4 && line.left(3) == code && line.at(3) == u' ') break;
        if (line.size() == 3 && line == code) break;
    }
    if (text) *text = lines.join(u'\n');
    return code.toInt();
}

int FtpClient::command(const QString &cmd, QString *replyText)
{
    if (!m_ctrl) return -1;
    m_ctrl->write(cmd.toUtf8() + "\r\n");
    if (!m_ctrl->waitForBytesWritten(m_timeout)) {
        m_error = QStringLiteral("Lost the connection to the FTP server");
        return -1;
    }
    return readReply(replyText);
}

bool FtpClient::connectAndLogin(const QString &host, int port, const QString &user, const QString &password, int timeoutMs)
{
    m_timeout = timeoutMs;
    m_ctrl = std::make_unique<QTcpSocket>();
    m_ctrl->connectToHost(host, quint16(port > 0 ? port : 21));
    if (!m_ctrl->waitForConnected(timeoutMs)) {
        m_error = QStringLiteral("Could not reach %1:%2 (%3)").arg(host).arg(port).arg(m_ctrl->errorString());
        return false;
    }
    QString text;
    if (readReply(&text) != 220) { if (m_error.isEmpty()) m_error = QStringLiteral("Not an FTP server: ") + text; return false; }
    int c = command(QStringLiteral("USER ") + (user.isEmpty() ? QStringLiteral("anonymous") : user), &text);
    if (c == 331) c = command(QStringLiteral("PASS ") + (user.isEmpty() ? QStringLiteral("cplay@") : password), &text);
    if (c != 230 && c != 202) { m_error = c == 530 ? QStringLiteral("Wrong user name or password") : QStringLiteral("Login failed: ") + text; return false; }
    command(QStringLiteral("OPTS UTF8 ON"));
    if (command(QStringLiteral("TYPE I"), &text) != 200) { m_error = text; return false; }
    return true;
}

std::unique_ptr<QTcpSocket> FtpClient::openData()
{
    QString text;
    int port = -1;
    if (command(QStringLiteral("EPSV"), &text) == 229) port = NetRules::parseEpsvPort(text);
    if (port < 0 && command(QStringLiteral("PASV"), &text) == 227) port = NetRules::parsePasvPort(text);
    if (port <= 0) { m_error = QStringLiteral("The FTP server refused passive mode"); return {}; }
    auto data = std::make_unique<QTcpSocket>();
    data->connectToHost(m_ctrl->peerAddress(), quint16(port));
    if (!data->waitForConnected(m_timeout)) { m_error = QStringLiteral("Could not open the FTP data connection"); return {}; }
    return data;
}

QByteArray FtpClient::readAllData(QTcpSocket *data)
{
    QByteArray all;
    while (data->state() == QAbstractSocket::ConnectedState || data->bytesAvailable() > 0) {
        if (data->bytesAvailable() == 0 && !data->waitForReadyRead(m_timeout)) break;
        all += data->readAll();
        if (all.size() > 64 * 1024 * 1024) break;
    }
    return all;
}

QList<NetRules::FtpEntry> FtpClient::list(const QString &path)
{
    QList<NetRules::FtpEntry> out;
    const QString dir = path.isEmpty() ? QStringLiteral("/") : (path.startsWith(u'/') ? path : u'/' + path);
    for (int attempt = 0; attempt < 2; ++attempt) {
        const bool mlsd = m_mlsd && attempt == 0;
        auto data = openData();
        if (!data) return out;
        QString text;
        const int c = command((mlsd ? QStringLiteral("MLSD ") : QStringLiteral("LIST ")) + dir, &text);
        if (c == 500 || c == 502 || c == 504) { m_mlsd = false; continue; } // no MLSD: use LIST
        if (c != 150 && c != 125) { m_error = c == 550 ? QStringLiteral("Folder not found or no access") : text; return out; }
        const QByteArray raw = readAllData(data.get());
        data->close();
        readReply(&text); // 226
        for (const QByteArray &l : raw.split('\n')) {
            const QString line = QString::fromUtf8(l).trimmed();
            if (line.isEmpty()) continue;
            const auto e = mlsd ? NetRules::parseMlsdLine(line) : NetRules::parseListLine(line);
            if (e) out << *e;
        }
        return out;
    }
    return out;
}

qint64 FtpClient::size(const QString &path)
{
    QString text;
    if (command(QStringLiteral("SIZE ") + (path.startsWith(u'/') ? path : u'/' + path), &text) != 213) return -1;
    return text.section(u' ', 1).trimmed().toLongLong();
}

bool FtpClient::startRead(const QString &path, qint64 offset)
{
    abortRead();
    auto data = openData();
    if (!data) return false;
    QString text;
    if (offset > 0 && command(QStringLiteral("REST %1").arg(offset), &text) != 350) { m_error = QStringLiteral("The FTP server cannot resume: ") + text; return false; }
    const int c = command(QStringLiteral("RETR ") + (path.startsWith(u'/') ? path : u'/' + path), &text);
    if (c != 150 && c != 125) { m_error = text; return false; }
    m_data = std::move(data);
    return true;
}

qint64 FtpClient::read(char *buf, qint64 max)
{
    if (!m_data) return -1;
    if (m_data->bytesAvailable() == 0) {
        if (m_data->state() != QAbstractSocket::ConnectedState) return 0;
        if (!m_data->waitForReadyRead(m_timeout) && m_data->bytesAvailable() == 0) {
            return m_data->state() == QAbstractSocket::ConnectedState ? -1 : 0;
        }
    }
    return m_data->read(buf, max);
}

void FtpClient::abortRead()
{
    if (!m_data) return;
    const bool finished = m_data->state() != QAbstractSocket::ConnectedState && m_data->bytesAvailable() == 0;
    m_data->abort();
    m_data.reset();
    if (!m_ctrl) return;
    if (finished) {
        QString text;
        readReply(&text); // 226 transfer complete
    } else {
        // Stopped midway: ABOR, then the server sends 426/451 and 226
        QString text;
        m_ctrl->write("ABOR\r\n");
        m_ctrl->waitForBytesWritten(m_timeout);
        for (int i = 0; i < 2; ++i) {
            const int c = readReply(&text);
            if (c == 226 || c == 225 || c < 0) break;
        }
    }
}
