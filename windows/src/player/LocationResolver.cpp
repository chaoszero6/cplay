#include "LocationResolver.h"

#include <QReadWriteLock>
#include <QUrl>

namespace {
QReadWriteLock g_lock;
LocationResolver::Fn g_resolve;
LocationResolver::NameFn g_name;
}

namespace LocationResolver {

void install(Fn resolve, NameFn displayName)
{
    QWriteLocker w(&g_lock);
    g_resolve = std::move(resolve);
    g_name = std::move(displayName);
}

ResolvedLocation resolve(const QString &location)
{
    {
        QReadLocker r(&g_lock);
        if (g_resolve) return g_resolve(location);
    }
    ResolvedLocation out;
    out.url = location.startsWith(QLatin1String("file://")) ? QUrl(location).toLocalFile() : location;
    return out;
}

QString displayName(const QString &location)
{
    QReadLocker r(&g_lock);
    return g_name ? g_name(location) : QString();
}

}
