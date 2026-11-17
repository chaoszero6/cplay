#include "core/MediaFiles.h"
#include <QFile>
#include <QFileInfo>
#include <QTemporaryDir>
#include <QTest>

class MediaFilesTest : public QObject
{
    Q_OBJECT
private slots:
    void kinds()
    {
        QVERIFY(MediaFiles::isVideo("C:/Movies/Film.MKV"));
        QVERIFY(MediaFiles::isVideo("https://host/v/clip.mp4?token=abc"));
        QVERIFY(!MediaFiles::isVideo("notes.txt"));
        QVERIFY(MediaFiles::isMedia("song.flac"));
        QVERIFY(MediaFiles::isSubtitle("Film.en.srt"));
        QVERIFY(!MediaFiles::isSubtitle("readme.txt"));
        QVERIFY(MediaFiles::isUrl("http://a/b.mp4"));
        QVERIFY(!MediaFiles::isUrl("C:/a/b.mp4"));
        QVERIFY(!MediaFiles::isUrl("C:\\a\\b.mp4"));
    }
    void names()
    {
        QCOMPARE(MediaFiles::displayName("C:/Movies/Alpha Clip.mp4"), "Alpha Clip.mp4");
        QCOMPARE(MediaFiles::displayName("http://192.168.0.5:8765/Bravo%20Bars.mp4?x=1"), "Bravo Bars.mp4");
        QCOMPARE(MediaFiles::displayName("https://host.example/"), "host.example");
        QCOMPARE(MediaFiles::displayName("file:///tmp/a%20b.mkv"), "a b.mkv");
    }
    void siblingSubtitles()
    {
        QTemporaryDir dir;
        for (const char *n : {"Movie.mkv", "Movie.srt", "Movie.en.srt", "Movie2.srt", "Other.ass", "Movie.txt"}) {
            QFile f(dir.filePath(n));
            QVERIFY(f.open(QIODevice::WriteOnly));
        }
        QStringList found = MediaFiles::siblingSubtitles(dir.filePath("Movie.mkv"));
        for (QString &s : found) s = QFileInfo(s).fileName();
        QCOMPARE(found, QStringList({"Movie.en.srt", "Movie.srt"}));
    }
    void filters()
    {
        const QStringList f = MediaFiles::openDialogFilters();
        QVERIFY(f[0].startsWith("Videos ("));
        QVERIFY(f[0].contains("*.mkv"));
    }
};

QTEST_GUILESS_MAIN(MediaFilesTest)
#include "MediaFilesTest.moc"
