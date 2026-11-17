// Ported from android/app/src/test/.../player/BookmarksAndPrefsTest.kt (bookmark part)
#include "core/Bookmarks.h"
#include <QTemporaryDir>
#include <QTest>

using namespace BookmarkRules;

class BookmarksTest : public QObject
{
    Q_OBJECT
private slots:
    void addSortsAndLabels()
    {
        QList<Bookmark> all;
        all = add(all, {"a.mkv", 90000, "", 1});
        all = add(all, {"a.mkv", 30000, "  Intro end ", 2});
        all = add(all, {"b.mkv", 10000, "", 3});
        const auto mine = forLocation(all, "a.mkv");
        QCOMPARE(mine.size(), 2);
        QCOMPARE(mine[0].positionMs, 30000);
        QCOMPARE(mine[0].label, "Intro end");
        QCOMPARE(mine[1].label, "Bookmark at 1:30");
    }
    void nearbyPressIsIgnored()
    {
        QList<Bookmark> all = add({}, {"a", 10000, "", 1});
        QCOMPARE(add(all, {"a", 11000, "", 2}).size(), 1);
        QCOMPARE(add(all, {"a", 11500, "", 2}).size(), 2);   // exactly the window: a new point
        QCOMPARE(add(all, {"b", 10000, "", 2}).size(), 2);   // other video
        QCOMPARE(add({}, {"a", -50, "", 1})[0].positionMs, 0);
    }
    void capDropsTheOldest()
    {
        QList<Bookmark> all;
        for (int i = 0; i < MaxPerVideo; ++i) all = add(all, {"a", i * 10000LL, "", 100 + i});
        all = add(all, {"a", 5000000, "", 999});
        const auto mine = forLocation(all, "a");
        QCOMPARE(mine.size(), MaxPerVideo);
        QVERIFY(std::none_of(mine.begin(), mine.end(), [](const Bookmark &b) { return b.createdSec == 100; }));
    }
    void removeAndRename()
    {
        QList<Bookmark> all = add(add({}, {"a", 10000, "", 1}), {"a", 20000, "", 2});
        all = rename(all, "a", 20000, " Fight ");
        QCOMPARE(forLocation(all, "a")[1].label, "Fight");
        all = rename(all, "a", 20000, "   ");
        QCOMPARE(forLocation(all, "a")[1].label, "Bookmark at 0:20");
        all = remove(all, "a", 10000);
        QCOMPARE(forLocation(all, "a").size(), 1);
    }
    void nextAndPrevious()
    {
        const QList<Bookmark> mine{{"a", 10000, "x", 0}, {"a", 60000, "y", 0}, {"a", 120000, "z", 0}};
        QCOMPARE(nextAfter(mine, 10000)->positionMs, 60000);  // not the one we are on
        QCOMPARE(nextAfter(mine, 9600)->positionMs, 60000);  // within the 0.5 s slack: skipped
        QCOMPARE(nextAfter(mine, 9400)->positionMs, 10000);
        QVERIFY(!nextAfter(mine, 120000));
        QCOMPARE(previousBefore(mine, 61000)->positionMs, 10000); // right after a jump: further back
        QCOMPARE(previousBefore(mine, 63000)->positionMs, 60000);
        QVERIFY(!previousBefore(mine, 11000));
    }
    void storeRoundTrip()
    {
        QTemporaryDir dir;
        BookmarkStore s(dir.filePath("bookmarks.json"));
        s.setAll({{"C:/v/a.mkv", 1234, "One", 5}, {"https://x/y.mp4", 99, "Two", 6}});
        QVERIFY(s.save());
        BookmarkStore r(dir.filePath("bookmarks.json"));
        r.load();
        QCOMPARE(r.all().size(), 2);
        QCOMPARE(r.all()[0], (Bookmark{"C:/v/a.mkv", 1234, "One", 5}));
    }
};

QTEST_GUILESS_MAIN(BookmarksTest)
#include "BookmarksTest.moc"
