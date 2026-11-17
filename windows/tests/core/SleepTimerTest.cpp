// Ported from android/app/src/test/.../player/SleepTimerTest.kt
#include "core/SleepTimer.h"
#include <QTest>

class SleepTimerTest : public QObject
{
    Q_OBJECT
private slots:
    void labels()
    {
        QCOMPARE(SleepTimer::label(std::nullopt), "Sleep");
        QCOMPARE(SleepTimer::label(0), "Sleep");
        QCOMPARE(SleepTimer::label(15 * 60000), "Sleep 15m");
        QCOMPARE(SleepTimer::label(14 * 60000 + 1), "Sleep 15m");
        QCOMPARE(SleepTimer::label(30000), "Sleep 1m");
    }
    void minutes()
    {
        QCOMPARE(SleepTimer::minutesToMs(5), 300000);
        QCOMPARE(SleepTimer::minutesToMs(-3), 0);
        QCOMPARE(SleepTimer::remainingMinutes(0), 0);
        QCOMPARE(SleepTimer::remainingMinutes(60001), 2);
        QCOMPARE(SleepTimer::presetMinutes().first(), 5);
    }
    void ticking()
    {
        QCOMPARE(*SleepTimer::tick(60000, 1000), 59000);
        QVERIFY(!SleepTimer::tick(1000, 1000));
        QVERIFY(!SleepTimer::tick(std::nullopt, 1000));
        QCOMPARE(*SleepTimer::tick(60000, -50), 60000);
        QVERIFY(SleepTimer::shouldPause(1000, 1000));
        QVERIFY(SleepTimer::shouldPause(1000, 5000));
        QVERIFY(!SleepTimer::shouldPause(1000, 999));
        QVERIFY(!SleepTimer::shouldPause(std::nullopt, 5000));
    }
};

QTEST_GUILESS_MAIN(SleepTimerTest)
#include "SleepTimerTest.moc"
