// Ported from android/app/src/test/.../player/TimeFormatTest.kt
#include "core/TimeFormat.h"
#include <QTest>

class TimeFormatTest : public QObject
{
    Q_OBJECT
private slots:
    void zeroAndNegative()
    {
        QCOMPARE(TimeFormat::format(0), "0:00");
        QCOMPARE(TimeFormat::format(-1), "0:00");
        QCOMPARE(TimeFormat::format(-90000), "0:00");
    }
    void subMinuteAndSubHour()
    {
        QCOMPARE(TimeFormat::format(1000), "0:01");
        QCOMPARE(TimeFormat::format(59000), "0:59");
        QCOMPARE(TimeFormat::format(60000), "1:00");
        QCOMPARE(TimeFormat::format(754000), "12:34");
        QCOMPARE(TimeFormat::format(3599000), "59:59");
    }
    void hoursAndTruncation()
    {
        QCOMPARE(TimeFormat::format(3600000), "1:00:00");
        QCOMPARE(TimeFormat::format(3723000), "1:02:03");
        QCOMPARE(TimeFormat::format(4000000), "1:06:40");
        QCOMPARE(TimeFormat::format(1999), "0:01");
        QCOMPARE(TimeFormat::format(999), "0:00");
        QCOMPARE(TimeFormat::format(360000000), "100:00:00");
    }
    void clock()
    {
        QCOMPARE(TimeFormat::formatClock(754000), "00:12:34");
        QCOMPARE(TimeFormat::formatClock(4360000), "01:12:40");
        QCOMPARE(TimeFormat::formatClock(0), "00:00:00");
        QCOMPARE(TimeFormat::formatClock(-5000), "00:00:00");
        const QString before = TimeFormat::formatClock(3599000), after = TimeFormat::formatClock(3600000);
        QCOMPARE(before, "00:59:59");
        QCOMPARE(after, "01:00:00");
        QCOMPARE(before.size(), after.size());
    }
    void clockRemaining()
    {
        QCOMPARE(TimeFormat::formatClockRemaining(4360000, 0), "-01:12:40");
        QCOMPARE(TimeFormat::formatClockRemaining(100000, 40000), "-00:01:00");
        QCOMPARE(TimeFormat::formatClockRemaining(100000, 100000), "-00:00:00");
        QCOMPARE(TimeFormat::formatClockRemaining(100000, 150000), "-00:00:00");
        QCOMPARE(TimeFormat::formatClockRemaining(0, 0), "-00:00:00");
        const auto labels = TimeFormat::seekBarLabels(4360000, 754000);
        QCOMPARE(labels.first, "00:12:34");
        QCOMPARE(labels.second, "-01:00:06");
    }
    void remaining()
    {
        QCOMPARE(TimeFormat::formatRemaining(100000, 40000), "-1:00");
        QCOMPARE(TimeFormat::formatRemaining(3600000, 100000), "-58:20");
        QCOMPARE(TimeFormat::formatRemaining(7200000, 4200000), "-50:00");
        QCOMPARE(TimeFormat::formatRemaining(10800000, 1800000), "-2:30:00");
        QCOMPARE(TimeFormat::formatRemaining(100000, 100000), "-0:00");
        QCOMPARE(TimeFormat::formatRemaining(100000, 150000), "-0:00");
        QCOMPARE(TimeFormat::formatRemaining(0, 0), "-0:00");
        QCOMPARE(TimeFormat::formatRemaining(-5, 10), "-0:00");
    }
    void seekDelta()
    {
        QCOMPARE(TimeFormat::formatSeekDelta(23000, 4360000), "+0:23 (1:12:40)");
        QCOMPARE(TimeFormat::formatSeekDelta(-23000, 60000), "-0:23 (1:00)");
        QCOMPARE(TimeFormat::formatSeekDelta(0, 60000), "+0:00 (1:00)");
    }
    void speed()
    {
        QCOMPARE(TimeFormat::formatSpeed(1.0), "1x");
        QCOMPARE(TimeFormat::formatSpeed(2.0), "2x");
        QCOMPARE(TimeFormat::formatSpeed(4.0), "4x");
        QCOMPARE(TimeFormat::formatSpeed(1.25), "1.25x");
        QCOMPARE(TimeFormat::formatSpeed(1.5), "1.5x");
        QCOMPARE(TimeFormat::formatSpeed(0.25), "0.25x");
        QCOMPARE(TimeFormat::formatSpeed(0.75), "0.75x");
    }
    void progressAndShortLabel()
    {
        QCOMPARE(TimeFormat::progressFraction(50, 100), 0.5);
        QCOMPARE(TimeFormat::progressFraction(150, 100), 1.0);
        QCOMPARE(TimeFormat::progressFraction(-5, 100), 0.0);
        QCOMPARE(TimeFormat::progressFraction(5, 0), 0.0);
        QCOMPARE(TimeFormat::remainingShortLabel(100000, 100000), "Finished");
        QCOMPARE(TimeFormat::remainingShortLabel(100000, 70000), "<1 min left");
        QCOMPARE(TimeFormat::remainingShortLabel(600000, 0), "10 min left");
        QCOMPARE(TimeFormat::remainingShortLabel(5400000, 0), "1h 30m left");
    }
};

QTEST_GUILESS_MAIN(TimeFormatTest)
#include "TimeFormatTest.moc"
