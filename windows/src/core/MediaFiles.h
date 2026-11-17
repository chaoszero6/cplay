#pragma once
#include <QString>
#include <QStringList>

/** Which files CPlay opens, and how they are named on screen. */
namespace MediaFiles {
const QStringList &videoExtensions();
const QStringList &audioExtensions();
const QStringList &subtitleExtensions();
bool isVideo(const QString &path);
bool isMedia(const QString &path);
bool isSubtitle(const QString &path);
bool isUrl(const QString &location);
/** "Alpha Clip.mp4" from a path or URL (query dropped, %20 decoded). */
QString displayName(const QString &location);
/** Subtitle files next to [videoPath] whose name starts with the video's base name. */
QStringList siblingSubtitles(const QString &videoPath);
/** Filters for the Open dialog: "Videos (*.mp4 *.mkv ...)". */
QStringList openDialogFilters();
}
