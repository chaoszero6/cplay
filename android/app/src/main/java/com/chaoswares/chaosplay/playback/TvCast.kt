package com.chaoswares.chaosplay.playback

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.cast.CastPlayer
import androidx.media3.cast.SessionAvailabilityListener
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.mediarouter.media.MediaRouteSelector
import androidx.mediarouter.media.MediaRouter
import com.chaoswares.chaosplay.network.DlnaRules
import com.chaoswares.chaosplay.network.NetHttp
import com.chaoswares.chaosplay.network.NetworkError
import com.chaoswares.chaosplay.network.TvRules
import com.google.android.gms.cast.CastMediaControlIntent
import com.google.android.gms.cast.framework.CastContext
import com.google.android.gms.cast.framework.CastOptions
import com.google.android.gms.cast.framework.OptionsProvider
import com.google.android.gms.cast.framework.SessionProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketTimeoutException

/** Google Cast setup: the Default Media Receiver plays MP4/WebM/HLS/DASH links. */
class CastOptionsProvider : OptionsProvider {
    override fun getCastOptions(context: Context): CastOptions =
        CastOptions.Builder()
            .setReceiverApplicationId(CastMediaControlIntent.DEFAULT_MEDIA_RECEIVER_APPLICATION_ID)
            .build()

    override fun getAdditionalSessionProviders(context: Context): List<SessionProvider>? = null
}

/** A TV or speaker the video can be sent to. */
data class TvDevice(
    val id: String,
    val name: String,
    val kind: Kind,
    val detail: String,
    val renderer: TvRules.Renderer? = null
) {
    enum class Kind(val label: String) { DLNA("DLNA TV"), CAST("Chromecast") }
}

/** What a TV is doing, polled for the remote-control screen. */
data class TvStatus(val playing: Boolean, val positionMs: Long, val durationMs: Long, val state: String)

/** One remote playback on a TV. */
interface TvPlayback {
    suspend fun start(url: String, title: String, mime: String, durationMs: Long, startMs: Long)
    suspend fun play()
    suspend fun pause()
    suspend fun seek(positionMs: Long)
    suspend fun stop()
    suspend fun status(): TvStatus
}

/** DLNA renderer control over SOAP (AVTransport). */
class DlnaTvPlayback(private val renderer: TvRules.Renderer) : TvPlayback {

    private suspend fun call(action: String, body: String): String = withContext(Dispatchers.IO) {
        val req = Request.Builder().url(renderer.avTransportUrl)
            .post(body.toRequestBody("text/xml; charset=\"utf-8\"".toMediaType()))
            .header("SOAPAction", TvRules.soapAction(TvRules.AV_TRANSPORT, action))
            .build()
        try {
            NetHttp.client.newCall(req).execute().use { resp ->
                val text = resp.body?.string().orEmpty()
                TvRules.faultMessage(text)?.let { if (!resp.isSuccessful) throw NetworkError("TV: $it") }
                if (!resp.isSuccessful) throw NetworkError("The TV answered ${resp.code}")
                text
            }
        } catch (e: NetworkError) {
            throw e
        } catch (e: Exception) {
            throw NetworkError("Lost contact with the TV. Is it still on?")
        }
    }

    override suspend fun start(url: String, title: String, mime: String, durationMs: Long, startMs: Long) {
        runCatching { call("Stop", TvRules.stop()) }
        call("SetAVTransportURI", TvRules.setUri(url, TvRules.didl(title, url, mime, durationMs)))
        call("Play", TvRules.play())
        if (startMs > 5_000) {
            // Many TVs only accept Seek once playback has begun
            repeat(10) {
                kotlinx.coroutines.delay(700)
                val state = runCatching { status().state }.getOrDefault("")
                if (state == "PLAYING") {
                    runCatching { call("Seek", TvRules.seek(startMs)) }
                    return
                }
            }
        }
    }

    override suspend fun play() { call("Play", TvRules.play()) }
    override suspend fun pause() { call("Pause", TvRules.pause()) }
    override suspend fun seek(positionMs: Long) { call("Seek", TvRules.seek(positionMs)) }
    override suspend fun stop() { runCatching { call("Stop", TvRules.stop()) } }

    override suspend fun status(): TvStatus {
        val state = TvRules.parseTransportState(call("GetTransportInfo", TvRules.transportInfo()))
        val pos = TvRules.parsePosition(call("GetPositionInfo", TvRules.positionInfo()))
        return TvStatus(state == "PLAYING", pos.positionMs, pos.durationMs, state)
    }
}

/** Chromecast / Google TV through Media3's CastPlayer (Default Media Receiver). */
@OptIn(UnstableApi::class)
class CastTvPlayback(private val castPlayer: CastPlayer) : TvPlayback {

    override suspend fun start(url: String, title: String, mime: String, durationMs: Long, startMs: Long) = withContext(Dispatchers.Main) {
        val item = MediaItem.Builder()
            .setUri(url)
            .setMimeType(castMime(mime))
            .setMediaMetadata(MediaMetadata.Builder().setTitle(title).build())
            .build()
        castPlayer.setMediaItem(item, startMs)
        castPlayer.prepare()
        castPlayer.playWhenReady = true
    }

    private fun castMime(mime: String) = when {
        mime == "video/x-matroska" -> "video/webm" // the receiver plays H.264 MKV when told webm/mp4
        mime.isBlank() -> "video/mp4"
        else -> mime
    }

    override suspend fun play() = withContext(Dispatchers.Main) { castPlayer.play() }
    override suspend fun pause() = withContext(Dispatchers.Main) { castPlayer.pause() }
    override suspend fun seek(positionMs: Long) = withContext(Dispatchers.Main) { castPlayer.seekTo(positionMs) }
    override suspend fun stop() = withContext(Dispatchers.Main) {
        castPlayer.stop()
        runCatching { CastContext.getSharedInstance()?.sessionManager?.endCurrentSession(true) }
        Unit
    }

    override suspend fun status(): TvStatus = withContext(Dispatchers.Main) {
        val state = when (castPlayer.playbackState) {
            Player.STATE_READY -> if (castPlayer.isPlaying) "PLAYING" else "PAUSED_PLAYBACK"
            Player.STATE_BUFFERING -> "TRANSITIONING"
            Player.STATE_ENDED -> "STOPPED"
            else -> "NO_MEDIA_PRESENT"
        }
        TvStatus(castPlayer.isPlaying, castPlayer.currentPosition, castPlayer.duration.coerceAtLeast(0), state)
    }
}

/**
 * Finds TVs: DLNA renderers by SSDP and Cast devices by MediaRouter.
 * Cast needs Google Play services; without it only DLNA TVs are listed.
 */
@OptIn(UnstableApi::class)
object TvDiscovery {

    private val _cast = MutableStateFlow<List<TvDevice>>(emptyList())
    val castDevices: StateFlow<List<TvDevice>> = _cast.asStateFlow()

    private var router: MediaRouter? = null
    private val selector by lazy {
        MediaRouteSelector.Builder()
            .addControlCategory(CastMediaControlIntent.categoryForCast(CastMediaControlIntent.DEFAULT_MEDIA_RECEIVER_APPLICATION_ID))
            .build()
    }

    private val callback = object : MediaRouter.Callback() {
        override fun onRouteAdded(router: MediaRouter, route: MediaRouter.RouteInfo) = refresh(router)
        override fun onRouteRemoved(router: MediaRouter, route: MediaRouter.RouteInfo) = refresh(router)
        override fun onRouteChanged(router: MediaRouter, route: MediaRouter.RouteInfo) = refresh(router)
    }

    private fun refresh(r: MediaRouter) {
        _cast.value = r.routes
            .filter { !it.isDefault && it.matchesSelector(selector) && it.isEnabled }
            .map { TvDevice("cast:${it.id}", it.name, TvDevice.Kind.CAST, it.description?.toString().orEmpty()) }
    }

    /** Main thread. Returns false when Cast is unavailable (no Play services). */
    fun startCastScan(context: Context): Boolean = runCatching {
        CastContext.getSharedInstance(context.applicationContext)
        val r = MediaRouter.getInstance(context.applicationContext)
        router = r
        r.addCallback(selector, callback, MediaRouter.CALLBACK_FLAG_PERFORM_ACTIVE_SCAN)
        refresh(r)
        true
    }.getOrDefault(false)

    fun stopCastScan() {
        runCatching { router?.removeCallback(callback) }
    }

    /** Selects a Cast device and returns a player once its session is up (main thread). */
    suspend fun connectCast(context: Context, device: TvDevice): CastTvPlayback = withContext(Dispatchers.Main) {
        val castContext = CastContext.getSharedInstance(context.applicationContext)
        val player = CastPlayer(castContext)
        val r = router ?: MediaRouter.getInstance(context.applicationContext)
        val route = r.routes.firstOrNull { "cast:${it.id}" == device.id } ?: throw NetworkError("${device.name} is no longer available")
        if (!player.isCastSessionAvailable) {
            val ready = kotlinx.coroutines.CompletableDeferred<Unit>()
            player.setSessionAvailabilityListener(object : SessionAvailabilityListener {
                override fun onCastSessionAvailable() { ready.complete(Unit) }
                override fun onCastSessionUnavailable() {}
            })
            r.selectRoute(route)
            kotlinx.coroutines.withTimeoutOrNull(20_000) { ready.await() }
                ?: throw NetworkError("${device.name} did not answer. Check it is on the same Wi-Fi")
        }
        CastTvPlayback(player)
    }

    /** SSDP search for DLNA renderers (~3 s). */
    suspend fun findDlnaTvs(context: Context, timeoutMs: Int = 3_000): List<TvDevice> = withContext(Dispatchers.IO) {
        val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? android.net.wifi.WifiManager
        val lock = runCatching { wifi?.createMulticastLock("chaosplay-tv")?.apply { setReferenceCounted(false); acquire() } }.getOrNull()
        val locations = linkedSetOf<String>()
        try {
            DatagramSocket().use { socket ->
                socket.soTimeout = 400
                val msg = TvRules.searchRequest().toByteArray()
                val group = InetAddress.getByName(DlnaRules.SSDP_ADDRESS)
                repeat(2) { socket.send(DatagramPacket(msg, msg.size, group, DlnaRules.SSDP_PORT)) }
                val deadline = System.currentTimeMillis() + timeoutMs
                val buf = ByteArray(4096)
                while (System.currentTimeMillis() < deadline) {
                    val p = DatagramPacket(buf, buf.size)
                    try {
                        socket.receive(p)
                    } catch (e: SocketTimeoutException) {
                        continue
                    }
                    TvRules.parseSearchReply(String(p.data, 0, p.length))?.let { locations += it }
                }
            }
        } catch (_: Exception) {
        } finally {
            runCatching { lock?.release() }
        }
        locations.mapNotNull { loc ->
            runCatching {
                NetHttp.client.newCall(Request.Builder().url(loc).get().build()).execute().use { resp ->
                    TvRules.parseDescription(resp.body?.string().orEmpty(), loc)
                }
            }.getOrNull()?.let { r ->
                TvDevice("dlna:${r.udn.ifBlank { loc }}", r.name, TvDevice.Kind.DLNA, r.manufacturer.ifBlank { "DLNA" }, r)
            }
        }.distinctBy { it.id }
    }
}
