// Plays every bundled test clip through libmpv without a window: start, seek
// to the end, reach the end, replay (the Android black-screen scenario), and
// checks that AC3 / E-AC3 / DTS / TrueHD audio decodes with no extra codecs.
#include "player/SelfTest.h"
#include <QDir>
#include <QFileInfo>
#include <QTest>

class MpvPlaybackTest : public QObject
{
    Q_OBJECT
private slots:
    void clips_data()
    {
        QTest::addColumn<QString>("file");
        QTest::addColumn<QString>("audio");
        const QDir dir(QStringLiteral(TEST_MEDIA_DIR));
        QTest::newRow("mp4 h264/aac") << dir.filePath("Alpha Clip.mp4") << "aac";
        QTest::newRow("mkv tracks") << dir.filePath("Delta Tracks.mkv") << "";
        QTest::newRow("ac3") << dir.filePath("Foxtrot AC3.mkv") << "ac3";
        QTest::newRow("eac3") << dir.filePath("Golf EAC3.mkv") << "eac3";
        QTest::newRow("dts") << dir.filePath("Hotel DTS.mkv") << "dts";
        QTest::newRow("truehd") << dir.filePath("India TrueHD.mkv") << "truehd";
    }
    void clips()
    {
        QFETCH(QString, file);
        QFETCH(QString, audio);
        QVERIFY2(QFileInfo::exists(file), qPrintable(file));
        const SelfTest::Result r = SelfTest::run(file);
        qInfo().noquote() << r.log;
        QVERIFY2(r.ok, qPrintable(r.log));
        QVERIFY(r.durationMs > 1000);
        if (!audio.isEmpty()) QCOMPARE(r.audioCodec, audio);
    }
    void missingFileFails()
    {
        const SelfTest::Result r = SelfTest::run(QStringLiteral(TEST_MEDIA_DIR) + "/does-not-exist.mp4", 5000);
        QVERIFY(!r.ok);
    }
};

QTEST_GUILESS_MAIN(MpvPlaybackTest)
#include "MpvPlaybackTest.moc"
