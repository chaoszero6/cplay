#include "core/Adjustments.h"
#include <QTest>

class AdjustmentsTest : public QObject
{
    Q_OBJECT
private slots:
    void delays()
    {
        QCOMPARE(Adjust::stepDelay(0, true), 100);
        QCOMPARE(Adjust::stepDelay(0, false), -100);
        QCOMPARE(Adjust::stepDelay(9950, true), 10000);
        QCOMPARE(Adjust::clampDelay(-20000), -10000);
        QCOMPARE(Adjust::delayLabel("Audio", 200), "Audio delay +0.2 s");
        QCOMPARE(Adjust::delayLabel("Subtitle", -1500), "Subtitle delay -1.5 s");
        QCOMPARE(Adjust::delayLabel("Audio", 0), "Audio delay 0 s");
        QCOMPARE(Adjust::delayLabel("Audio", 2000), "Audio delay +2 s");
    }
    void subtitles()
    {
        QCOMPARE(Adjust::stepSubScale(1.0, true), 1.1);
        QCOMPARE(Adjust::stepSubScale(0.3, false), 0.3);
        QCOMPARE(Adjust::stepSubScale(3.0, true), 3.0);
        QCOMPARE(Adjust::clampSubPos(200), 150);
        QCOMPARE(Adjust::clampSubPos(-5), 0);
    }
    void picture()
    {
        QCOMPARE(Adjust::clampPicture(150), 100);
        QCOMPARE(Adjust::pictureLabel("Brightness", 10), "Brightness +10");
        QCOMPARE(Adjust::pictureLabel("Contrast", -20), "Contrast -20");
        QCOMPARE(Adjust::pictureLabel("Hue", 0), "Hue 0");
    }
    void zoomAndRotation()
    {
        QCOMPARE(Adjust::stepZoom(0, true), 0.1);
        QCOMPARE(Adjust::stepZoom(2.0, true), 2.0);
        QCOMPARE(Adjust::zoomPercent(0), 100);
        QCOMPARE(Adjust::zoomPercent(1), 200);
        QCOMPARE(Adjust::zoomPercent(-1), 50);
        QCOMPARE(Adjust::nextRotation(0), 90);
        QCOMPARE(Adjust::nextRotation(270), 0);
        QCOMPARE(Adjust::aspectPresets().first().second, "no");
    }
};

QTEST_GUILESS_MAIN(AdjustmentsTest)
#include "AdjustmentsTest.moc"
