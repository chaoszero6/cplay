#include "Playlists.h"
#include "MediaFiles.h"

#include <QDir>
#include <QFileInfo>
#include <QRegularExpression>
#include <QUrl>
#include <algorithm>

namespace M3u {

QList<Entry> parse(const QString &text)
{
    QList<Entry> out;
    QString pendingTitle;
    qint64 pendingDuration = -1;
    QString t = text;
    if (t.startsWith(QChar(0xFEFF))) t.remove(0, 1);
    const QStringList lines = t.split(QRegularExpression(QStringLiteral("\\r?\\n|\\r")));
    for (QString line : lines) {
        line = line.trimmed();
        if (line.isEmpty()) continue;
        if (line.startsWith(QLatin1String("#EXTINF"), Qt::CaseInsensitive)) {
            const QString body = line.section(u':', 1);
            const QString durationPart = body.section(u',', 0, 0).trimmed().section(u' ', 0, 0);
            bool ok = false;
            const double d = durationPart.toDouble(&ok);
            pendingDuration = ok && d >= 0 ? qint64(d) : -1;
            pendingTitle = body.contains(u',') ? body.section(u',', 1).trimmed() : QString();
            continue;
        }
        if (line.startsWith(u'#')) continue;
        out << Entry{line, pendingTitle, pendingDuration};
        pendingTitle.clear();
        pendingDuration = -1;
    }
    return out;
}

QString write(const QList<Entry> &entries)
{
    QString s = QStringLiteral("#EXTM3U\n");
    for (const auto &e : entries) {
        QString title = e.title;
        title.replace(u'\n', u' ').replace(u'\r', u' ');
        s += QStringLiteral("#EXTINF:%1,%2\n%3\n").arg(e.durationSec >= 0 ? e.durationSec : -1).arg(title, e.location);
    }
    return s;
}

QString resolve(const QString &location, const QString &playlistDir)
{
    if (MediaFiles::isUrl(location)) return location;
    QString path = location;
    if (path.startsWith(QLatin1String("file://"))) path = QUrl(path).toLocalFile();
    path.replace(u'\\', u'/');
    // Network shares (\\nas\share becomes //nas/share): cleanPath would merge the leading "//"
    if (path.startsWith(QLatin1String("//"))) return QStringLiteral("//") + QDir::cleanPath(path.mid(2));
    const bool absolute = path.startsWith(u'/') || (path.size() > 1 && path[1] == u':');
    if (absolute) return QDir::cleanPath(path);
    return QDir::cleanPath(QDir(playlistDir).filePath(path));
}

} // namespace M3u

namespace FolderQueue {

static QString withoutLeadingZeros(const QString &digits)
{
    int i = 0;
    while (i < digits.size() - 1 && digits[i] == u'0') ++i;
    return digits.mid(i);
}

bool naturalLess(const QString &a, const QString &b)
{
    // Runs of digits compare by value ("2" < "10"), everything else case-insensitively
    static const QRegularExpression chunk(QStringLiteral("(\\d+)|(\\D+)"));
    auto ia = chunk.globalMatch(a), ib = chunk.globalMatch(b);
    while (ia.hasNext() && ib.hasNext()) {
        const auto ma = ia.next(), mb = ib.next();
        const bool da = ma.capturedLength(1) > 0, db = mb.capturedLength(1) > 0;
        if (da && db) {
            const QString na = withoutLeadingZeros(ma.captured(1)), nb = withoutLeadingZeros(mb.captured(1));
            if (na.size() != nb.size()) return na.size() < nb.size();
            if (na != nb) return na < nb;
        } else {
            const int c = QString::compare(ma.captured(0), mb.captured(0), Qt::CaseInsensitive);
            if (c != 0) return c < 0;
        }
    }
    if (ia.hasNext() != ib.hasNext()) return ib.hasNext();
    return a < b;
}

QStringList mediaIn(const QString &folder)
{
    QStringList out;
    const auto entries = QDir(folder).entryInfoList(QDir::Files | QDir::Readable);
    for (const auto &e : entries) if (MediaFiles::isMedia(e.fileName())) out << e.absoluteFilePath();
    std::sort(out.begin(), out.end(), [](const QString &x, const QString &y) {
        return naturalLess(QFileInfo(x).fileName(), QFileInfo(y).fileName());
    });
    return out;
}

Queue forFile(const QString &file)
{
    const QFileInfo info(file);
    QStringList all = mediaIn(info.absolutePath());
    const int i = all.indexOf(info.absoluteFilePath());
    if (i < 0) return {{file}, 0};
    return {all, int(i)};
}

} // namespace FolderQueue
