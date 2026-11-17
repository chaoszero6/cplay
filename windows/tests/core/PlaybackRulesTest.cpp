// Ported from android/app/src/test/.../player/PlaybackRulesTest.kt (+ resume thresholds)
#include "core/PlaybackRules.h"
#include <QTest>
#include <limits>

class PlaybackRulesTest : public QObject
{
    Q_OBJECT
private slots:
    void speedClamp()
    {
        QCOMPARE(PlaybackRules::clampSpeed(0.1), 0.25);
        QCOMPARE(PlaybackRules::clampSpeed(0.25), 0.25);
        QCOMPARE(PlaybackRules::clampSpeed(1.0), 1.0);
        QCOMPARE(PlaybackRules::clampSpeed(4.0), 4.0);
        QCOMPARE(PlaybackRules::clampSpeed(10.0), 4.0);
        QCOMPARE(PlaybackRules::clampSpeed(std::numeric_limits<double>::quiet_NaN()), 1.0);
    }
    void speedSteps()
    {
        QCOMPARE(PlaybackRules::stepSpeed(1.0, true), 1.25);
        QCOMPARE(PlaybackRules::stepSpeed(1.0, false), 0.75);
        QCOMPARE(PlaybackRules::stepSpeed(4.0, true), 4.0);
        QCOMPARE(PlaybackRules::stepSpeed(0.25, false), 0.25);
        QCOMPARE(PlaybackRules::stepSpeed(1.1, true), 1.25);
        QCOMPARE(PlaybackRules::stepSpeed(3.9, true), 4.0);
    }
    void seekClamp()
    {
        QCOMPARE(PlaybackRules::clampSeek(-5000, 100000), 0);
        QCOMPARE(PlaybackRules::clampSeek(50000, 100000), 50000);
        QCOMPARE(PlaybackRules::clampSeek(150000, 100000), 100000);
        QCOMPARE(PlaybackRules::clampSeek(5000, 0), 0);
        QCOMPARE(PlaybackRules::clampSeek(5000, -1), 0);
        QCOMPARE(PlaybackRules::seekBy(50000, 10000, 100000), 60000);
        QCOMPARE(PlaybackRules::seekBy(50000, -10000, 100000), 40000);
        QCOMPARE(PlaybackRules::seekBy(2000, -10000, 100000), 0);
        QCOMPARE(PlaybackRules::seekBy(98000, 10000, 100000), 100000);
    }
    void resume()
    {
        QCOMPARE(PlaybackRules::resumeStartMs(60000, 600000), 60000);
        QCOMPARE(PlaybackRules::resumeStartMs(3000, 600000), 0);     // barely started
        QCOMPARE(PlaybackRules::resumeStartMs(597000, 600000), 0);   // at the very end
        QCOMPARE(PlaybackRules::resumeStartMs(580000, 600000), 0);   // past 95 %: finished
        QCOMPARE(PlaybackRules::resumeStartMs(60000, 0), 60000);     // unknown duration (stream)
        QVERIFY(PlaybackRules::isFinished(95000, 100000));
        QVERIFY(!PlaybackRules::isFinished(94000, 100000));
        QVERIFY(!PlaybackRules::isFinished(10, 0));
    }
    void abRepeat()
    {
        AbRepeat idle;
        QVERIFY(!idle.isActive());
        QVERIFY(!idle.isArmed());
        QVERIFY(!idle.loopTargetMs(1000));

        const AbRepeat armed = AbRepeat().press(10000);
        QCOMPARE(*armed.aMs, 10000);
        QVERIFY(!armed.bMs);
        QVERIFY(armed.isArmed());
        QCOMPARE(armed.label(), "A-");

        const AbRepeat active = armed.press(30000);
        QCOMPARE(*active.bMs, 30000);
        QVERIFY(active.isActive());
        QCOMPARE(active.label(), "A-B");
        QVERIFY(!active.loopTargetMs(20000));
        QCOMPARE(*active.loopTargetMs(30000), 10000);
        QCOMPARE(*active.loopTargetMs(31000), 10000);

        const AbRepeat cleared = active.press(50000);
        QVERIFY(!cleared.aMs && !cleared.bMs);

        const AbRepeat restarted = AbRepeat().press(30000).press(10000);
        QCOMPARE(*restarted.aMs, 10000);
        QVERIFY(restarted.isArmed());
        QVERIFY(AbRepeat().press(10000).press(10000).isArmed()); // B == A: no empty loop
    }
};

QTEST_GUILESS_MAIN(PlaybackRulesTest)
#include "PlaybackRulesTest.moc"
