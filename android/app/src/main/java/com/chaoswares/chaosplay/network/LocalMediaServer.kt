package com.chaoswares.chaosplay.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.Uri
import okhttp3.Request
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.io.OutputStream
import java.io.RandomAccessFile
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URLEncoder
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * Tiny HTTP server that lets a TV play what the phone can play: local
 * files, content:// items, SMB/FTP/SFTP/NFS share files and WebDAV files
 * that need a password. Only items the user is casting are served, each
 * under a random 32-character token; everything stops with [stop].
 */
object LocalMediaServer {

    private sealed class Source {
        data class Content(val uri: Uri) : Source()
        data class LocalFile(val path: String) : Source()
        data class Share(val uri: String) : Source()
        data class Http(val url: String) : Source()
    }

    private data class Item(val source: Source, val name: String, val mime: String)

    private val items = ConcurrentHashMap<String, Item>()
    private val pool = Executors.newCachedThreadPool()
    private val random = SecureRandom()
    @Volatile private var server: ServerSocket? = null
    @Volatile private var appContext: Context? = null

    /** This phone's Wi-Fi/Ethernet IPv4 address, as the TV sees it. */
    fun lanAddress(context: Context): String? {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val props = cm.getLinkProperties(cm.activeNetwork) ?: return null
        return props.linkAddresses.map { it.address }
            .firstOrNull { it is Inet4Address && !it.isLoopbackAddress }
            ?.hostAddress
    }

    /** Returns a LAN URL for [location] that the TV can fetch, starting the server if needed. */
    @Synchronized
    fun share(context: Context, location: String, displayName: String): String {
        appContext = context.applicationContext
        val ip = lanAddress(context) ?: throw NetworkError("Connect the phone to the same Wi-Fi as the TV")
        val socket = server ?: ServerSocket().apply {
            reuseAddress = true
            bind(InetSocketAddress(0))
            server = this
            pool.execute { acceptLoop(this) }
        }
        val token = buildString { repeat(32) { append("abcdefghijklmnopqrstuvwxyz0123456789"[random.nextInt(36)]) } }
        val source = when {
            location.startsWith("content://") -> Source.Content(Uri.parse(location))
            location.startsWith("/") -> Source.LocalFile(location)
            location.startsWith("file://") -> Source.LocalFile(Uri.parse(location).path.orEmpty())
            RemoteUris.isRemote(location) -> Source.Share(location)
            else -> Source.Http(location)
        }
        val name = displayName.ifBlank { "video.mp4" }
        items[token] = Item(source, name, MediaServerRules.mimeFor(name))
        val safe = URLEncoder.encode(name, "UTF-8").replace("+", "%20")
        return "http://$ip:${socket.localPort}/$token/$safe"
    }

    fun mimeOf(displayName: String): String = MediaServerRules.mimeFor(displayName)

    @Synchronized
    fun stop() {
        items.clear()
        runCatching { server?.close() }
        server = null
    }

    private fun acceptLoop(socket: ServerSocket) {
        while (!socket.isClosed) {
            val client = try {
                socket.accept()
            } catch (e: Exception) {
                break
            }
            pool.execute { runCatching { handle(client) }; runCatching { client.close() } }
        }
    }

    private fun handle(client: Socket) {
        client.soTimeout = 30_000
        val input = BufferedInputStream(client.getInputStream())
        val requestLine = readLine(input) ?: return
        val headers = HashMap<String, String>()
        while (true) {
            val line = readLine(input) ?: break
            if (line.isEmpty()) break
            val i = line.indexOf(':')
            if (i > 0) headers[line.substring(0, i).trim().lowercase()] = line.substring(i + 1).trim()
        }
        val parts = requestLine.split(' ')
        val method = parts.getOrNull(0).orEmpty()
        val token = parts.getOrNull(1).orEmpty().trimStart('/').substringBefore('/')
        val out = client.getOutputStream()
        val item = items[token]
        if ((method != "GET" && method != "HEAD") || !MediaServerRules.isValidToken(token) || item == null) {
            writeStatus(out, 404, "Not Found", mapOf("Content-Length" to "0"))
            return
        }
        when (val src = item.source) {
            is Source.Http -> proxyHttp(src.url, method, headers["range"], out, item.mime)
            else -> serveSeekable(item, method == "HEAD", headers["range"], out)
        }
    }

    /** Files, content:// and shares: known size, positional opens. */
    private fun serveSeekable(item: Item, headOnly: Boolean, rangeHeader: String?, out: OutputStream) {
        val ctx = appContext ?: return
        val size: Long
        val opener: (Long) -> InputStream
        when (val s = item.source) {
            is Source.LocalFile -> {
                size = File(s.path).length()
                opener = { offset -> RandomAccessFile(s.path, "r").let { raf -> raf.seek(offset); FileInputStream(raf.fd) } }
            }
            is Source.Content -> {
                size = ctx.contentResolver.openAssetFileDescriptor(s.uri, "r")?.use { it.length.takeIf { l -> l >= 0 } ?: it.parcelFileDescriptor.statSize } ?: -1L
                opener = { offset ->
                    val afd = ctx.contentResolver.openAssetFileDescriptor(s.uri, "r") ?: error("cannot open")
                    val stream = afd.createInputStream()
                    stream.channel.position(afd.startOffset + offset)
                    stream
                }
            }
            is Source.Share -> {
                val parsed = RemoteUris.parse(s.uri) ?: return
                val login = NetworkRepository.get(ctx).shareLoginBlocking(parsed.profileId)
                size = RemoteFsPool.withRetry(login) { it.length(parsed.path) }
                opener = { offset -> RemoteFsPool.withRetry(login) { it.open(parsed.path, offset) } }
            }
            is Source.Http -> return
        }
        val dlna = mapOf(
            "Accept-Ranges" to "bytes",
            "Content-Type" to item.mime,
            "transferMode.dlna.org" to "Streaming",
            "contentFeatures.dlna.org" to "DLNA.ORG_OP=01;DLNA.ORG_CI=0;DLNA.ORG_FLAGS=01700000000000000000000000000000",
            "Connection" to "close"
        )
        val range = MediaServerRules.parseRange(rangeHeader, size)
        if (range != null && range.start < 0) {
            writeStatus(out, 416, "Range Not Satisfiable", dlna + mapOf("Content-Range" to "bytes */$size", "Content-Length" to "0"))
            return
        }
        val start = range?.start ?: 0L
        val length = range?.length ?: size
        if (range != null) {
            writeStatus(out, 206, "Partial Content", dlna + mapOf("Content-Range" to "bytes ${range.start}-${range.end}/$size", "Content-Length" to "$length"))
        } else {
            writeStatus(out, 200, "OK", dlna + mapOf("Content-Length" to "$size"))
        }
        if (headOnly) return
        opener(start).use { copy(it, out, length) }
    }

    /** WebDAV/Nextcloud files need credentials the TV does not have: relay them. */
    private fun proxyHttp(url: String, method: String, range: String?, out: OutputStream, mime: String) {
        val builder = Request.Builder().url(url)
        if (method == "HEAD") builder.head() else builder.get()
        if (range != null) builder.header("Range", range)
        NetHttp.client.newCall(builder.build()).execute().use { resp ->
            val h = linkedMapOf(
                "Content-Type" to (resp.header("Content-Type") ?: mime),
                "Accept-Ranges" to "bytes",
                "transferMode.dlna.org" to "Streaming",
                "Connection" to "close"
            )
            resp.header("Content-Length")?.let { h["Content-Length"] = it }
            resp.header("Content-Range")?.let { h["Content-Range"] = it }
            writeStatus(out, resp.code, resp.message.ifBlank { "OK" }, h)
            if (method != "HEAD") resp.body?.byteStream()?.use { copy(it, out, Long.MAX_VALUE) }
        }
    }

    private fun copy(input: InputStream, out: OutputStream, limit: Long) {
        val buf = ByteArray(64 * 1024)
        var left = limit
        while (left > 0) {
            val n = input.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
            if (n < 0) break
            out.write(buf, 0, n)
            left -= n
        }
        out.flush()
    }

    private fun writeStatus(out: OutputStream, code: Int, text: String, headers: Map<String, String>) {
        val sb = StringBuilder("HTTP/1.1 $code $text\r\n")
        headers.forEach { (k, v) -> sb.append(k).append(": ").append(v).append("\r\n") }
        sb.append("Server: CPlay/1.4 UPnP/1.0 DLNADOC/1.50\r\n\r\n")
        out.write(sb.toString().toByteArray(Charsets.ISO_8859_1))
        out.flush()
    }

    private fun readLine(input: InputStream): String? {
        val sb = StringBuilder()
        while (true) {
            val c = input.read()
            if (c < 0) return if (sb.isEmpty()) null else sb.toString()
            if (c == '\n'.code) return sb.toString().trimEnd('\r')
            sb.append(c.toChar())
            if (sb.length > 8192) return null
        }
    }
}
