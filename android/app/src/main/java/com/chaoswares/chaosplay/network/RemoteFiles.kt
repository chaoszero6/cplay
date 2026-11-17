package com.chaoswares.chaosplay.network

import com.chaoswares.chaosplay.subtitles.SubtitleFormat
import com.chaoswares.chaosplay.subtitles.SubtitleSelector
import java.net.URLDecoder
import java.net.URLEncoder

/** One file or folder on an SMB, FTP, SFTP or NFS share. */
data class RemoteFile(
    val name: String,
    /** Absolute path inside the source, "/" separated, e.g. "/Movies/clip.mkv". */
    val path: String,
    val isDir: Boolean,
    val size: Long = 0L
)

/**
 * Playable locations for file-share sources. Credentials are never part of
 * the URI: `chaos-smb://<profileId>/share/dir/file.mkv` names the saved
 * server, and the data source looks the password up in the Keystore-backed
 * store at play time (spec 19.8). History and resume keys stay token-free.
 */
object RemoteUris {

    const val SMB = "chaos-smb"
    const val FTP = "chaos-ftp"
    const val SFTP = "chaos-sftp"
    const val NFS = "chaos-nfs"

    val SCHEMES = setOf(SMB, FTP, SFTP, NFS)

    data class Parsed(val scheme: String, val profileId: String, val path: String)

    fun schemeFor(type: ServerType): String? = when (type) {
        ServerType.SMB -> SMB
        ServerType.FTP -> FTP
        ServerType.SFTP -> SFTP
        ServerType.NFS -> NFS
        else -> null
    }

    fun isRemote(uri: String): Boolean = SCHEMES.any { uri.startsWith("$it://") }

    fun build(scheme: String, profileId: String, path: String): String {
        val segments = RemotePaths.normalise(path).split('/').filter { it.isNotEmpty() }
        val encoded = segments.joinToString("/") { URLEncoder.encode(it, "UTF-8").replace("+", "%20") }
        return "$scheme://$profileId/$encoded"
    }

    fun parse(uri: String): Parsed? {
        val scheme = uri.substringBefore("://", "")
        if (scheme !in SCHEMES) return null
        val rest = uri.substringAfter("://")
        val profileId = rest.substringBefore('/')
        if (profileId.isBlank()) return null
        val rawPath = rest.substringAfter('/', "").substringBefore('?').substringBefore('#')
        val path = "/" + rawPath.split('/').filter { it.isNotEmpty() }
            .joinToString("/") { URLDecoder.decode(it.replace("+", "%2B"), "UTF-8") }
        return Parsed(scheme, profileId, path)
    }
}

/** Path helpers shared by every file-share source. */
object RemotePaths {

    /** "a\\b//c/" -> "/a/b/c"; never empty, root is "/". */
    fun normalise(path: String): String {
        val parts = path.replace('\\', '/').split('/').filter { it.isNotEmpty() && it != "." }
        val stack = ArrayList<String>()
        for (p in parts) {
            if (p == "..") {
                if (stack.isNotEmpty()) stack.removeAt(stack.lastIndex)
            } else stack += p
        }
        return "/" + stack.joinToString("/")
    }

    fun join(dir: String, name: String): String = normalise("$dir/$name")

    /** Parent folder, or null at the root. */
    fun parent(path: String): String? {
        val n = normalise(path)
        if (n == "/") return null
        return normalise(n.substringBeforeLast('/'))
    }

    fun name(path: String): String = normalise(path).substringAfterLast('/')

    /** Breadcrumbs from the root: [("Home","/"), ("Movies","/Movies"), ...]. */
    fun crumbs(path: String, rootLabel: String = "Home"): List<Pair<String, String>> {
        val out = mutableListOf(rootLabel to "/")
        var cur = ""
        for (seg in normalise(path).split('/').filter { it.isNotEmpty() }) {
            cur += "/$seg"
            out += seg to cur
        }
        return out
    }

    /** The first segment (an SMB share name) and the rest of the path. */
    fun splitFirst(path: String): Pair<String, String> {
        val n = normalise(path)
        val segs = n.split('/').filter { it.isNotEmpty() }
        if (segs.isEmpty()) return "" to "/"
        return segs.first() to "/" + segs.drop(1).joinToString("/")
    }
}

/** What the share browser shows and plays (spec 19.3 / 19.3a). */
object RemoteFileRules {

    fun isVideo(name: String) = WebDavRules.extensionOf(name) in WebDavRules.VIDEO_EXTENSIONS
    fun isAudio(name: String) = WebDavRules.extensionOf(name) in WebDavRules.AUDIO_EXTENSIONS
    fun isSubtitle(name: String) = SubtitleFormat.isSubtitleFile(name)

    /**
     * Folders and playable files by default; everything with [showAll].
     * Hidden entries (".x", "$RECYCLE.BIN", admin shares "C$") are skipped.
     */
    fun visible(files: List<RemoteFile>, showAll: Boolean): List<RemoteFile> =
        files
            .filterNot { it.name == "." || it.name == ".." || it.name.startsWith(".") }
            .filterNot { it.name.endsWith("$") || it.name.equals("\$RECYCLE.BIN", true) || it.name == "System Volume Information" }
            .filter { showAll || it.isDir || isVideo(it.name) || isAudio(it.name) }
            .sortedWith(compareBy<RemoteFile>({ !it.isDir }, { it.name.lowercase() }))

    /** Subtitle files beside [video], best match first. */
    fun sidecars(video: RemoteFile, siblings: List<RemoteFile>): List<RemoteFile> {
        val subs = siblings.filter { !it.isDir && isSubtitle(it.name) }
        val base = video.name.substringBeforeLast('.').lowercase()
        return SubtitleSelector.rank(video.name, subs.map { it.name })
            .filter { it.lowercase().startsWith(base) }
            .mapNotNull { n -> subs.firstOrNull { it.name == n } }
    }

    fun kindOf(file: RemoteFile): String = when {
        file.isDir -> "folder"
        isVideo(file.name) -> "video"
        isAudio(file.name) -> "audio"
        isSubtitle(file.name) -> "subtitle"
        else -> "file"
    }

    fun toEntry(file: RemoteFile, siblings: List<RemoteFile>, uriFor: (String) -> String): RemoteEntry =
        RemoteEntry(
            id = file.path,
            name = file.name,
            isFolder = file.isDir,
            playUri = if (file.isDir) "" else uriFor(file.path),
            sizeBytes = file.size,
            subtitleUris = if (isVideo(file.name)) sidecars(file, siblings).map { uriFor(it.path) } else emptyList(),
            kind = kindOf(file)
        )
}

/**
 * SMB addresses as people type them (spec 19.3a "Connect by hostname or
 * IP"): `\\nas\Movies`, `smb://nas/Movies/Kids`, `192.168.1.5`, `nas:4450`.
 */
object SmbAddress {

    data class Parts(val host: String, val port: Int, val share: String, val path: String)

    const val DEFAULT_PORT = 445

    fun parse(input: String): Parts? {
        var s = input.trim()
        if (s.isEmpty()) return null
        s = s.removePrefix("smb://").removePrefix("SMB://").removePrefix("cifs://")
        s = s.replace('\\', '/').trimStart('/')
        val hostPort = s.substringBefore('/')
        val rest = s.substringAfter('/', "")
        val host = hostPort.substringBefore(':').trim()
        if (host.isEmpty() || host.any { it.isWhitespace() }) return null
        val port = if (':' in hostPort) hostPort.substringAfter(':').toIntOrNull() ?: return null else DEFAULT_PORT
        if (port !in 1..65535) return null
        val (share, path) = RemotePaths.splitFirst(rest)
        return Parts(host, port, share, path)
    }

    /** "WORKGROUP\\bob" or "bob@corp" -> (domain, user); plain "bob" -> ("", "bob"). */
    fun splitUser(user: String): Pair<String, String> {
        val u = user.trim()
        return when {
            '\\' in u -> u.substringBefore('\\') to u.substringAfter('\\')
            '/' in u -> u.substringBefore('/') to u.substringAfter('/')
            '@' in u -> u.substringAfter('@') to u.substringBefore('@')
            else -> "" to u
        }
    }

    /** Address saved in the profile: smb://host:port/share (share may be empty). */
    fun format(parts: Parts): String =
        "smb://${parts.host}:${parts.port}/" + parts.share
}

/** ftp:// and sftp:// addresses; a bare host uses [defaultSftp] to pick the protocol. */
object FtpAddress {

    data class Parts(val sftp: Boolean, val host: String, val port: Int, val path: String)

    fun parse(input: String, defaultSftp: Boolean): Parts? {
        val s = input.trim()
        if (s.isEmpty()) return null
        val sftp = when {
            s.startsWith("sftp://", true) -> true
            s.startsWith("ftp://", true) -> false
            else -> defaultSftp
        }
        val body = s.substringAfter("://", s)
        val hostPort = body.substringBefore('/').substringAfterLast('@')
        val host = hostPort.substringBefore(':').trim()
        if (host.isEmpty() || host.any { it.isWhitespace() }) return null
        val port = if (':' in hostPort) hostPort.substringAfter(':').toIntOrNull() ?: return null else if (sftp) 22 else 21
        if (port !in 1..65535) return null
        val path = RemotePaths.normalise(body.substringAfter('/', ""))
        return Parts(sftp, host, port, path)
    }

    fun format(p: Parts): String = (if (p.sftp) "sftp" else "ftp") + "://${p.host}:${p.port}${if (p.path == "/") "" else p.path}"
}

/** NFS exports: "nas:/volume1/video", "nfs://nas/volume1/video". */
object NfsAddress {

    data class Parts(val host: String, val export: String)

    fun parse(input: String): Parts? {
        var s = input.trim()
        if (s.isEmpty()) return null
        s = s.removePrefix("nfs://").removePrefix("NFS://")
        val host: String
        val export: String
        if (s.contains(":/")) {
            host = s.substringBefore(":/")
            export = "/" + s.substringAfter(":/")
        } else {
            host = s.substringBefore('/')
            export = "/" + s.substringAfter('/', "")
        }
        if (host.isBlank() || host.any { it.isWhitespace() }) return null
        val norm = RemotePaths.normalise(export)
        if (norm == "/") return null // an export path is required
        return Parts(host.trim(), norm)
    }

    fun format(p: Parts): String = "nfs://${p.host}${p.export}"
}
