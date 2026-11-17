package com.chaoswares.chaosplay.network

import com.chaoswares.chaosplay.util.FieldCodec

/** Kinds of saved sources on the Network tab (spec 4.6 / 19.1). */
enum class ServerType(val label: String, val available: Boolean) {
    JELLYFIN("Jellyfin", true),
    NEXTCLOUD("Nextcloud", true),
    WEBDAV("WebDAV", true),
    URL("Stream URL", true),
    SMB("SMB / Windows share", true),
    FTP("FTP", true),
    SFTP("SFTP (SSH)", true),
    NFS("NFS", true),
    DLNA("DLNA / UPnP", true);

    /** Sources browsed as folders of files (one shared browser and data source). */
    val isFileShare: Boolean get() = this == SMB || this == FTP || this == SFTP || this == NFS
}

/**
 * A saved server. Secrets (password, access token) are NOT part of this
 * record: they live encrypted with an Android Keystore key and are looked
 * up by [id] (spec 19.8 "store credentials in Android Keystore").
 */
data class ServerProfile(
    val id: String,
    val type: ServerType,
    val name: String,
    /** Jellyfin: server root. WebDAV/Nextcloud: the DAV root folder URL. URL: the stream. */
    val baseUrl: String,
    val username: String = "",
    /** Jellyfin user id returned by sign-in. */
    val userId: String = "",
    val addedAtSec: Long = 0L,
    /** User accepted a self-signed / untrusted certificate for this host (19.8). */
    val trustAllCerts: Boolean = false
)

object ServerProfiles {

    fun add(list: List<ServerProfile>, profile: ServerProfile): List<ServerProfile> =
        list.filterNot { it.id == profile.id } + profile

    fun remove(list: List<ServerProfile>, id: String): List<ServerProfile> = list.filterNot { it.id == id }

    fun encode(list: List<ServerProfile>): String = FieldCodec.encode(list.map {
        listOf(
            "S1", it.id, it.type.name, it.name, it.baseUrl, it.username, it.userId,
            it.addedAtSec.toString(), it.trustAllCerts.toString()
        )
    })

    fun decode(raw: String): List<ServerProfile> = FieldCodec.decode(raw).mapNotNull { r ->
        if (r.getOrNull(0) != "S1") return@mapNotNull null
        val id = r.getOrNull(1)?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
        val type = runCatching { ServerType.valueOf(r[2]) }.getOrNull() ?: return@mapNotNull null
        val base = r.getOrNull(4)?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
        ServerProfile(
            id = id,
            type = type,
            name = r.getOrNull(3).orEmpty().ifBlank { type.label },
            baseUrl = base,
            username = r.getOrNull(5).orEmpty(),
            userId = r.getOrNull(6).orEmpty(),
            addedAtSec = r.getOrNull(7)?.toLongOrNull() ?: 0L,
            trustAllCerts = r.getOrNull(8)?.toBooleanStrictOrNull() ?: false
        )
    }
}

/** One row when browsing any remote source (spec 19.9 `browse(path)`). */
data class RemoteEntry(
    /** Folder path / item id to browse into, or the playable location. */
    val id: String,
    val name: String,
    val isFolder: Boolean,
    /** Location handed to the player: an http(s) URL or a jellyfin:// key. */
    val playUri: String = "",
    val sizeBytes: Long = 0L,
    val durationMs: Long = 0L,
    val mimeType: String = "",
    val imageUrl: String? = null,
    val resumeMs: Long = 0L,
    val played: Boolean = false,
    val subtitle: String = "",
    val overview: String = "",
    /** Extra subtitle files to offer in the subtitle menu (sidecars, server subtitles). */
    val subtitleUris: List<String> = emptyList(),
    val kind: String = ""
) {
    val isPlayable: Boolean get() = !isFolder && playUri.isNotBlank()
}

/** Minimal RFC 4648 Base64 (java.util.Base64 needs API 26, minSdk is 24). */
object Base64Lite {
    private const val ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"

    fun encode(bytes: ByteArray): String {
        val out = StringBuilder((bytes.size + 2) / 3 * 4)
        var i = 0
        while (i < bytes.size) {
            val b0 = bytes[i].toInt() and 0xFF
            val b1 = if (i + 1 < bytes.size) bytes[i + 1].toInt() and 0xFF else -1
            val b2 = if (i + 2 < bytes.size) bytes[i + 2].toInt() and 0xFF else -1
            out.append(ALPHABET[b0 shr 2])
            out.append(ALPHABET[((b0 and 0x03) shl 4) or (if (b1 >= 0) b1 shr 4 else 0)])
            out.append(if (b1 >= 0) ALPHABET[((b1 and 0x0F) shl 2) or (if (b2 >= 0) b2 shr 6 else 0)] else '=')
            out.append(if (b2 >= 0) ALPHABET[b2 and 0x3F] else '=')
            i += 3
        }
        return out.toString()
    }

    fun basicAuth(user: String, password: String): String =
        "Basic " + encode("$user:$password".toByteArray(Charsets.UTF_8))
}
