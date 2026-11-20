#pragma once
#include "LocalMediaServer.h"
#include "core/NetRules.h"

#include <QString>

/** How to reach a file share. The password only lives in memory. */
struct ShareLogin {
    QString type;      // "smb", "nfs", "ftp"
    QString host;
    int port = 0;
    QString root;      // SMB share / NFS export ("/srv/media"), may be empty
    QString user;
    QString password;
};

/**
 * SMB (libsmb2), NFS (libnfs) and FTP (FtpClient) as one blocking file system,
 * for worker threads. Paths are "/" separated and relative to the root; with
 * no share/export configured the first level lists the server's shares/exports
 * and the first path segment (SMB) or the longest matching export (NFS) picks it.
 */
namespace ShareFs {
/** Folder listing ([path] "" = top). Sets [error] on failure. */
QList<RemoteEntry> list(const ShareLogin &login, const QString &path, QString *error);
/** Reader for a file, opened per HTTP request by LocalMediaServer. */
SourceFactory sourceFor(const ShareLogin &login, const QString &path);
/** Checks the server and login (lists the top folder). */
bool test(const ShareLogin &login, QString *error);
}
