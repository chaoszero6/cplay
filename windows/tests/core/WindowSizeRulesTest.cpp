#include "core/WindowSizeRules.h"
#include <QTest>

using namespace WindowSizeRules;
static const QSize work(1920, 1040), minimum(480, 300), chrome(2, 106);

class WindowSizeRulesTest : public QObject
{
    Q_OBJECT
private slots:
    void keepNeverResizes()
    {
        // Whatever the video, the window stays as the user left it
        QVERIFY(!sizeOnPlay(Mode::Keep, {900, 500}, {1280, 720}, {3840, 2160}, 1.0, chrome, work, minimum).isValid());
        QVERIFY(!sizeOnPlay(Mode::Keep, {900, 500}, {}, {720, 1280}, 1.0, chrome, work, minimum).isValid());
    }
    void customIsTheSameForEveryVideo()
    {
        QCOMPARE(sizeOnPlay(Mode::Custom, {900, 500}, {1280, 760}, {3840, 2160}, 1, chrome, work, minimum), QSize(1280, 760));
        QCOMPARE(sizeOnPlay(Mode::Custom, {900, 500}, {1280, 760}, {640, 360}, 1, chrome, work, minimum), QSize(1280, 760));
        QCOMPARE(sizeOnPlay(Mode::Custom, {900, 500}, {1280, 760}, {720, 1280}, 1, chrome, work, minimum), QSize(1280, 760));
        // Never bigger than the screen, never smaller than the minimum
        QCOMPARE(sizeOnPlay(Mode::Custom, {900, 500}, {5000, 3000}, {}, 1, chrome, work, minimum), QSize(1920, 1040));
        QCOMPARE(sizeOnPlay(Mode::Custom, {900, 500}, {200, 150}, {}, 1, chrome, work, minimum), QSize(480, 300));
        QVERIFY(!sizeOnPlay(Mode::Custom, {900, 500}, {}, {640, 360}, 1, chrome, work, minimum).isValid());
    }
    void fitFollowsTheVideo()
    {
        QCOMPARE(sizeOnPlay(Mode::Fit, {}, {}, {1280, 720}, 1.0, chrome, work, minimum), QSize(1282, 826));
        QCOMPARE(sizeOnPlay(Mode::Fit, {}, {}, {1280, 720}, 0.5, chrome, work, minimum), QSize(642, 466));
        // 4K on a 1080p screen: shrunk to fit, same shape
        const QSize big = sizeOnPlay(Mode::Fit, {}, {}, {3840, 2160}, 1.0, chrome, work, minimum);
        QVERIFY(big.width() <= 1920 && big.height() <= 1040);
        QVERIFY(qAbs(double(big.width() - 2) / (big.height() - 106) - 16.0 / 9.0) < 0.01);
        QVERIFY(!sizeOnPlay(Mode::Fit, {}, {}, {}, 1.0, chrome, work, minimum).isValid());
    }
    void modeNames()
    {
        QCOMPARE(modeFromString("custom"), Mode::Custom);
        QCOMPARE(modeFromString("fit"), Mode::Fit);
        QCOMPARE(modeFromString("anything"), Mode::Keep);
        QCOMPARE(modeToString(Mode::Custom), "custom");
    }
    void parsing()
    {
        QCOMPARE(parseSize("1280x720"), QSize(1280, 720));
        QCOMPARE(parseSize(" 1600 X 900 "), QSize(1600, 900));
        QCOMPARE(parseSize("800*450"), QSize(800, 450));
        QVERIFY(!parseSize("big").isValid());
        QVERIFY(!parseSize("50x50").isValid());
        QVERIFY(!parseSize("1280").isValid());
    }
};

QTEST_GUILESS_MAIN(WindowSizeRulesTest)
#include "WindowSizeRulesTest.moc"
