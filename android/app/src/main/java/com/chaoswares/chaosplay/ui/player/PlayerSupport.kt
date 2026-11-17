package com.chaoswares.chaosplay.ui.player

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.media.audiofx.DynamicsProcessing
import android.media.audiofx.Equalizer
import android.media.audiofx.LoudnessEnhancer
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.util.LruCache
import com.chaoswares.chaosplay.network.NetworkRepository
import com.chaoswares.chaosplay.network.StreamRules
import com.chaoswares.chaosplay.player.AudioRules
import com.chaoswares.chaosplay.player.SeekPreviewRules
import com.chaoswares.chaosplay.subtitles.Cue
import com.chaoswares.chaosplay.subtitles.LanguageNames
import com.chaoswares.chaosplay.subtitles.SubtitleEncoding
import com.chaoswares.chaosplay.subtitles.SubtitleEncodingDetector
import com.chaoswares.chaosplay.subtitles.SubtitleFormat
import com.chaoswares.chaosplay.subtitles.SubtitleParser
import com.chaoswares.chaosplay.subtitles.SubtitleSelector
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Volume boost (LoudnessEnhancer) and equalizer on the player's own audio
 * session (spec 6). Some devices and emulators refuse audio effects; every
 * call is guarded and [available] reports whether they work.
 */
class AudioEffects(sessionId: Int) {

    private val enhancer: LoudnessEnhancer? = runCatching { LoudnessEnhancer(sessionId) }.getOrNull()
    private val equalizer: Equalizer? = runCatching { Equalizer(0, sessionId) }.getOrNull()

    /**
     * Volume levelling (spec 6 "Audio normalization"): a compressor that
     * lifts quiet dialogue and tames loud scenes, then a limiter. Uses
     * DynamicsProcessing, Android 9+; older phones report it unavailable.
     */
    private val leveller: DynamicsProcessing? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) runCatching { buildLeveller(sessionId) }.getOrNull() else null

    val available: Boolean get() = enhancer != null || equalizer != null
    val equalizerAvailable: Boolean get() = equalizer != null
    val levellerAvailable: Boolean get() = leveller != null

    fun setLevelling(on: Boolean): Boolean {
        val l = leveller ?: return false
        return runCatching { l.enabled = on; l.enabled == on }.getOrDefault(false)
    }

    @androidx.annotation.RequiresApi(Build.VERSION_CODES.P)
    private fun buildLeveller(sessionId: Int): DynamicsProcessing {
        val p = AudioRules.LEVELLER
        val config = DynamicsProcessing.Config.Builder(
            DynamicsProcessing.VARIANT_FAVOR_FREQUENCY_RESOLUTION,
            /* channelCount = */ 2,
            /* preEqInUse = */ false, 0,
            /* mbcInUse = */ true, 1,
            /* postEqInUse = */ false, 0,
            /* limiterInUse = */ true
        ).build()
        val effect = DynamicsProcessing(0, sessionId, config)
        val band = DynamicsProcessing.MbcBand(
            true, 20_000f, p.attackMs, p.releaseMs, p.ratio, p.thresholdDb, p.kneeDb,
            /* noiseGateThreshold = */ -90f, /* expanderRatio = */ 1f, /* preGain = */ 0f, /* postGain = */ p.makeUpGainDb
        )
        effect.setMbcBandAllChannelsTo(0, band)
        effect.setLimiterAllChannelsTo(
            DynamicsProcessing.Limiter(true, true, 0, 1f, 60f, p.limiterRatio, p.limiterThresholdDb, 0f)
        )
        effect.enabled = false
        return effect
    }

    fun setBoost(level: Float) {
        val e = enhancer ?: return
        val gain = AudioRules.boostGainMb(level)
        runCatching {
            e.setTargetGain(gain)
            e.enabled = gain > 0
        }
    }

    fun setPreset(preset: AudioRules.EqPreset) {
        val eq = equalizer ?: return
        runCatching {
            if (preset == AudioRules.EqPreset.FLAT) {
                eq.enabled = false
                return
            }
            val range = eq.bandLevelRange
            val levels = AudioRules.bandLevels(preset, eq.numberOfBands.toInt(), range[0].toInt(), range[1].toInt())
            levels.forEachIndexed { band, level -> eq.setBandLevel(band.toShort(), level.toShort()) }
            eq.enabled = true
        }
    }

    fun release() {
        runCatching { enhancer?.release() }
        runCatching { equalizer?.release() }
        runCatching { leveller?.release() }
    }
}

/**
 * Finds and loads subtitle files the app renders itself.
 */
class SubtitleLoader(private val context: Context) {

    /**
     * Sidecar subtitles next to a local video (spec 4 "Auto-detect subtitle
     * files next to the video"). On Android 11+ other apps' .srt files are
     * only readable with All files access, so this returns nothing there
     * unless the user granted it in Settings.
     */
    suspend fun sidecarsFor(videoPath: String): List<ExternalSubtitle> = withContext(Dispatchers.IO) {
        if (videoPath.isBlank() || !videoPath.startsWith("/")) return@withContext emptyList()
        val video = File(videoPath)
        val dir = video.parentFile ?: return@withContext emptyList()
        val names = runCatching { dir.list()?.toList() }.getOrNull() ?: return@withContext emptyList()
        val videoBase = video.name.substringBeforeLast('.').lowercase()
        SubtitleSelector.rank(video.name, names)
            .filter { it.lowercase().startsWith(videoBase) }
            .map { name ->
                val file = File(dir, name)
                val tag = SubtitleSelector.extractLanguageTag(videoBase, name.substringBeforeLast('.').lowercase()).orEmpty()
                ExternalSubtitle(
                    key = "file:${file.absolutePath}",
                    label = labelFor(name, tag),
                    location = file.absolutePath,
                    languageTag = tag
                )
            }
            .filter { File(it.location).canRead() }
    }

    fun labelFor(fileName: String, languageTag: String): String {
        val lang = if (languageTag.isNotBlank() && LanguageNames.isKnown(languageTag)) LanguageNames.display(languageTag) else null
        return if (lang != null) "$lang ($fileName)" else fileName
    }

    /** Display name for a picked content:// file. */
    fun displayName(uri: Uri): String = runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    }.getOrNull() ?: uri.lastPathSegment.orEmpty()

    /** Reads, decodes (with [encoding]) and parses a subtitle file from any supported location. */
    suspend fun load(location: String, encoding: SubtitleEncoding, nameHint: String = location): List<Cue> =
        withContext(Dispatchers.IO) {
            val bytes = when {
                StreamRules.isNetworkUrl(location) -> NetworkRepository.get(context).download(location)
                com.chaoswares.chaosplay.network.RemoteUris.isRemote(location) -> NetworkRepository.get(context).readShare(location)
                location.startsWith("content://") -> context.contentResolver.openInputStream(Uri.parse(location))
                    ?.use { it.readBytes() } ?: error("Cannot open the subtitle file")
                else -> File(location.removePrefix("file://")).readBytes()
            }
            if (bytes.size > 8 * 1024 * 1024) error("Subtitle file is too large")
            val text = SubtitleEncodingDetector.decode(bytes, encoding)
            val fileName = nameHint.substringAfterLast('/').substringBefore('?')
            val format = SubtitleParser.formatForFileName(fileName)
                ?: if (location.contains("Stream.srt")) SubtitleFormat.SRT else null
            SubtitleParser.parse(text, format)
        }
}

/**
 * Seek-bar thumbnails (spec 4.2 "While dragging: thumbnail preview above
 * the thumb", spec 3 "Preview thumbnails while seeking").
 *
 * Works for local files, http(s) streams (with the same credentials as
 * playback: Nextcloud, WebDAV, Jellyfin, DLNA) and SMB / FTP / SFTP / NFS
 * shares through ranged reads. Frames are decoded off the main thread,
 * per time bucket, and cached; HLS/DASH/RTSP have no single file to seek
 * in, so they show the time only.
 */
class SeekPreviewLoader(private val context: Context) {

    private val cache = LruCache<Long, Bitmap>(32)
    private val mutex = Mutex()
    private var retriever: MediaMetadataRetriever? = null
    private var source: String? = null
    private var shareSource: ShareMediaSource? = null
    @Volatile private var broken: String? = null

    fun supports(location: String): Boolean {
        if (location.isBlank() || location == broken) return false
        if (location.startsWith("content://") || location.startsWith("/")) return true
        if (com.chaoswares.chaosplay.network.RemoteUris.isRemote(location)) return Build.VERSION.SDK_INT >= 23
        if (StreamRules.isNetworkUrl(location)) {
            return StreamRules.kindOf(location) == com.chaoswares.chaosplay.network.StreamKind.PROGRESSIVE &&
                !location.startsWith("rtsp", ignoreCase = true)
        }
        return false
    }

    suspend fun frameAt(location: String, positionMs: Long, durationMs: Long): Bitmap? {
        if (!supports(location)) return null
        val bucket = SeekPreviewRules.bucket(positionMs, durationMs)
        if (source == location) cache.get(bucket)?.let { return it }
        return withContext(Dispatchers.IO) {
            mutex.withLock {
                runCatching {
                    if (source != location) open(location)
                    val r = retriever ?: return@runCatching null
                    val frame = if (Build.VERSION.SDK_INT >= 27) {
                        r.getScaledFrameAtTime(bucket * 1000L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, 320, 180)
                    } else {
                        r.getFrameAtTime(bucket * 1000L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                            ?.let { Bitmap.createScaledBitmap(it, 320, (320f * it.height / it.width.coerceAtLeast(1)).toInt().coerceAtLeast(1), true) }
                    }
                    frame?.also { cache.put(bucket, it) }
                }.onFailure {
                    // A source that cannot be read for previews is not retried on every drag
                    broken = location
                }.getOrNull()
            }
        }
    }

    private fun open(location: String) {
        closeSource()
        cache.evictAll()
        val r = MediaMetadataRetriever()
        when {
            location.startsWith("content://") -> r.setDataSource(context, Uri.parse(location))
            location.startsWith("/") -> r.setDataSource(location)
            com.chaoswares.chaosplay.network.RemoteUris.isRemote(location) && Build.VERSION.SDK_INT >= 23 -> {
                val parsed = com.chaoswares.chaosplay.network.RemoteUris.parse(location) ?: error("bad share uri")
                val login = NetworkRepository.get(context).shareLoginBlocking(parsed.profileId)
                val src = ShareMediaSource(login, parsed.path)
                shareSource = src
                r.setDataSource(src)
            }
            else -> {
                val headers = HashMap(com.chaoswares.chaosplay.network.NetHttp.headersFor(location))
                headers["User-Agent"] = com.chaoswares.chaosplay.network.NetHttp.USER_AGENT
                r.setDataSource(location, headers)
            }
        }
        retriever = r
        source = location
    }

    private fun closeSource() {
        runCatching { retriever?.release() }
        retriever = null
        runCatching { shareSource?.close() }
        shareSource = null
        source = null
    }

    fun release() {
        closeSource()
        cache.evictAll()
    }
}

/**
 * Lets MediaMetadataRetriever read a file on an SMB/FTP/SFTP/NFS share.
 * Keeps one open stream and only reopens when the decoder jumps, so FTP
 * does not reconnect for every small read.
 */
@androidx.annotation.RequiresApi(23)
private class ShareMediaSource(
    private val login: com.chaoswares.chaosplay.network.ShareLogin,
    private val path: String
) : android.media.MediaDataSource() {

    private val fileSize: Long by lazy {
        com.chaoswares.chaosplay.network.RemoteFsPool.withRetry(login) { it.length(path) }
    }
    private var stream: java.io.InputStream? = null
    private var streamPos = -1L

    @Synchronized
    override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
        if (position >= fileSize) return -1
        if (stream == null || streamPos != position) {
            runCatching { stream?.close() }
            stream = com.chaoswares.chaosplay.network.RemoteFsPool.withRetry(login) { it.open(path, position) }
            streamPos = position
        }
        val n = stream!!.read(buffer, offset, size)
        if (n > 0) streamPos += n
        return n
    }

    override fun getSize(): Long = fileSize

    @Synchronized
    override fun close() {
        runCatching { stream?.close() }
        stream = null
    }
}

/** Saves a captured frame to Pictures/CPlay (spec 4.2 overflow "screenshot"). */
object ScreenshotSaver {
    @Suppress("DEPRECATION")
    suspend fun save(context: Context, bitmap: Bitmap, baseName: String): Uri? = withContext(Dispatchers.IO) {
        val safe = baseName.replace(Regex("[^A-Za-z0-9._-]+"), "_").take(60).ifBlank { "frame" }
        val name = "CPlay_${safe}_${System.currentTimeMillis()}.png"
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name)
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            if (Build.VERSION.SDK_INT >= 29) {
                put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/CPlay")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            } else {
                val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), "CPlay")
                dir.mkdirs()
                put(MediaStore.Images.Media.DATA, File(dir, name).absolutePath)
            }
        }
        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return@withContext null
        val ok = runCatching {
            resolver.openOutputStream(uri)?.use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) } ?: false
        }.getOrDefault(false)
        if (!ok) {
            resolver.delete(uri, null, null)
            return@withContext null
        }
        if (Build.VERSION.SDK_INT >= 29) {
            resolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
        }
        uri
    }
}
