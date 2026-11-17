#include "MediaFiles.h"

#include <QDir>
#include <QFileInfo>
#include <QUrl>

namespace MediaFiles {

const QStringList &videoExtensions()
{
    static const QStringList ext{
        "mp4", "m4v", "mkv", "webm", "avi", "mov", "wmv", "flv", "ts", "m2ts", "mts", "mpg", "mpeg",
        "vob", "3gp", "ogv", "rm", "rmvb", "asf", "divx", "f4v", "mxf", "y4m"};
    return ext;
}

const QStringList &audioExtensions()
{
    static const QStringList ext{"mp3", "m4a", "aac", "flac", "ogg", "opus", "wav", "wma", "ac3", "dts", "mka", "eac3"};
    return ext;
}

const QStringList &subtitleExtensions()
{
    static const QStringList ext{"srt", "ass", "ssa", "vtt", "sub", "sup", "idx", "smi", "txt"};
    return ext;
}

static QString suffixOf(const QString &location)
{
    QString path = location;
    if (isUrl(location)) path = QUrl(location).path();
    return QFileInfo(path).suffix().toLower();
}

bool isVideo(const QString &path) { return videoExtensions().contains(suffixOf(path)); }
bool isMedia(const QString &path) { return isVideo(path) || audioExtensions().contains(suffixOf(path)); }
bool isSubtitle(const QString &path)
{
    const QString s = suffixOf(path);
    return s != QLatin1String("txt") && subtitleExtensions().contains(s);
}

bool isUrl(const QString &location)
{
    const int i = location.indexOf(QLatin1String("://"));
    // "C:\..." and "C:/..." are paths, not URLs
    return i > 1 && !location.startsWith(QLatin1String("file://"));
}

QString displayName(const QString &location)
{
    if (location.startsWith(QLatin1String("file://")))
        return QFileInfo(QUrl(location).toLocalFile()).fileName();
    if (isUrl(location)) {
        const QUrl url(location);
        QString name = QUrl::fromPercentEncoding(url.path().section(u'/', -1, -1, QString::SectionSkipEmpty).toUtf8());
        return name.isEmpty() ? url.host() : name;
    }
    return QFileInfo(location).fileName();
}

QStringList siblingSubtitles(const QString &videoPath)
{
    const QFileInfo info(videoPath);
    if (!info.exists()) return {};
    const QString base = info.completeBaseName();
    QStringList out;
    const auto entries = info.dir().entryInfoList(QDir::Files, QDir::Name);
    for (const auto &e : entries) {
        if (!isSubtitle(e.fileName())) continue;
        const QString name = e.completeBaseName();
        // "Movie.srt", "Movie.en.srt", "Movie.English.forced.srt"
        if (name.compare(base, Qt::CaseInsensitive) == 0 ||
            name.startsWith(base + u'.', Qt::CaseInsensitive))
            out << e.absoluteFilePath();
    }
    return out;
}

QStringList openDialogFilters()
{
    auto patterns = [](const QStringList &exts) {
        QStringList p;
        for (const auto &e : exts) p << QStringLiteral("*.") + e;
        return p.join(u' ');
    };
    return {
        QStringLiteral("Videos (%1)").arg(patterns(videoExtensions())),
        QStringLiteral("Audio (%1)").arg(patterns(audioExtensions())),
        QStringLiteral("All files (*)"),
    };
}

} // namespace MediaFiles
