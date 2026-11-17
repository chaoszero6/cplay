#include "core/ResumeStore.h"
#include <QTemporaryDir>
#include <QTest>

class ResumeStoreTest : public QObject
{
    Q_OBJECT
private slots:
    void savesAndReloads()
    {
        QTemporaryDir dir;
        const QString file = dir.filePath("sub/history.json");
        {
            ResumeStore s(file);
            s.update("C:/Videos/a.mkv", "A", 60000, 600000, false, 1000);
            s.update("https://x/b.mp4", "B", 120000, 0, false, 2000);
            QVERIFY(s.save());
        }
        ResumeStore s(file);
        s.load();
        QCOMPARE(s.startPositionFor("C:/Videos/a.mkv"), 60000);
        QCOMPARE(s.startPositionFor("https://x/b.mp4"), 120000);
        QCOMPARE(s.startPositionFor("missing"), 0);
        const auto recent = s.recent(10);
        QCOMPARE(recent.size(), 2);
        QCOMPARE(recent[0].key, "https://x/b.mp4"); // newest first
    }
    void finishingClearsResumeAndCountsAPlay()
    {
        QTemporaryDir dir;
        ResumeStore s(dir.filePath("h.json"));
        s.update("a", "A", 300000, 600000, false, 1);
        s.update("a", "", 0, 600000, true, 2);
        QCOMPARE(s.startPositionFor("a"), 0);
        QCOMPARE(s.entry("a")->playCount, 1);
        QCOMPARE(s.entry("a")->title, "A"); // empty title keeps the old one
        QCOMPARE(s.entry("a")->positionMs, 600000);
    }
    void keepsOnlyNewestEntries()
    {
        QTemporaryDir dir;
        ResumeStore s(dir.filePath("h.json"));
        for (int i = 0; i < ResumeStore::MaxEntries + 20; ++i)
            s.update(QString::number(i), "", 60000, 600000, false, i);
        QCOMPARE(s.recent(10000).size(), ResumeStore::MaxEntries);
        QVERIFY(!s.entry("0"));
        QVERIFY(s.entry(QString::number(ResumeStore::MaxEntries + 19)));
    }
    void corruptFileIsIgnored()
    {
        QTemporaryDir dir;
        QFile f(dir.filePath("h.json"));
        QVERIFY(f.open(QIODevice::WriteOnly));
        f.write("{not json");
        f.close();
        ResumeStore s(dir.filePath("h.json"));
        s.load();
        QCOMPARE(s.recent(10).size(), 0);
    }
};

QTEST_GUILESS_MAIN(ResumeStoreTest)
#include "ResumeStoreTest.moc"
