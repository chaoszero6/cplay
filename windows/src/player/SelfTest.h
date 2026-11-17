#pragma once
#include <QString>

/**
 * Headless playback check with its own libmpv instance (vo=null, ao=null).
 * Used by `CPlay.exe --self-test <file>` (Wine smoke test, support) and
 * by the automated tests.
 */
namespace SelfTest {
struct Result {
    bool ok = false;
    QString audioCodec;   // e.g. "ac3", "dts", "aac"
    QString videoCodec;
    qint64 durationMs = 0;
    QString log;          // human readable steps
};
/** Plays [file]: start, seek near the end, reach the end, replay from 0. */
Result run(const QString &file, int timeoutMs = 20000);
}
