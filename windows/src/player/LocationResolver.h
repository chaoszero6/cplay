#pragma once
#include <QString>
#include <QStringList>
#include <functional>

/**
 * Turns what CPlay stores (a path, a URL or a stable network key such as
 * "jellyfin://<server>/<item>" or "cplay-share://<server>/<path>") into what
 * mpv opens: a real URL plus HTTP headers. Keys never contain passwords or
 * tokens, so history, resume points and playlists stay credential-free.
 * Installed once by the network layer; thread-safe (preview threads use it).
 */
struct ResolvedLocation {
    QString url;
    QStringList httpHeaders;  // "Name: value"
    QStringList subtitles;    // extra subtitle URLs/paths to add after loading
    QString title;            // display title when the key has one
    QString error;            // non-empty: cannot be opened (e.g. server removed)
};

namespace LocationResolver {
using Fn = std::function<ResolvedLocation(const QString &location)>;
using NameFn = std::function<QString(const QString &location)>;
void install(Fn resolve, NameFn displayName);
/** Paths and ordinary URLs come back unchanged. */
ResolvedLocation resolve(const QString &location);
/** Title for a network key ("" when unknown or not a key). */
QString displayName(const QString &location);
}
