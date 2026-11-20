// Library rules, matching android/app/src/test/.../player/LibraryRulesTest.kt and LibraryTabsTest.kt
#include "core/LibraryRules.h"
#include <QTest>

using namespace LibraryRules;

static LibraryVideo v(const QString &path, qint64 size, qint64 modified, qint64 duration = 0)
{
    LibraryVideo x;
    x.path = path;
    const int slash = path.lastIndexOf('/');
    x.folder = path.left(slash);
    QString file = path.mid(slash + 1);
    x.name = file.left(file.lastIndexOf('.'));
    x.sizeBytes = size;
    x.modifiedMs = modified;
    x.durationMs = duration;
    return x;
}

static QStringList names(const QList<LibraryVideo> &l)
{
    QStringList out;
    for (const auto &x : l) out << x.name;
    return out;
}

class LibraryRulesTest : public QObject
{
    Q_OBJECT
private slots:
    void naturalOrder()
    {
        QVERIFY(naturalLess("Ep 2", "Ep 10"));
        QVERIFY(!naturalLess("Ep 10", "Ep 2"));
        QVERIFY(naturalLess("ep 1", "Ep 2"));
        QVERIFY(naturalLess("Ep 02", "Ep 3"));
        QVERIFY(naturalLess("Alpha", "beta"));
        QVERIFY(naturalLess("Show", "Show 1"));
        QVERIFY(!naturalLess("x", "x"));
    }
    void sortOrders()
    {
        const QList<LibraryVideo> all{v("/m/Ep 10.mkv", 300, 3000, 50), v("/m/Ep 2.mkv", 100, 1000, 90), v("/m/Ep 1.mkv", 200, 2000, 70)};
        QCOMPARE(names(sorted(all, Sort::NewestFirst)), QStringList({"Ep 10", "Ep 1", "Ep 2"}));
        QCOMPARE(names(sorted(all, Sort::OldestFirst)), QStringList({"Ep 2", "Ep 1", "Ep 10"}));
        QCOMPARE(names(sorted(all, Sort::TitleAsc)), QStringList({"Ep 1", "Ep 2", "Ep 10"}));
        QCOMPARE(names(sorted(all, Sort::TitleDesc)), QStringList({"Ep 10", "Ep 2", "Ep 1"}));
        QCOMPARE(names(sorted(all, Sort::LongestFirst)), QStringList({"Ep 2", "Ep 1", "Ep 10"}));
        QCOMPARE(names(sorted(all, Sort::LargestFirst)), QStringList({"Ep 10", "Ep 1", "Ep 2"}));
        QCOMPARE(sortFromString("largest"), Sort::LargestFirst);
        QCOMPARE(sortFromString("nonsense"), Sort::NewestFirst);
        QCOMPARE(sortToString(Sort::TitleDesc), QString("title-desc"));
    }
    void searchMatchesAllWords()
    {
        const QList<LibraryVideo> all{v("/m/Holiday/Beach Day.mp4", 1, 1), v("/m/Work/Beach talk.mp4", 1, 1), v("/m/Other.mp4", 1, 1)};
        QCOMPARE(names(search(all, "beach")), QStringList({"Beach Day", "Beach talk"}));
        QCOMPARE(names(search(all, "  BEACH   holiday ")), QStringList({"Beach Day"})); // folder name counts
        QCOMPARE(search(all, "   ").size(), 3);
        QCOMPARE(search(all, "zzz").size(), 0);
    }
    void tabs()
    {
        const qint64 now = 100LL * 24 * 3600 * 1000;
        const QList<LibraryVideo> all{v("/m/new.mp4", 1, now - 1000), v("/m/old.mp4", 1, now - RecentWindowMs - 1), v("/m/future.mp4", 1, now + 5000)};
        QCOMPARE(names(forTab(all, Tab::Recent, now, {})), QStringList({"new", "future"}));
        QCOMPARE(names(forTab(all, Tab::Favorites, now, {"/m/old.mp4"})), QStringList({"old"}));
        QCOMPARE(forTab(all, Tab::All, now, {}).size(), 3);
        QCOMPARE(tabFromString("favorites"), Tab::Favorites);
        QCOMPARE(tabToString(Tab::Folders), QString("folders"));
        QVERIFY(emptyMessage(Tab::Recent, false).contains("7 days"));
        QVERIFY(emptyMessage(Tab::All, true).contains("search"));
    }
    void foldersGroupAndCover()
    {
        const QList<LibraryVideo> all{v("/m/Show/Ep 10.mkv", 10, 5), v("/m/Show/Ep 2.mkv", 20, 9), v("/m/Films/A.mp4", 5, 1), v("/m/Show/S2/Ep 1.mkv", 1, 1)};
        const auto f = folders(all);
        QCOMPARE(f.size(), 3);
        QCOMPARE(f[0].name, QString("Films"));
        QCOMPARE(f[1].name, QString("S2"));
        QCOMPARE(f[2].name, QString("Show"));
        QCOMPARE(f[2].count, 2);
        QCOMPARE(f[2].totalBytes, 30);
        QCOMPARE(f[2].latestMs, 9);
        QCOMPARE(f[2].coverPath, QString("/m/Show/Ep 2.mkv"));
        QCOMPARE(inFolder(all, "/m/Show").size(), 2); // not the S2 subfolder
        QCOMPARE(countLabel(1), QString("1 video"));
        QCOMPARE(countLabel(3), QString("3 videos"));
    }
    void scanRules()
    {
        QVERIFY(skipDirectory(".git"));
        QVERIFY(skipDirectory("$RECYCLE.BIN"));
        QVERIFY(skipDirectory("system volume information"));
        QVERIFY(!skipDirectory("Movies"));
        QVERIFY(isUnder("C:/Users/me/Videos/Show", {"C:/Users/me/Videos"}));
        QVERIFY(isUnder("c:\\users\\me\\videos", {"C:/Users/me/Videos/"}));
        QVERIFY(!isUnder("C:/Users/me/VideosOld", {"C:/Users/me/Videos"}));
        QVERIFY(isUnder("D:/Films/x", {"D:/"}));
        QCOMPARE(addRoot({"D:/Films/Kids"}, "D:/Films"), QStringList({"D:/Films"})); // covers the old one
        QCOMPARE(addRoot({"D:/Films"}, "D:/Films/Kids"), QStringList({"D:/Films"})); // already covered
        QCOMPARE(addRoot({"D:/Films"}, "E:/TV"), QStringList({"D:/Films", "E:/TV"}));
    }
    void thumbnails()
    {
        QCOMPARE(thumbnailKey("C:/a.mkv", 10, 5000), thumbnailKey("c:\\A.mkv", 10, 5999)); // same second, same file
        QVERIFY(thumbnailKey("C:/a.mkv", 10, 5000) != thumbnailKey("C:/a.mkv", 11, 5000));
        QCOMPARE(thumbnailKey("x", 1, 1).size(), 40);
        QCOMPARE(thumbnailPositionMs(0), 1000);
        QCOMPARE(thumbnailPositionMs(3000), 1500);
        QCOMPARE(thumbnailPositionMs(5000), 1000);
        QCOMPARE(thumbnailPositionMs(120000), 12000);
        QCOMPARE(thumbnailPositionMs(7200000), 60000);
    }
    void rename()
    {
        QString err;
        QCOMPARE(renameTarget("/m/Old.mkv", "New name", &err), QString("/m/New name.mkv"));
        QVERIFY(err.isEmpty());
        QCOMPARE(renameTarget("/m/Old.mkv", "New.mkv", &err), QString("/m/New.mkv")); // extension not doubled
        QVERIFY(renameTarget("/m/Old.mkv", "  ", &err).isEmpty());
        QVERIFY(!err.isEmpty());
        QVERIFY(renameTarget("/m/Old.mkv", "a/b", &err).isEmpty());
        QVERIFY(renameTarget("/m/Old.mkv", "what?", &err).isEmpty());
        QVERIFY(renameTarget("/m/Old.mkv", "con", &err).isEmpty());
        QVERIFY(renameTarget("/m/Old.mkv", "dots.", &err).isEmpty());
    }
    void sizes()
    {
        QCOMPARE(sizeLabel(0), QString("0 B"));
        QCOMPARE(sizeLabel(512), QString("512 B"));
        QCOMPARE(sizeLabel(2048), QString("2 KB"));
        QCOMPARE(sizeLabel(5 * 1024 * 1024 + 300000), QString("5.3 MB"));
        QCOMPARE(sizeLabel(700LL * 1024 * 1024), QString("700 MB"));
        QCOMPARE(sizeLabel(3LL * 1024 * 1024 * 1024 / 2), QString("1.50 GB"));
    }
};

QTEST_APPLESS_MAIN(LibraryRulesTest)
#include "LibraryRulesTest.moc"
