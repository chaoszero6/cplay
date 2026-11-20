#include "ShareFs.h"
#include "FtpClient.h"
#include "core/LibraryRules.h"

#include <QMutex>
#include <QStringList>
#include <algorithm>
#include <functional>
#include <list>
#include <fcntl.h>

extern "C" {
#include <smb2/smb2.h>
#include <smb2/libsmb2.h>
#include <smb2/libsmb2-raw.h>
#include <smb2/libsmb2-dcerpc-srvsvc.h>
#include <nfsc/libnfs.h>
#include <nfsc/libnfs-raw-mount.h>
}

#ifdef Q_OS_WIN
#include <winsock2.h>
#else
#include <poll.h>
#include <unistd.h>
#endif

namespace {

void initSockets()
{
#ifdef Q_OS_WIN
    static bool done = [] { WSADATA d; return WSAStartup(MAKEWORD(2, 2), &d) == 0; }();
    Q_UNUSED(done);
#endif
}

QStringList segs(const QString &p) { return QString(p).replace(u'\\', u'/').split(u'/', Qt::SkipEmptyParts); }

void sortEntries(QList<RemoteEntry> &l)
{
    std::stable_sort(l.begin(), l.end(), [](const RemoteEntry &a, const RemoteEntry &b) {
        if (a.isFolder != b.isFolder) return a.isFolder;
        return LibraryRules::naturalLess(a.name, b.name);
    });
}

RemoteEntry entryFor(const QString &folder, const QString &name, bool dir, qint64 size)
{
    RemoteEntry e;
    e.id = NetRules::joinPath(folder, name);
    e.name = name;
    e.isFolder = dir;
    e.sizeBytes = dir ? 0 : size;
    e.kind = dir ? QStringLiteral("folder") : QStringLiteral("video");
    if (!dir) e.subtitle = name.section(u'.', -1).toUpper();
    return e;
}

bool keep(const QString &name, bool dir)
{
    if (name.startsWith(u'.') || name.startsWith(u'$') || name.startsWith(QLatin1String("@eaDir"))) return false;
    return dir || NetRules::isPlayableName(name);
}

// ---- SMB ---------------------------------------------------------------------

// libsmb2's blocking calls never time out on a silent server, so every call
// runs through the async API with our own deadline
struct SmbWait {
    bool done = false;
    int status = 0;
    void *data = nullptr;
};

void smbWaitCb(smb2_context *, int status, void *data, void *priv)
{
    auto *w = static_cast<SmbWait *>(priv);
    w->done = true;
    w->status = status;
    w->data = data;
}

bool smbPump(smb2_context *ctx, const SmbWait &w, int timeoutMs)
{
    for (int waited = 0; !w.done && waited < timeoutMs; waited += 100) {
#ifdef Q_OS_WIN
        WSAPOLLFD pfd{};
        pfd.fd = smb2_get_fd(ctx);
        pfd.events = short(smb2_which_events(ctx));
        if (WSAPoll(&pfd, 1, 100) < 0) return false;
#else
        pollfd pfd{};
        pfd.fd = smb2_get_fd(ctx);
        pfd.events = short(smb2_which_events(ctx));
        if (poll(&pfd, 1, 100) < 0) return false;
#endif
        if (pfd.revents == 0) continue;
        if (smb2_service(ctx, pfd.revents) < 0) return false;
    }
    return w.done;
}

struct Smb {
    smb2_context *ctx = nullptr;
    QString error;
    std::list<SmbWait> waits; // outlive ctx: destroying it may still call back
    bool abandoned = false;   // a call timed out: libsmb2 can't be torn down safely mid-call
    ~Smb()
    {
        if (!ctx) return;
        if (!abandoned) { smb2_destroy_context(ctx); return; }
        // Leak the context (rare: a server that went silent) but drop the connection
#ifdef Q_OS_WIN
        closesocket(smb2_get_fd(ctx));
#else
        ::close(smb2_get_fd(ctx));
#endif
        new std::list<SmbWait>(std::move(waits)); // callbacks may still point here
    }
    SmbWait &newWait() { waits.emplace_back(); return waits.back(); }
    /** Runs one async call; false on error or timeout. */
    bool run(const std::function<int(SmbWait &)> &start, int timeoutMs, SmbWait **out = nullptr)
    {
        SmbWait &w = newWait();
        if (start(w) != 0) return false;
        if (!smbPump(ctx, w, timeoutMs)) {
            abandoned = true;
            error = QStringLiteral("The server stopped answering");
            return false;
        }
        if (out) *out = &w;
        return w.status >= 0;
    }
    bool connect(const ShareLogin &l, const QString &share)
    {
        initSockets();
        ctx = smb2_init_context();
        if (!ctx) { error = QStringLiteral("Out of memory"); return false; }
        QString user = l.user, domain;
        if (user.contains(u'\\')) { domain = user.section(u'\\', 0, 0); user = user.section(u'\\', 1); }
        else if (user.contains(u'@') && !user.contains(u'.')) { domain = user.section(u'@', 1); user = user.section(u'@', 0, 0); }
        smb2_set_security_mode(ctx, SMB2_NEGOTIATE_SIGNING_ENABLED);
        // No libsmb2 timeout: its timeout path crashes mid-login; run() has its own deadline
        const QByteArray u = user.toUtf8(), p = l.password.toUtf8(), d = domain.toUtf8();
        if (!user.isEmpty()) smb2_set_user(ctx, u.constData());
        smb2_set_password(ctx, p.constData());
        if (!domain.isEmpty()) smb2_set_domain(ctx, d.constData());
        const QByteArray server = (l.port > 0 && l.port != 445 ? QStringLiteral("%1:%2").arg(l.host).arg(l.port) : l.host).toUtf8();
        const QByteArray s = share.toUtf8();
        const QByteArray who = user.isEmpty() ? QByteArray("guest") : u;
        SmbWait *w = nullptr;
        const bool ok = run([&](SmbWait &wait) {
            return smb2_connect_share_async(ctx, server.constData(), s.constData(), who.constData(), smbWaitCb, &wait);
        }, 15000, &w);
        if (ok) return true;
        const QString e = QString::fromUtf8(smb2_get_error(ctx));
        if (!w && error.isEmpty()) error = QStringLiteral("Could not connect to %1: %2").arg(l.host, e);
        if (w || error == QLatin1String("The server stopped answering")) {
            if (e.contains(QLatin1String("LOGON_FAILURE"), Qt::CaseInsensitive) || e.contains(QLatin1String("ACCESS_DENIED"), Qt::CaseInsensitive)
                || e.contains(QLatin1String("authenticat"), Qt::CaseInsensitive) || error == QLatin1String("The server stopped answering"))
                error = QStringLiteral("Wrong user name or password");
            else if (e.contains(QLatin1String("BAD_NETWORK_NAME"), Qt::CaseInsensitive))
                error = QStringLiteral("There is no share called \"%1\" on this server").arg(share);
            else
                error = QStringLiteral("Could not connect to %1: %2").arg(l.host, e.isEmpty() ? QStringLiteral("no answer") : e);
        }
        return false;
    }
};

struct ShareEnum { bool done = false; int status = 0; QStringList names; };

void shareEnumCb(smb2_context *smb2, int status, void *data, void *priv)
{
    auto *r = static_cast<ShareEnum *>(priv);
    r->done = true;
    r->status = status;
    if (status) return;
    auto *rep = static_cast<srvsvc_NetrShareEnum_rep *>(data);
    for (uint32_t i = 0; i < rep->ses.ShareInfo.Level1.EntriesRead; ++i) {
        const auto &info = rep->ses.ShareInfo.Level1.Buffer->share_info_1[i];
        if ((info.type & 3) != SHARE_TYPE_DISKTREE || (info.type & SHARE_TYPE_HIDDEN)) continue;
        const QString name = QString::fromUtf8(info.netname.utf8);
        if (!name.endsWith(u'$')) r->names << name;
    }
    smb2_free_data(smb2, rep);
}

QList<RemoteEntry> smbShares(const ShareLogin &l, QString *error)
{
    Smb smb;
    QList<RemoteEntry> out;
    if (!smb.connect(l, QStringLiteral("IPC$"))) { *error = smb.error; return out; }
    ShareEnum result;
    if (smb2_share_enum_async(smb.ctx, SHARE_INFO_1, shareEnumCb, &result) != 0) {
        *error = QStringLiteral("The server would not list its shares. Add the share to the address (\\\\server\\share).");
        return out;
    }
    for (int waited = 0; !result.done && waited < 15000; waited += 200) {
#ifdef Q_OS_WIN
        WSAPOLLFD pfd{};
        pfd.fd = smb2_get_fd(smb.ctx);
        pfd.events = short(smb2_which_events(smb.ctx));
        if (WSAPoll(&pfd, 1, 200) < 0) break;
#else
        pollfd pfd{};
        pfd.fd = smb2_get_fd(smb.ctx);
        pfd.events = short(smb2_which_events(smb.ctx));
        if (poll(&pfd, 1, 200) < 0) break;
#endif
        if (pfd.revents == 0) continue;
        if (smb2_service(smb.ctx, pfd.revents) < 0) break;
    }
    if (!result.done || result.status != 0) {
        *error = QStringLiteral("The server would not list its shares. Add the share to the address (\\\\server\\share).");
        return out;
    }
    for (const auto &n : std::as_const(result.names)) {
        RemoteEntry e = entryFor({}, n, true, 0);
        e.kind = QStringLiteral("share");
        out << e;
    }
    sortEntries(out);
    return out;
}

// SMB path split: (share, path inside the share)
QPair<QString, QString> smbTarget(const ShareLogin &l, const QString &path)
{
    QStringList p = segs(l.root) + segs(path);
    if (p.isEmpty()) return {};
    const QString share = p.takeFirst();
    return {share, p.join(u'/')};
}

QList<RemoteEntry> smbList(const ShareLogin &l, const QString &path, QString *error)
{
    const auto [share, inner] = smbTarget(l, path);
    if (share.isEmpty()) return smbShares(l, error);
    Smb smb;
    QList<RemoteEntry> out;
    if (!smb.connect(l, share)) { *error = smb.error; return out; }
    const QByteArray dirPath = inner.toUtf8();
    // libsmb2 quirk: the directory handle takes ownership of opendir's callback data
    // and free()s it in smb2_closedir, so that one must come from malloc
    auto *w = static_cast<SmbWait *>(calloc(1, sizeof(SmbWait)));
    smb2dir *dir = nullptr;
    if (smb2_opendir_async(smb.ctx, dirPath.constData(), smbWaitCb, w) == 0) {
        if (!smbPump(smb.ctx, *w, 20000)) { smb.abandoned = true; *error = QStringLiteral("The server stopped answering"); return out; }
        if (w->status >= 0) dir = static_cast<smb2dir *>(w->data);
    } else {
        free(w);
    }
    if (!dir) { *error = QStringLiteral("Could not open the folder: ") + QString::fromUtf8(smb2_get_error(smb.ctx)); return out; }
    while (smb2dirent *ent = smb2_readdir(smb.ctx, dir)) {
        const QString name = QString::fromUtf8(ent->name);
        if (name == QLatin1String(".") || name == QLatin1String("..")) continue;
        const bool isDir = ent->st.smb2_type == SMB2_TYPE_DIRECTORY;
        if (keep(name, isDir)) out << entryFor(path, name, isDir, qint64(ent->st.smb2_size));
    }
    smb2_closedir(smb.ctx, dir);
    sortEntries(out);
    return out;
}

class SmbSource : public ByteSource
{
public:
    SmbSource(ShareLogin l, QString path) : m_login(std::move(l)), m_path(std::move(path)) {}
    ~SmbSource() override
    {
        if (m_fh && m_smb.ctx) m_smb.run([&](SmbWait &wait) { return smb2_close_async(m_smb.ctx, m_fh, smbWaitCb, &wait); }, 3000);
    }
    bool open(QString *error) override
    {
        const auto [share, inner] = smbTarget(m_login, m_path);
        if (!m_smb.connect(m_login, share)) { if (error) *error = m_smb.error; return false; }
        const QByteArray p = inner.toUtf8();
        SmbWait *w = nullptr;
        if (m_smb.run([&](SmbWait &wait) { return smb2_open_async(m_smb.ctx, p.constData(), O_RDONLY, smbWaitCb, &wait); }, 15000, &w))
            m_fh = static_cast<smb2fh *>(w->data);
        if (!m_fh) { if (error) *error = QString::fromUtf8(smb2_get_error(m_smb.ctx)); return false; }
        smb2_stat_64 st{};
        if (m_smb.run([&](SmbWait &wait) { return smb2_fstat_async(m_smb.ctx, m_fh, &st, smbWaitCb, &wait); }, 15000))
            m_size = qint64(st.smb2_size);
        m_maxRead = std::max<uint32_t>(64 * 1024, std::min<uint32_t>(smb2_get_max_read_size(m_smb.ctx), 1024 * 1024));
        return true;
    }
    qint64 size() const override { return m_size; }
    qint64 read(qint64 offset, char *buf, qint64 max) override
    {
        const uint32_t n = uint32_t(std::min<qint64>(max, m_maxRead));
        SmbWait *w = nullptr;
        if (!m_smb.run([&](SmbWait &wait) {
                return smb2_pread_async(m_smb.ctx, m_fh, reinterpret_cast<uint8_t *>(buf), n, uint64_t(offset), smbWaitCb, &wait);
            }, 30000, &w))
            return -1;
        return w->status;
    }

private:
    ShareLogin m_login;
    QString m_path;
    Smb m_smb;
    smb2fh *m_fh = nullptr;
    qint64 m_size = 0;
    uint32_t m_maxRead = 64 * 1024;
};

// ---- NFS ---------------------------------------------------------------------

QMutex g_exportsLock;
QHash<QString, QStringList> g_exports;

QStringList nfsExports(const QString &host, QString *error)
{
    {
        QMutexLocker l(&g_exportsLock);
        if (g_exports.contains(host)) return g_exports.value(host);
    }
    initSockets();
    QStringList out;
    exportnode *list = mount_getexports_timeout(host.toUtf8().constData(), 10);
    if (!list) { if (error) *error = QStringLiteral("Could not reach the NFS server %1").arg(host); return out; }
    for (exportnode *e = list; e; e = e->ex_next) out << QString::fromUtf8(e->ex_dir);
    mount_free_export_list(list);
    QMutexLocker l(&g_exportsLock);
    g_exports.insert(host, out);
    return out;
}

// NFS: (export, path inside it) for an absolute browse path
QPair<QString, QString> nfsTarget(const ShareLogin &l, const QString &path, QString *error)
{
    if (!l.root.isEmpty()) return {l.root, segs(path).join(u'/')};
    const QString abs = u'/' + segs(path).join(u'/');
    QString best;
    for (const QString &ex : nfsExports(l.host, error))
        if ((abs == ex || abs.startsWith(ex.endsWith(u'/') ? ex : ex + u'/')) && ex.size() > best.size()) best = ex;
    if (best.isEmpty()) return {};
    return {best, abs.mid(best.size()).split(u'/', Qt::SkipEmptyParts).join(u'/')};
}

struct Nfs {
    nfs_context *ctx = nullptr;
    QString error;
    ~Nfs() { if (ctx) nfs_destroy_context(ctx); }
    bool mount(const ShareLogin &l, const QString &exportPath)
    {
        initSockets();
        ctx = nfs_init_context();
        if (!ctx) { error = QStringLiteral("Out of memory"); return false; }
        nfs_set_timeout(ctx, 15000);
        if (l.port > 0) nfs_set_nfsport(ctx, l.port);
        if (nfs_mount(ctx, l.host.toUtf8().constData(), exportPath.toUtf8().constData()) != 0) {
            error = QStringLiteral("Could not open the NFS export %1: %2").arg(exportPath, QString::fromUtf8(nfs_get_error(ctx)));
            return false;
        }
        return true;
    }
};

QList<RemoteEntry> nfsList(const ShareLogin &l, const QString &path, QString *error)
{
    QList<RemoteEntry> out;
    if (l.root.isEmpty() && segs(path).isEmpty()) {
        // Top: the server's exports
        for (const QString &ex : nfsExports(l.host, error)) {
            RemoteEntry e;
            e.id = ex;
            e.name = ex;
            e.isFolder = true;
            e.kind = QStringLiteral("share");
            out << e;
        }
        return out;
    }
    const auto [ex, inner] = nfsTarget(l, path, error);
    if (ex.isEmpty()) { if (error->isEmpty()) *error = QStringLiteral("That folder is not on an NFS export"); return out; }
    Nfs nfs;
    if (!nfs.mount(l, ex)) { *error = nfs.error; return out; }
    nfsdir *dir = nullptr;
    if (nfs_opendir(nfs.ctx, (u'/' + inner).toUtf8().constData(), &dir) != 0) {
        *error = QStringLiteral("Could not open the folder: ") + QString::fromUtf8(nfs_get_error(nfs.ctx));
        return out;
    }
    const QString base = l.root.isEmpty() ? u'/' + segs(path).join(u'/') : segs(path).join(u'/');
    while (nfsdirent *ent = nfs_readdir(nfs.ctx, dir)) {
        const QString name = QString::fromUtf8(ent->name);
        if (name == QLatin1String(".") || name == QLatin1String("..")) continue;
        const bool isDir = ent->type == 2 /* NF3DIR */ || (ent->mode & 0170000) == 0040000;
        if (!keep(name, isDir)) continue;
        RemoteEntry e = entryFor(base, name, isDir, qint64(ent->size));
        if (l.root.isEmpty()) e.id = base.endsWith(u'/') ? base + name : base + u'/' + name;
        out << e;
    }
    nfs_closedir(nfs.ctx, dir);
    sortEntries(out);
    return out;
}

class NfsSource : public ByteSource
{
public:
    NfsSource(ShareLogin l, QString path) : m_login(std::move(l)), m_path(std::move(path)) {}
    ~NfsSource() override { if (m_fh && m_nfs.ctx) nfs_close(m_nfs.ctx, m_fh); }
    bool open(QString *error) override
    {
        QString err;
        const auto [ex, inner] = nfsTarget(m_login, m_path, &err);
        if (ex.isEmpty()) { if (error) *error = err; return false; }
        if (!m_nfs.mount(m_login, ex)) { if (error) *error = m_nfs.error; return false; }
        if (nfs_open(m_nfs.ctx, (u'/' + inner).toUtf8().constData(), O_RDONLY, &m_fh) != 0) {
            if (error) *error = QString::fromUtf8(nfs_get_error(m_nfs.ctx));
            return false;
        }
        nfs_stat_64 st{};
        if (nfs_fstat64(m_nfs.ctx, m_fh, &st) == 0) m_size = qint64(st.nfs_size);
        return true;
    }
    qint64 size() const override { return m_size; }
    qint64 read(qint64 offset, char *buf, qint64 max) override
    {
        const int r = nfs_pread(m_nfs.ctx, m_fh, buf, size_t(std::min<qint64>(max, 512 * 1024)), uint64_t(offset));
        return r < 0 ? -1 : r;
    }

private:
    ShareLogin m_login;
    QString m_path;
    Nfs m_nfs;
    nfsfh *m_fh = nullptr;
    qint64 m_size = 0;
};

// ---- FTP ---------------------------------------------------------------------

QString ftpPath(const ShareLogin &l, const QString &path)
{
    return u'/' + (segs(l.root) + segs(path)).join(u'/');
}

class FtpSource : public ByteSource
{
public:
    FtpSource(ShareLogin l, QString path) : m_login(std::move(l)), m_path(ftpPath(m_login, path)) {}
    bool open(QString *error) override
    {
        if (!m_ftp.connectAndLogin(m_login.host, m_login.port, m_login.user, m_login.password)) { if (error) *error = m_ftp.error(); return false; }
        m_size = m_ftp.size(m_path);
        if (m_size < 0) { if (error) *error = QStringLiteral("File not found"); return false; }
        return true;
    }
    qint64 size() const override { return m_size; }
    qint64 read(qint64 offset, char *buf, qint64 max) override
    {
        if (!m_reading || offset != m_pos) {
            if (!m_ftp.startRead(m_path, offset)) return -1;
            m_reading = true;
            m_pos = offset;
        }
        const qint64 n = m_ftp.read(buf, max);
        if (n > 0) m_pos += n;
        return n;
    }

private:
    ShareLogin m_login;
    QString m_path;
    FtpClient m_ftp;
    qint64 m_size = 0;
    qint64 m_pos = 0;
    bool m_reading = false;
};

QList<RemoteEntry> ftpList(const ShareLogin &l, const QString &path, QString *error)
{
    QList<RemoteEntry> out;
    FtpClient ftp;
    if (!ftp.connectAndLogin(l.host, l.port, l.user, l.password)) { *error = ftp.error(); return out; }
    const auto entries = ftp.list(ftpPath(l, path));
    if (entries.isEmpty() && !ftp.error().isEmpty()) { *error = ftp.error(); return out; }
    for (const auto &e : entries)
        if (keep(e.name, e.isDir)) out << entryFor(path, e.name, e.isDir, e.size);
    sortEntries(out);
    return out;
}

} // namespace

namespace ShareFs {

QList<RemoteEntry> list(const ShareLogin &login, const QString &path, QString *error)
{
    QString err;
    QList<RemoteEntry> out;
    if (login.type == QLatin1String("smb")) out = smbList(login, path, &err);
    else if (login.type == QLatin1String("nfs")) out = nfsList(login, path, &err);
    else if (login.type == QLatin1String("ftp")) out = ftpList(login, path, &err);
    else err = QStringLiteral("Unknown share type");
    if (error) *error = err;
    return out;
}

SourceFactory sourceFor(const ShareLogin &login, const QString &path)
{
    return [login, path]() -> std::unique_ptr<ByteSource> {
        if (login.type == QLatin1String("smb")) return std::make_unique<SmbSource>(login, path);
        if (login.type == QLatin1String("nfs")) return std::make_unique<NfsSource>(login, path);
        if (login.type == QLatin1String("ftp")) return std::make_unique<FtpSource>(login, path);
        return {};
    };
}

bool test(const ShareLogin &login, QString *error)
{
    QString err;
    list(login, {}, &err);
    if (error) *error = err;
    return err.isEmpty();
}

}
