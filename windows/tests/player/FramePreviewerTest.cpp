// Seek-bar preview frames from a hidden libmpv instance (no window needed)
#include "player/FramePreviewer.h"
#include <QDir>
#include <QTest>

static int brightest(const QImage &img)
{
    int best = 0;
    for (int y = 0; y < img.height(); y += 4)
        for (int x = 0; x < img.width(); x += 4) {
            const QRgb p = img.pixel(x, y);
            best = std::max(best, (qRed(p) + qGreen(p) + qBlue(p)) / 3);
        }
    return best;
}

class FramePreviewerTest : public QObject
{
    Q_OBJECT
private slots:
    void framesFromAFile()
    {
        FramePreviewer p;
        const QString bars = QDir(QStringLiteral(TEST_MEDIA_DIR)).filePath("Bravo Bars.mp4");
        const QImage a = p.frameAt(bars, 5000, {224, 224});
        QVERIFY(!a.isNull());
        QCOMPARE(a.width(), 224);
        QCOMPARE(a.height(), 126);           // 16:9 kept
        QVERIFY(brightest(a) > 150);         // colour bars, not a black frame
        const QImage b = p.frameAt(bars, 20000, {160, 90});
        QCOMPARE(b.size(), QSize(160, 90));
        QVERIFY(brightest(b) > 150);
    }
    void switchesFiles()
    {
        FramePreviewer p;
        const QDir d(QStringLiteral(TEST_MEDIA_DIR));
        QVERIFY(!p.frameAt(d.filePath("Bravo Bars.mp4"), 3000, {160, 90}).isNull());
        const QImage c = p.frameAt(d.filePath("Charlie Fractal.mp4"), 10000, {160, 90});
        QVERIFY(!c.isNull());
        QVERIFY(brightest(c) > 100);
    }
    void missingFileGivesNoImage()
    {
        FramePreviewer p;
        QVERIFY(p.frameAt(QStringLiteral(TEST_MEDIA_DIR) + "/nope.mkv", 1000, {160, 90}, 3000).isNull());
    }
};

QTEST_GUILESS_MAIN(FramePreviewerTest)
#include "FramePreviewerTest.moc"
