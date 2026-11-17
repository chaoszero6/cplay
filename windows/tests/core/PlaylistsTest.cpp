#include "core/Playlists.h"
#include <QFile>
#include <QTemporaryDir>
#include <QTest>

class PlaylistsTest : public QObject
{
    Q_OBJECT
private slots:
    void parseExtinf()
    {
        const auto e = M3u::parse(QStringLiteral("\xFEFF#EXTM3U\r\n#EXTINF:123 tvg-id=\"x\",Movie, part 1\r\nC:\\Videos\\a.mkv\r\n\r\n# comment\r\nb.mp4\r\n#EXTINF:-1,Live\nhttp://h/live.m3u8\n"));
        QCOMPARE(e.size(), 3);
        QCOMPARE(e[0].location, "C:\\Videos\\a.mkv");
        QCOMPARE(e[0].title, "Movie, part 1");
        QCOMPARE(e[0].durationSec, 123);
        QCOMPARE(e[1].title, "");
        QCOMPARE(e[1].durationSec, -1);
        QCOMPARE(e[2].location, "http://h/live.m3u8");
    }
    void writeRoundTrip()
    {
        const QList<M3u::Entry> in{{"C:/v/a.mkv", "A\nB", 61}, {"http://x/y.mp4", "Y", -1}};
        const QString text = M3u::write(in);
        QVERIFY(text.startsWith("#EXTM3U\n#EXTINF:61,A B\nC:/v/a.mkv\n"));
        const auto out = M3u::parse(text);
        QCOMPARE(out.size(), 2);
        QCOMPARE(out[0].title, "A B");
        QCOMPARE(out[1].durationSec, -1);
    }
    void resolvePaths()
    {
        QCOMPARE(M3u::resolve("Show\\Ep1.mkv", "C:/Lists"), "C:/Lists/Show/Ep1.mkv");
        QCOMPARE(M3u::resolve("../Movies/a.mp4", "/home/u/lists"), "/home/u/Movies/a.mp4");
        QCOMPARE(M3u::resolve("D:\\Films\\b.mkv", "C:/Lists"), "D:/Films/b.mkv");
        QCOMPARE(M3u::resolve("http://h/v.mp4", "C:/Lists"), "http://h/v.mp4");
        QCOMPARE(M3u::resolve("//nas/share/c.mkv", "C:/Lists"), "//nas/share/c.mkv");
        QCOMPARE(M3u::resolve("\\\\nas\\share\\d.mkv", "C:/Lists"), "//nas/share/d.mkv");
        QCOMPARE(M3u::resolve("file:///tmp/x%20y.mkv", "C:/Lists"), "/tmp/x y.mkv");
    }
    void naturalOrder()
    {
        QStringList names{"Ep 10.mkv", "ep 2.mkv", "Ep 1.mkv", "Ep 02b.mkv", "Bonus.mkv"};
        std::sort(names.begin(), names.end(), FolderQueue::naturalLess);
        QCOMPARE(names, QStringList({"Bonus.mkv", "Ep 1.mkv", "ep 2.mkv", "Ep 02b.mkv", "Ep 10.mkv"}));
    }
    void folderQueue()
    {
        QTemporaryDir dir;
        for (const char *n : {"Ep 10.mkv", "Ep 2.mkv", "Ep 1.mkv", "notes.txt", "Ep 2.srt", "song.mp3"}) {
            QFile f(dir.filePath(n));
            QVERIFY(f.open(QIODevice::WriteOnly));
        }
        const auto q = FolderQueue::forFile(dir.filePath("Ep 2.mkv"));
        QStringList names;
        for (const auto &f : q.files) names << QFileInfo(f).fileName();
        QCOMPARE(names, QStringList({"Ep 1.mkv", "Ep 2.mkv", "Ep 10.mkv", "song.mp3"}));
        QCOMPARE(q.index, 1);
        const auto lone = FolderQueue::forFile("/does/not/exist.mkv");
        QCOMPARE(lone.files, QStringList({"/does/not/exist.mkv"}));
    }
};

QTEST_GUILESS_MAIN(PlaylistsTest)
#include "PlaylistsTest.moc"
