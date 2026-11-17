package com.chaoswares.chaosplay.network

import android.content.Context
import com.emc.ecs.nfsclient.nfs.io.Nfs3File
import com.emc.ecs.nfsclient.nfs.io.NfsFileInputStream
import com.emc.ecs.nfsclient.nfs.nfs3.Nfs3
import com.emc.ecs.nfsclient.rpc.CredentialUnix
import com.hierynomus.msdtyp.AccessMask
import com.hierynomus.msfscc.FileAttributes
import com.hierynomus.mssmb2.SMB2CreateDisposition
import com.hierynomus.mssmb2.SMB2ShareAccess
import com.hierynomus.smbj.SMBClient
import com.hierynomus.smbj.SmbConfig
import com.hierynomus.smbj.auth.AuthenticationContext
import com.hierynomus.smbj.connection.Connection
import com.hierynomus.smbj.session.Session
import com.hierynomus.smbj.share.DiskShare
import com.jcraft.jsch.ChannelSftp
import com.jcraft.jsch.JSch
import com.rapid7.client.dcerpc.mssrvs.ServerService
import com.rapid7.client.dcerpc.transport.SMBTransportFactories
import org.apache.commons.net.ftp.FTP
import org.apache.commons.net.ftp.FTPClient
import org.apache.commons.net.ftp.FTPReply
import java.io.File
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.util.EnumSet
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * A browsable, seekable file share (spec 19.3: "Browse folders and play
 * files directly", "Support seeking and range requests so large MKV files
 * do not need full downloads first"). Blocking API: call from IO threads.
 */
interface RemoteFs : AutoCloseable {
    fun list(path: String): List<RemoteFile>
    fun length(path: String): Long
    /** A stream positioned at [offset]; closing it releases its server resources. */
    fun open(path: String, offset: Long): InputStream
    override fun close()
}

/** Connection settings resolved from a saved profile plus its stored password. */
data class ShareLogin(val profile: ServerProfile, val password: String?)

/**
 * One open connection per saved server, reused by browsing and every
 * playback stream, and rebuilt after a failure (spec 19.3a "reconnect
 * after Wi-Fi drops or NAS sleep").
 */
object RemoteFsPool {

    private val pool = ConcurrentHashMap<String, RemoteFs>()

    @Volatile var appContext: Context? = null

    fun get(login: ShareLogin): RemoteFs =
        pool[login.profile.id] ?: synchronized(this) {
            pool[login.profile.id] ?: create(login).also { pool[login.profile.id] = it }
        }

    fun create(login: ShareLogin): RemoteFs = when (login.profile.type) {
        ServerType.SMB -> SmbFs(login)
        ServerType.FTP -> FtpFs(login)
        ServerType.SFTP -> SftpFs(login, appContext)
        ServerType.NFS -> NfsFs(login)
        else -> throw NetworkError("${login.profile.type.label} is not a file share")
    }

    fun drop(profileId: String) {
        pool.remove(profileId)?.let { runCatching { it.close() } }
    }

    /** Runs [block]; on an I/O failure reconnects once and retries. */
    fun <T> withRetry(login: ShareLogin, block: (RemoteFs) -> T): T =
        try {
            block(get(login))
        } catch (e: NetworkError) {
            throw e
        } catch (e: Exception) {
            drop(login.profile.id)
            try {
                block(get(login))
            } catch (again: NetworkError) {
                throw again
            } catch (again: Exception) {
                throw NetworkError(friendly(again, login.profile.type))
            }
        }

    /** Turns library exceptions into "what happened and what to do" messages (spec 8). */
    fun friendly(e: Throwable, type: ServerType): String {
        val msg = (e.message ?: e.javaClass.simpleName)
        val lower = msg.lowercase()
        return when {
            e is NetworkError -> msg
            "status_logon_failure" in lower || "auth fail" in lower || "login" in lower && "incorrect" in lower ||
                "530" in lower -> "Wrong user name or password"
            "status_bad_network_name" in lower -> "Share not found. Check the share name"
            "status_access_denied" in lower || "permission denied" in lower || "nfs3err_acces" in lower ->
                "This account is not allowed to open that"
            "status_object_name_not_found" in lower || "no such file" in lower || "nfs3err_noent" in lower ->
                "File or folder not found"
            "connect" in lower && ("refused" in lower || "timed out" in lower) || "unreachable" in lower ||
                "no route" in lower || "unknownhost" in lower || e is java.net.UnknownHostException ->
                "Server unreachable. Check Wi-Fi, the address and that ${type.label} is enabled on it"
            "status_not_supported" in lower || "negotiat" in lower ->
                "The server only speaks an old SMB version. Turn on SMB2 or SMB3 on the server"
            "export" in lower || "mount" in lower -> "NFS export not available to this device. Check the export path and that it allows this IP"
            else -> "${type.label} error: ${msg.take(160)}"
        }
    }
}

// ---------------------------------------------------------------- SMB

/**
 * SMB 2/3 via smbj (spec 19.3a: SMB2/SMB3, user/password, domain, guest).
 * Paths are "/share/folder/file"; listing "/" enumerates shares over
 * SRVSVC when the profile has no fixed share.
 */
class SmbFs(private val login: ShareLogin) : RemoteFs {

    private val parts = SmbAddress.parse(login.profile.baseUrl) ?: throw NetworkError("Bad SMB address")
    private val client = SMBClient(
        SmbConfig.builder()
            .withTimeout(20, TimeUnit.SECONDS)
            .withSoTimeout(30, TimeUnit.SECONDS)
            .withMultiProtocolNegotiate(true)
            .withSigningRequired(false)
            .build()
    )
    private var connection: Connection? = null
    private var session: Session? = null
    private val shares = ConcurrentHashMap<String, DiskShare>()

    @Synchronized
    private fun session(): Session {
        session?.takeIf { connection?.isConnected == true }?.let { return it }
        val conn = client.connect(parts.host, parts.port)
        val (domain, user) = SmbAddress.splitUser(login.profile.username)
        val auth = when {
            user.isBlank() -> AuthenticationContext.guest()
            else -> AuthenticationContext(user, (login.password ?: "").toCharArray(), domain.ifBlank { null })
        }
        val s = try {
            conn.authenticate(auth)
        } catch (e: Exception) {
            if (user.isBlank()) conn.authenticate(AuthenticationContext.anonymous()) else throw e
        }
        connection = conn
        session = s
        shares.clear()
        return s
    }

    private fun share(name: String): DiskShare =
        shares[name]?.takeIf { it.isConnected } ?: (session().connectShare(name) as? DiskShare
            ?: throw NetworkError("\"$name\" is not a file share")).also { shares[name] = it }

    /** (share, path-inside-share as smbj expects it: backslashes, no leading slash). */
    private fun resolve(path: String): Pair<String, String> {
        val full = if (parts.share.isNotBlank()) RemotePaths.join("/" + parts.share, path) else RemotePaths.normalise(path)
        val (share, rest) = RemotePaths.splitFirst(full)
        return share to rest.trimStart('/').replace('/', '\\')
    }

    override fun list(path: String): List<RemoteFile> {
        val norm = RemotePaths.normalise(path)
        if (parts.share.isBlank() && norm == "/") return listShares()
        val (share, inner) = resolve(norm)
        return share(share).list(inner)
            .filter { it.fileName != "." && it.fileName != ".." }
            .map { info ->
                val isDir = (info.fileAttributes and FileAttributes.FILE_ATTRIBUTE_DIRECTORY.value) != 0L
                RemoteFile(info.fileName, RemotePaths.join(norm, info.fileName), isDir, info.endOfFile)
            }
    }

    private fun listShares(): List<RemoteFile> {
        val session = session()
        val transport = runCatching { SMBTransportFactories.SRVSVC.getTransport(session) }
            .recoverCatching { SMBTransportFactories.BROWSER_SRVSVC.getTransport(session) }
            .getOrElse {
                throw NetworkError("Signed in, but this server does not list its shares. Add it again with the share name, e.g. ${parts.host}/Movies")
            }
        val service = ServerService(transport)
        return service.shares1
            // type 0 = disk share; skip printers, IPC$ and admin shares
            .filter { (it.type and 0xFFFF) == 0 && !it.netName.endsWith("$") }
            .map { RemoteFile(it.netName, "/" + it.netName, true) }
    }

    override fun length(path: String): Long {
        val (share, inner) = resolve(path)
        return share(share).getFileInformation(inner).standardInformation.endOfFile
    }

    override fun open(path: String, offset: Long): InputStream {
        val (share, inner) = resolve(path)
        val file = share(share).openFile(
            inner,
            EnumSet.of(AccessMask.GENERIC_READ),
            null,
            EnumSet.of(SMB2ShareAccess.FILE_SHARE_READ, SMB2ShareAccess.FILE_SHARE_WRITE),
            SMB2CreateDisposition.FILE_OPEN,
            null
        )
        // Never ask for more than the server negotiated (MaxReadSize), or the
        // reply cannot be decoded; 64 KiB is the SMB2 floor every server accepts.
        val chunk = (connection?.negotiatedProtocol?.maxReadSize ?: 65_536).coerceIn(65_536, 1 shl 20)
        // Known size: never send a read at or past the end (some servers reply badly)
        val size = runCatching { file.fileInformation.standardInformation.endOfFile }.getOrDefault(Long.MAX_VALUE)
        // Positional reads: seeking is just a new offset, no re-download
        return object : InputStream() {
            var pos = offset
            override fun read(): Int {
                val b = ByteArray(1)
                return if (read(b, 0, 1) <= 0) -1 else b[0].toInt() and 0xFF
            }

            override fun read(b: ByteArray, off: Int, len: Int): Int {
                if (pos >= size) return -1
                val want = minOf(len.toLong(), chunk.toLong(), size - pos).toInt()
                val n = try {
                    file.read(b, pos, off, want)
                } catch (e: com.hierynomus.smbj.common.SMBRuntimeException) {
                    throw IOException(e.message, e)
                } catch (e: com.hierynomus.mssmb2.SMBApiException) {
                    throw IOException(e.message, e)
                }
                if (n <= 0) return -1
                pos += n
                return n
            }

            override fun close() {
                runCatching { file.close() }
            }
        }
    }

    override fun close() {
        shares.values.forEach { runCatching { it.close() } }
        runCatching { session?.close() }
        runCatching { connection?.close() }
        runCatching { client.close() }
    }
}

// ---------------------------------------------------------------- FTP

/**
 * FTP via commons-net. FTP allows one transfer per control connection, so
 * listing uses a shared connection and every playback stream gets its own,
 * starting at the requested offset with REST.
 */
class FtpFs(private val login: ShareLogin) : RemoteFs {

    private val parts = FtpAddress.parse(login.profile.baseUrl, defaultSftp = false) ?: throw NetworkError("Bad FTP address")
    private var control: FTPClient? = null

    private fun connect(): FTPClient {
        val c = FTPClient()
        c.controlEncoding = "UTF-8"
        c.connectTimeout = 15_000
        c.setDataTimeout(java.time.Duration.ofSeconds(30))
        c.connect(parts.host, parts.port)
        if (!FTPReply.isPositiveCompletion(c.replyCode)) {
            runCatching { c.disconnect() }
            throw NetworkError("FTP server refused the connection (${c.replyCode})")
        }
        val user = login.profile.username.ifBlank { "anonymous" }
        val pass = if (login.profile.username.isBlank()) "chaosplay@" else login.password.orEmpty()
        if (!c.login(user, pass)) {
            runCatching { c.disconnect() }
            throw NetworkError("Wrong user name or password")
        }
        c.enterLocalPassiveMode()
        c.setFileType(FTP.BINARY_FILE_TYPE)
        runCatching { c.sendCommand("OPTS UTF8 ON") }
        return c
    }

    private fun full(path: String) = RemotePaths.join(parts.path, path)

    @Synchronized
    private fun control(): FTPClient {
        control?.takeIf { it.isConnected && runCatching { it.sendNoOp() }.getOrDefault(false) }?.let { return it }
        runCatching { control?.disconnect() }
        return connect().also { control = it }
    }

    @Synchronized
    override fun list(path: String): List<RemoteFile> {
        val norm = RemotePaths.normalise(path)
        val files = control().listFiles(full(norm)) ?: emptyArray()
        return files.filterNotNull().map { RemoteFile(it.name, RemotePaths.join(norm, it.name), it.isDirectory, it.size) }
    }

    @Synchronized
    override fun length(path: String): Long {
        val c = control()
        c.getSize(full(path))?.trim()?.toLongOrNull()?.let { return it }
        return c.mlistFile(full(path))?.size ?: -1L
    }

    override fun open(path: String, offset: Long): InputStream {
        val c = connect()
        c.restartOffset = offset
        val stream = c.retrieveFileStream(full(path)) ?: run {
            val code = c.replyCode
            runCatching { c.disconnect() }
            throw NetworkError(if (code == 550) "File not found on the FTP server" else "FTP server refused the download ($code)")
        }
        return object : FilterInputStream(stream) {
            override fun close() {
                runCatching { super.close() }
                // Abort rather than wait for the whole file when the player seeks away
                runCatching { c.abort() }
                runCatching { c.logout() }
                runCatching { c.disconnect() }
            }
        }
    }

    @Synchronized
    override fun close() {
        runCatching { control?.logout() }
        runCatching { control?.disconnect() }
        control = null
    }
}

// ---------------------------------------------------------------- SFTP

/**
 * SFTP via JSch. Host keys are remembered on first use in the app's
 * private known_hosts file (trust on first use); a changed key afterwards
 * is refused, which is what protects against a spoofed server.
 */
class SftpFs(private val login: ShareLogin, context: Context?) : RemoteFs {

    private val parts = FtpAddress.parse(login.profile.baseUrl, defaultSftp = true) ?: throw NetworkError("Bad SFTP address")
    private val knownHosts: File? = context?.let { File(it.filesDir, "sftp_known_hosts") }
    private val jsch = JSch()
    private var session: com.jcraft.jsch.Session? = null

    @Synchronized
    private fun session(): com.jcraft.jsch.Session {
        session?.takeIf { it.isConnected }?.let { return it }
        knownHosts?.let { f ->
            if (!f.exists()) f.createNewFile()
            jsch.setKnownHosts(f.absolutePath)
            jsch.hostKeyRepository = TofuHostKeys(jsch.hostKeyRepository)
        }
        val user = login.profile.username.ifBlank { throw NetworkError("Enter the SSH user name") }
        val s = jsch.getSession(user, parts.host, parts.port)
        s.setPassword(login.password.orEmpty())
        // "yes" + TofuHostKeys = remember a new host, refuse a changed key
        s.setConfig("StrictHostKeyChecking", "yes")
        s.setConfig("PreferredAuthentications", "password,keyboard-interactive")
        s.timeout = 20_000
        s.connect(20_000)
        session = s
        return s
    }

    private fun channel(): ChannelSftp =
        (session().openChannel("sftp") as ChannelSftp).also { it.connect(15_000) }

    private fun full(path: String) = RemotePaths.join(parts.path, path)

    override fun list(path: String): List<RemoteFile> {
        val norm = RemotePaths.normalise(path)
        val ch = channel()
        try {
            @Suppress("UNCHECKED_CAST")
            val entries = ch.ls(full(norm)) as java.util.Vector<ChannelSftp.LsEntry>
            return entries.map { RemoteFile(it.filename, RemotePaths.join(norm, it.filename), it.attrs.isDir, it.attrs.size) }
        } finally {
            ch.disconnect()
        }
    }

    override fun length(path: String): Long {
        val ch = channel()
        try {
            return ch.stat(full(path)).size
        } finally {
            ch.disconnect()
        }
    }

    override fun open(path: String, offset: Long): InputStream {
        val ch = channel()
        val stream = ch.get(full(path), null, offset)
        return object : FilterInputStream(stream) {
            override fun close() {
                runCatching { super.close() }
                runCatching { ch.disconnect() }
            }
        }
    }

    @Synchronized
    override fun close() {
        runCatching { session?.disconnect() }
        session = null
    }
}

/**
 * Trust on first use: an unknown host's key is saved and accepted; a key
 * that differs from the saved one is reported as CHANGED, which JSch
 * refuses under StrictHostKeyChecking=yes (spec 19.8).
 */
private class TofuHostKeys(private val known: com.jcraft.jsch.HostKeyRepository) : com.jcraft.jsch.HostKeyRepository by known {
    override fun check(host: String?, key: ByteArray?): Int {
        val result = known.check(host, key)
        if (result == com.jcraft.jsch.HostKeyRepository.NOT_INCLUDED && host != null && key != null) {
            runCatching { known.add(com.jcraft.jsch.HostKey(host, key), null) }
            return com.jcraft.jsch.HostKeyRepository.OK
        }
        return result
    }
}

// ---------------------------------------------------------------- NFS

/**
 * NFSv3 via nfs-client-java (no root, no kernel mount). AUTH_UNIX with the
 * uid/gid from the user field ("1000:1000"; blank = nobody 65534), which is
 * how NAS exports normally map access.
 */
class NfsFs(private val login: ShareLogin) : RemoteFs {

    private val parts = NfsAddress.parse(login.profile.baseUrl) ?: throw NetworkError("Bad NFS address")
    private var nfs: Nfs3? = null

    @Synchronized
    private fun nfs(): Nfs3 = nfs ?: run {
        val ids = login.profile.username.split(':').mapNotNull { it.trim().toIntOrNull() }
        val uid = ids.getOrNull(0) ?: 65534
        val gid = ids.getOrNull(1) ?: uid
        Nfs3(parts.host, parts.export, CredentialUnix(uid, gid, null), 3).also { nfs = it }
    }

    private fun file(path: String) = Nfs3File(nfs(), RemotePaths.normalise(path))

    override fun list(path: String): List<RemoteFile> {
        val norm = RemotePaths.normalise(path)
        return file(norm).listFiles().map { f ->
            RemoteFile(f.name, RemotePaths.join(norm, f.name), runCatching { f.isDirectory }.getOrDefault(false), runCatching { f.length() }.getOrDefault(0L))
        }
    }

    override fun length(path: String): Long = file(path).length()

    override fun open(path: String, offset: Long): InputStream =
        NfsFileInputStream(file(path), offset, 1 shl 20)

    @Synchronized
    override fun close() {
        nfs = null
    }
}

/** Thrown when a share answers but the item cannot be streamed. */
class RemoteFsException(message: String) : IOException(message)
