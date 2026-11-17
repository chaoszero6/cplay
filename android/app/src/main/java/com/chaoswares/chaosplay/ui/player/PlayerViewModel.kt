package com.chaoswares.chaosplay.ui.player

import android.app.Application
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.media.AudioManager
import android.net.Uri
import androidx.annotation.OptIn
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.VideoSize
import androidx.media3.common.text.CueGroup
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.util.Util
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.MediaSession
import com.chaoswares.chaosplay.MainActivity
import com.chaoswares.chaosplay.data.MediaStoreScanner
import com.chaoswares.chaosplay.data.ResumeCodec
import com.chaoswares.chaosplay.data.SettingsStore
import com.chaoswares.chaosplay.network.JellyfinRules
import com.chaoswares.chaosplay.network.NetHttp
import com.chaoswares.chaosplay.network.NetworkRepository
import com.chaoswares.chaosplay.network.StreamKind
import com.chaoswares.chaosplay.network.StreamRules
import com.chaoswares.chaosplay.playback.PlaybackDiagnostics
import com.chaoswares.chaosplay.playback.SessionHolder
import com.chaoswares.chaosplay.player.AspectMode
import com.chaoswares.chaosplay.player.AudioRules
import com.chaoswares.chaosplay.player.Bookmark
import com.chaoswares.chaosplay.player.BookmarkRules
import com.chaoswares.chaosplay.player.CodecSupport
import com.chaoswares.chaosplay.player.DecoderMode
import com.chaoswares.chaosplay.player.DecoderRules
import com.chaoswares.chaosplay.player.GestureRules
import com.chaoswares.chaosplay.player.LibraryRules
import com.chaoswares.chaosplay.player.LockRules
import com.chaoswares.chaosplay.player.OrientationMode
import com.chaoswares.chaosplay.player.PlayQueue
import com.chaoswares.chaosplay.player.PlaybackErrors
import com.chaoswares.chaosplay.player.PlaybackRules
import com.chaoswares.chaosplay.player.PlayerPrefs
import com.chaoswares.chaosplay.player.QueueItem
import com.chaoswares.chaosplay.player.RepeatMode
import com.chaoswares.chaosplay.player.SleepTimer
import com.chaoswares.chaosplay.player.TimeFormat
import com.chaoswares.chaosplay.player.WatchEntry
import com.chaoswares.chaosplay.subtitles.LanguageNames
import com.chaoswares.chaosplay.subtitles.SubtitleChoices
import com.chaoswares.chaosplay.subtitles.SubtitleEncoding
import com.chaoswares.chaosplay.subtitles.SubtitleSelector
import com.chaoswares.chaosplay.subtitles.SubtitleStyle
import com.chaoswares.chaosplay.subtitles.SubtitleTimeline
import com.chaoswares.chaosplay.subtitles.SubtitleTrack
import com.chaoswares.chaosplay.util.AppScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Player view model: ONE instance per playback session.
 *
 * The black-screen bug came from this class being scoped to the activity:
 * the second time a video was opened the same instance came back with its
 * player already stopped and `initialised == true`, so `initialise()`
 * returned early and nothing was ever prepared. The player screen now gets
 * a fresh view model store per session (see PlayerSessionHost), this class
 * releases everything when that store is cleared, and [start] is safe to
 * call again with a different queue.
 */
@OptIn(UnstableApi::class)
class PlayerViewModel(app: Application) : AndroidViewModel(app) {

    private val settings = SettingsStore(app)
    private val scanner = MediaStoreScanner(app)
    private val network = NetworkRepository.get(app)
    private val audioManager = app.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val subtitleLoader = SubtitleLoader(app)
    val seekPreviews = SeekPreviewLoader(app)

    /** The playable location (stream URL for Jellyfin keys) used for seek previews. */
    @Volatile private var previewSource: String = ""

    /** Seek preview frame for [positionMs] of the current item, or null (time-only preview). */
    suspend fun previewFrame(positionMs: Long): android.graphics.Bitmap? =
        seekPreviews.frameAt(previewSource, positionMs, _state.value.durationMs)

    private val sessionId = PlaybackDiagnostics.nextSession()

    private val _state = MutableStateFlow(PlayerUiState(sessionId = sessionId))
    val state: StateFlow<PlayerUiState> = _state.asStateFlow()

    /** The bound ExoPlayer; a flow so the surface re-binds after a decoder rebuild. */
    private val _player = MutableStateFlow<ExoPlayer?>(null)
    val player: StateFlow<ExoPlayer?> = _player.asStateFlow()

    private var mediaSession: MediaSession? = null
    private var effects: AudioEffects? = null
    private var gestureSettings = GestureRules.GestureSettings()

    private var hideControlsJob: Job? = null
    private var progressJob: Job? = null
    private var sleepJob: Job? = null
    private var loadJob: Job? = null
    private var periodicSaveAt = 0L

    private var started = false
    private var released = false
    private var decoderAttempts = 0
    private var transcoding = false
    private var readyHandled = false
    private var pendingStartMs = 0L
    private var currentPath: String = ""
    private var jellyfin: NetworkRepository.JellyfinPlayback? = null
    private var lastJellyfinReport = 0L
    private var choices: Map<String, String> = emptyMap()
    private var holdPreviousSpeed: Float? = null
    private var twoFingerBaseSpeed: Float? = null
    private var lastVolumeWrite = 0L

    init {
        viewModelScope.launch {
            _state.collect { s ->
                PlaybackDiagnostics.publish(
                    PlaybackDiagnostics.Snapshot(
                        active = !released,
                        title = s.title,
                        uri = s.currentUri,
                        isPlaying = s.isPlaying,
                        positionMs = s.positionMs,
                        durationMs = s.durationMs,
                        firstFrameRendered = s.firstFrameRendered,
                        videoWidth = s.videoWidth,
                        videoHeight = s.videoHeight,
                        error = s.errorMessage,
                        subtitleText = s.mainSubtitleText,
                        queueIndex = s.queue.index,
                        queueSize = s.queue.size,
                        sessionId = sessionId,
                        speed = s.speed,
                        audioTracks = s.audioTracks.size,
                        subtitleTracks = s.subtitleTracks.size + s.externalSubtitles.size,
                        controlsVisible = s.controlsVisible,
                        tvDevice = s.tv?.deviceName.orEmpty(),
                        tvState = s.tv?.let { t -> t.error ?: if (t.connecting) "CONNECTING" else t.state }.orEmpty(),
                        indicator = when {
                            s.volumeLevel != null && s.brightnessVisible -> "both"
                            s.volumeLevel != null -> "volume"
                            s.brightnessVisible -> "brightness"
                            else -> ""
                        },
                        locked = s.locked,
                        audioDecoder = s.audioDecoder,
                        audioMime = s.audioMime,
                        audioDelayMs = s.audioDelayMs,
                        levelling = s.levelling
                    )
                )
            }
        }
    }

    // ------------------------------------------------------------------
    // Session start
    // ------------------------------------------------------------------

    /**
     * Starts playback of [queue]. Idempotent for the same queue (a
     * recomposition or rotation calls it again); a different queue replaces
     * the current one in this session.
     */
    fun start(queue: PlayQueue) {
        if (queue.isEmpty) return
        if (started && _state.value.queue.items == queue.items && !released) return
        started = true
        released = false
        _state.update { it.copy(queue = queue) }

        viewModelScope.launch {
            val prefs = PlayerPrefs.decode(settings.playerPrefsRaw.first())
            gestureSettings = prefs.gestures
            val decoder = runCatching { DecoderMode.valueOf(settings.decoderMode.first()) }.getOrDefault(DecoderMode.HW)
            val style = SubtitleStyle.decode(settings.subtitleStyleRaw.first())
            val encoding = runCatching { SubtitleEncoding.valueOf(settings.subtitleEncoding.first()) }
                .getOrDefault(SubtitleEncoding.AUTO)
            val eq = runCatching { AudioRules.EqPreset.valueOf(settings.eqPreset.first()) }
                .getOrDefault(AudioRules.EqPreset.FLAT)
            choices = SubtitleChoices.decode(settings.subtitleChoicesRaw.first())
            _state.update {
                it.copy(
                    prefs = prefs,
                    decoderMode = decoder,
                    speed = prefs.defaultSpeed,
                    levelling = prefs.volumeLevelling,
                    subtitleStyle = style,
                    subtitleEncoding = encoding,
                    eqPreset = eq
                )
            }
            buildPlayer(decoder)
            loadCurrent(startAtMs = null)
            if (prefs.backgroundPlay) SessionHolder.connectService(getApplication())
        }
        startProgressUpdates()
        scheduleControlsHide()
    }

    /** Builds an ExoPlayer with the renderer flags for [mode], plus its session and audio effects. */
    private fun buildPlayer(mode: DecoderMode) {
        releasePlayer()
        val app = getApplication<Application>()
        val renderers = ChaosRenderersFactory(app, mode, DecoderRules.tuningFor(mode))
        rendererFactory = renderers
        // Files, content://, http(s) with credentials, and SMB/FTP/SFTP/NFS shares
        val dataSources = com.chaoswares.chaosplay.network.chaosDataSourceFactory(app)
        val audioSessionId = Util.generateAudioSessionIdV21(app)
        val exo = ExoPlayer.Builder(app, renderers)
            .setMediaSourceFactory(DefaultMediaSourceFactory(dataSources))
            .setAudioAttributes(
                AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(),
                true
            )
            .setHandleAudioBecomingNoisy(true)
            .setSeekBackIncrementMs(_state.value.prefs.seekStepMs)
            .setSeekForwardIncrementMs(_state.value.prefs.seekStepMs)
            .build()
        exo.setAudioSessionId(audioSessionId)
        exo.setPlaybackSpeed(_state.value.speed)
        exo.addListener(listener)
        renderers.audioSink?.delayMs = _state.value.audioDelayMs
        exo.addAnalyticsListener(object : androidx.media3.exoplayer.analytics.AnalyticsListener {
            override fun onAudioDecoderInitialized(
                eventTime: androidx.media3.exoplayer.analytics.AnalyticsListener.EventTime,
                decoderName: String,
                initializedTimestampMs: Long,
                initializationDurationMs: Long
            ) {
                _state.update { it.copy(audioDecoder = decoderName) }
            }

            override fun onAudioInputFormatChanged(
                eventTime: androidx.media3.exoplayer.analytics.AnalyticsListener.EventTime,
                format: Format,
                decoderReuseEvaluation: androidx.media3.exoplayer.DecoderReuseEvaluation?
            ) {
                _state.update { it.copy(audioMime = format.sampleMimeType.orEmpty()) }
            }
        })
        exo.trackSelectionParameters = exo.trackSelectionParameters.buildUpon()
            .setPreferredTextLanguage(null)
            .build()

        effects = AudioEffects(audioSessionId).also {
            it.setBoost(_state.value.boostLevel)
            it.setPreset(_state.value.eqPreset)
            it.setLevelling(_state.value.levelling)
        }
        _state.update {
            it.copy(effectsAvailable = effects?.available == true, levellingAvailable = effects?.levellerAvailable == true)
        }

        viewModelScope.launch {
            val audioLang = settings.preferredAudioLanguage.first()
            if (audioLang.isNotBlank()) {
                exo.trackSelectionParameters = exo.trackSelectionParameters.buildUpon()
                    .setPreferredAudioLanguage(audioLang).build()
            }
        }

        // MediaSession: lock-screen, notification, Bluetooth and PiP controls (7.1)
        val activityIntent = PendingIntent.getActivity(
            app, 0,
            Intent(app, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        mediaSession = runCatching {
            MediaSession.Builder(app, exo)
                .setId("chaosplay-$sessionId-${System.nanoTime()}")
                .setSessionActivity(activityIntent)
                .build()
        }.getOrNull()
        SessionHolder.session = mediaSession
        _player.value = exo
    }

    private var rendererFactory: ChaosRenderersFactory? = null

    /** Loads the queue's current item, resolving network locations first. */
    private fun loadCurrent(startAtMs: Long?) {
        val item = _state.value.queue.current ?: return
        loadJob?.cancel()
        reportJellyfinStop()
        readyHandled = false
        transcoding = false
        decoderAttempts = 0
        jellyfin = null
        _state.update {
            it.copy(
                title = item.title.ifBlank { StreamRules.displayName(item.uri) },
                currentUri = item.uri,
                errorMessage = null,
                isBuffering = true,
                firstFrameRendered = false,
                positionMs = 0L,
                durationMs = item.durationMs,
                abRepeat = com.chaoswares.chaosplay.player.AbRepeatState(),
                subtitleTracks = emptyList(),
                audioTracks = emptyList(),
                externalSubtitles = emptyList(),
                primaryExternalKey = null,
                secondaryExternalKey = null,
                embeddedCueText = "",
                primaryCueText = "",
                secondaryCueText = "",
                selectedSubtitleId = null,
                selectedAudioId = null,
                subtitleDelayMs = 0L,
                subtitleSpeed = 1.0,
                transform = it.transform.reset(),
                sourceLabel = sourceLabelFor(item.uri),
                videoWidth = 0,
                videoHeight = 0
            )
        }
        loadJob = viewModelScope.launch {
            val exo = _player.value ?: return@launch
            try {
                val resolved = resolveLocation(item)
                previewSource = resolved
                val resumeMs = when {
                    startAtMs != null -> startAtMs
                    !_state.value.prefs.resumeEnabled -> 0L
                    else -> savedPositionFor(item)
                }
                pendingStartMs = resumeMs
                val mediaItem = MediaItem.Builder()
                    .setUri(Uri.parse(resolved))
                    .setMediaId(item.uri)
                    .setMediaMetadata(MediaMetadata.Builder().setTitle(_state.value.title).build())
                    .apply {
                        when (StreamRules.kindOf(resolved)) {
                            StreamKind.HLS -> setMimeType(MimeTypes.APPLICATION_M3U8)
                            StreamKind.DASH -> setMimeType(MimeTypes.APPLICATION_MPD)
                            StreamKind.RTSP -> setMimeType(MimeTypes.APPLICATION_RTSP)
                            StreamKind.PROGRESSIVE -> Unit
                        }
                    }
                    .build()
                exo.setMediaItem(mediaItem, resumeMs.coerceAtLeast(0L))
                exo.prepare()
                exo.playWhenReady = true
                loadBookmarks()
                discoverExternalSubtitles(item)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(errorMessage = e.message ?: "This item could not be opened", isBuffering = false) }
            }
        }
    }

    private fun sourceLabelFor(uri: String): String = when {
        JellyfinRules.isItemKey(uri) -> "Jellyfin"
        com.chaoswares.chaosplay.network.RemoteUris.isRemote(uri) ->
            when (uri.substringBefore("://")) {
                com.chaoswares.chaosplay.network.RemoteUris.SMB -> "SMB share"
                com.chaoswares.chaosplay.network.RemoteUris.FTP -> "FTP"
                com.chaoswares.chaosplay.network.RemoteUris.SFTP -> "SFTP"
                else -> "NFS"
            }
        StreamRules.isNetworkUrl(uri) -> "Network · " + JellyfinRules.hostOf(uri)
        else -> "This device"
    }

    /** jellyfin:// keys become a stream URL here, at play time, so no token is ever stored. */
    private suspend fun resolveLocation(item: QueueItem): String {
        if (JellyfinRules.isItemKey(item.uri)) {
            val pb = network.jellyfinResolve(item.uri)
            jellyfin = pb
            if (item.title.isBlank()) _state.update { it.copy(title = pb.title) }
            _state.update { it.copy(sourceLabel = "Jellyfin · ${pb.profile.name}") }
            return pb.streamUrl
        }
        if (StreamRules.isNetworkUrl(item.uri)) {
            network.ensureCredentials()
            return item.uri
        }
        if (com.chaoswares.chaosplay.network.RemoteUris.isRemote(item.uri)) return item.uri
        // Local: look up the MediaStore row for title, codec probe and sidecars
        val local = runCatching { scanner.itemForUri(item.uri) }.getOrNull()
        currentPath = local?.path.orEmpty()
        if (local != null) {
            _state.update {
                it.copy(
                    title = item.title.ifBlank { local.title },
                    videoMimeType = CodecSupport.normaliseMime(local.mimeType)
                )
            }
        }
        return item.uri
    }

    private suspend fun savedPositionFor(item: QueueItem): Long {
        // "Start over" from a server detail sheet
        if (item.resumeHintMs < 0L) return 0L
        val entries = ResumeCodec.decode(settings.resumeRaw.first())
        val local = entries.firstOrNull { it.uri == item.uri }
        val saved = local?.positionMs ?: 0L
        val hint = jellyfin?.resumeMs?.takeIf { it > 0 } ?: item.resumeHintMs
        // Prefer whichever is further along: the server may know about
        // progress made in another Jellyfin client.
        val candidate = maxOf(saved, hint)
        val duration = local?.durationMs ?: item.durationMs
        return LibraryRules.startPositionMs(candidate, duration)
    }

    // ------------------------------------------------------------------
    // Player events
    // ------------------------------------------------------------------

    private val listener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            _state.update { it.copy(isPlaying = isPlaying) }
            reportJellyfinProgress(force = true)
            if (!isPlaying) saveProgress()
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            val exo = _player.value ?: return
            when (playbackState) {
                Player.STATE_BUFFERING -> _state.update { it.copy(isBuffering = true) }
                Player.STATE_READY -> onReady(exo)
                Player.STATE_ENDED -> onEnded()
                else -> Unit
            }
        }

        override fun onRenderedFirstFrame() {
            _state.update { it.copy(firstFrameRendered = true) }
        }

        override fun onVideoSizeChanged(videoSize: VideoSize) {
            if (videoSize.width <= 0 || videoSize.height <= 0) return
            val w = (videoSize.width * videoSize.pixelWidthHeightRatio).toInt()
            val h = videoSize.height
            val first = _state.value.videoWidth == 0
            _state.update { it.copy(videoWidth = w, videoHeight = h) }
            if (first) {
                val mode = OrientationMode.forVideo(w, h, _state.value.prefs.landscapeForWideVideo)
                _state.update { it.copy(orientation = mode) }
            }
        }

        override fun onTracksChanged(tracks: Tracks) {
            _state.update {
                it.copy(
                    subtitleTracks = buildSubtitleTracks(),
                    audioTracks = buildAudioTracks(),
                    selectedAudioId = activeAudioId() ?: it.selectedAudioId,
                    selectedSubtitleId = if (it.primaryExternalKey == null) activeSubtitleId() else null
                )
            }
        }

        override fun onCues(cueGroup: CueGroup) {
            val text = cueGroup.cues.mapNotNull { it.text?.toString() }.joinToString("\n")
            _state.update { it.copy(embeddedCueText = text) }
        }

        override fun onPlayerError(error: PlaybackException) {
            handleError(error)
        }
    }

    private fun onReady(exo: ExoPlayer) {
        val duration = exo.duration.takeIf { it != C.TIME_UNSET }?.coerceAtLeast(0L) ?: 0L
        val format = firstVideoFormat(exo)
        _state.update {
            it.copy(
                isBuffering = false,
                durationMs = duration,
                videoMimeType = format?.let { f -> CodecSupport.normaliseMime(f.sampleMimeType ?: f.codecs) }
                    ?: it.videoMimeType,
                videoBitrate = format?.bitrate?.takeIf { b -> b > 0 } ?: 0
            )
        }
        if (!readyHandled) {
            readyHandled = true
            decoderAttempts = 0
            probeHardwareCodec(_state.value.videoMimeType)
            captureActiveCodec()
            restoreSubtitleChoice()
            reportJellyfin(NetworkRepository.Report.START)
        }
    }

    private fun onEnded() {
        _state.update { it.copy(isPlaying = false) }
        saveProgress(finished = true)
        reportJellyfinStop()
        val next = _state.value.queue.onEnded(autoAdvance = _state.value.prefs.autoPlayNext)
        when {
            next == null -> _state.update { it.copy(controlsVisible = true) }
            next.index == _state.value.queue.index && next.repeat == RepeatMode.ONE -> {
                _player.value?.seekTo(0)
                _player.value?.play()
            }
            else -> {
                _state.update { it.copy(queue = next) }
                loadCurrent(startAtMs = 0L)
            }
        }
    }

    private fun handleError(error: PlaybackException) {
        if (released) return
        val http = (error.cause as? HttpDataSource.InvalidResponseCodeException)?.responseCode
        // Share errors carry a written-for-people message (wrong password, share not found...)
        val shareMessage = generateSequence(error.cause) { it.cause }
            .firstOrNull { it is com.chaoswares.chaosplay.network.NetworkError }?.message
        val recovery = PlaybackErrors.recoveryFor(
            code = error.errorCode,
            isJellyfin = jellyfin != null,
            alreadyTranscoding = transcoding,
            decoderAttempts = decoderAttempts
        )
        val exo = _player.value
        val at = exo?.currentPosition?.coerceAtLeast(0L) ?: 0L
        when (recovery) {
            PlaybackErrors.Recovery.SERVER_TRANSCODE -> {
                val pb = jellyfin ?: return
                transcoding = true
                exo?.setMediaItem(
                    MediaItem.Builder().setUri(pb.transcodeUrl).setMimeType(MimeTypes.APPLICATION_M3U8)
                        .setMediaId(_state.value.currentUri)
                        .setMediaMetadata(MediaMetadata.Builder().setTitle(_state.value.title).build())
                        .build(),
                    at
                )
                exo?.prepare()
                exo?.play()
                flash("Direct play failed, the server is converting this video")
            }
            PlaybackErrors.Recovery.NEXT_DECODER -> {
                val next = DecoderRules.nextModeOnError(
                    requested = _state.value.decoderMode,
                    hardwareSupported = _state.value.hardwareDecodable != false,
                    previousAttempts = decoderAttempts
                )
                if (next != null) {
                    decoderAttempts++
                    _state.update { it.copy(decoderMode = next) }
                    buildPlayer(next)
                    loadCurrent(startAtMs = at)
                } else {
                    _state.update { it.copy(errorMessage = PlaybackErrors.messageFor(error.errorCode, http), isBuffering = false) }
                }
            }
            PlaybackErrors.Recovery.NONE -> _state.update {
                it.copy(errorMessage = shareMessage ?: PlaybackErrors.messageFor(error.errorCode, http), isBuffering = false)
            }
        }
    }

    /** Retry after an error (network blips): reload at the last position. */
    fun retry() {
        val at = _state.value.positionMs
        _state.update { it.copy(errorMessage = null) }
        loadCurrent(startAtMs = at)
    }

    private fun firstVideoFormat(exo: ExoPlayer): Format? {
        for (group in exo.currentTracks.groups) {
            if (group.type == C.TRACK_TYPE_VIDEO && group.length > 0) return group.getTrackFormat(0)
        }
        return null
    }

    private fun captureActiveCodec() {
        viewModelScope.launch {
            repeat(20) {
                val name = rendererFactory?.videoRenderer?.activeCodecName
                if (name != null) {
                    _state.update { it.copy(activeCodecName = name) }
                    return@launch
                }
                delay(100L)
            }
        }
    }

    private fun probeHardwareCodec(mime: String) {
        if (mime.isBlank()) return
        viewModelScope.launch {
            val supported = withContext(Dispatchers.Default) { CodecSupport.isHardwareDecodable(mime) }
            _state.update { it.copy(hardwareDecodable = supported) }
        }
    }

    // ------------------------------------------------------------------
    // Tracks: embedded subtitles and audio
    // ------------------------------------------------------------------

    private fun buildSubtitleTracks(): List<SubtitleTrack> {
        val exo = _player.value ?: return emptyList()
        val tracks = mutableListOf<SubtitleTrack>()
        var index = 0
        for (group in exo.currentTracks.groups) {
            if (group.type != C.TRACK_TYPE_TEXT) continue
            for (i in 0 until group.length) {
                val format = group.getTrackFormat(i)
                val lang = format.language.orEmpty()
                val label = format.label?.takeIf { it.isNotBlank() }
                tracks += SubtitleTrack(
                    id = "text-$index",
                    languageTag = lang,
                    displayName = (label ?: LanguageNames.display(lang)) + " (Embedded)",
                    isEmbedded = true
                )
                index++
            }
        }
        return tracks
    }

    private fun buildAudioTracks(): List<AudioTrackOption> {
        val exo = _player.value ?: return emptyList()
        val options = mutableListOf<AudioTrackOption>()
        var index = 0
        for (group in exo.currentTracks.groups) {
            if (group.type != C.TRACK_TYPE_AUDIO) continue
            for (i in 0 until group.length) {
                val format = group.getTrackFormat(i)
                val channels = when (format.channelCount) {
                    1 -> "Mono"; 2 -> "Stereo"; 6 -> "5.1"; 8 -> "7.1"; else -> null
                }
                val codec = format.sampleMimeType?.substringAfterLast('/')?.uppercase()
                val detail = listOfNotNull(format.label, codec, channels).joinToString(" ")
                options += AudioTrackOption("audio-$index", format.language.orEmpty(), detail.ifBlank { "Track ${index + 1}" })
                index++
            }
        }
        return options
    }

    private fun activeSubtitleId(): String? = activeId(C.TRACK_TYPE_TEXT, "text-")
    private fun activeAudioId(): String? = activeId(C.TRACK_TYPE_AUDIO, "audio-")

    private fun activeId(type: Int, prefix: String): String? {
        val exo = _player.value ?: return null
        var index = 0
        for (group in exo.currentTracks.groups) {
            if (group.type != type) continue
            for (i in 0 until group.length) {
                if (group.isTrackSelected(i)) return "$prefix$index"
                index++
            }
        }
        return null
    }

    private fun overrideTrack(type: Int, prefix: String, id: String): Boolean {
        val exo = _player.value ?: return false
        val wanted = id.removePrefix(prefix).toIntOrNull() ?: return false
        var seen = 0
        for (group in exo.currentTracks.groups) {
            if (group.type != type) continue
            for (i in 0 until group.length) {
                if (seen == wanted) {
                    exo.trackSelectionParameters = exo.trackSelectionParameters.buildUpon()
                        .setTrackTypeDisabled(type, false)
                        .setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, i))
                        .build()
                    return true
                }
                seen++
            }
        }
        return false
    }

    private fun setEmbeddedTextEnabled(enabled: Boolean) {
        val exo = _player.value ?: return
        exo.trackSelectionParameters = exo.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, !enabled).build()
    }

    /** Select an embedded track ("text-N"), or null for Off. Remembered per video (spec 4.3). */
    fun selectSubtitle(id: String?) {
        if (id == null) {
            setEmbeddedTextEnabled(false)
            _state.update {
                it.copy(subtitlesOff = true, selectedSubtitleId = null, primaryExternalKey = null, primaryCueText = "")
            }
            rememberChoice(SubtitleChoices.OFF)
            return
        }
        if (overrideTrack(C.TRACK_TYPE_TEXT, "text-", id)) {
            val track = _state.value.subtitleTracks.firstOrNull { it.id == id }
            _state.update { it.copy(subtitlesOff = false, selectedSubtitleId = id, primaryExternalKey = null, primaryCueText = "") }
            rememberChoice(SubtitleChoices.embeddedKey(id.removePrefix("text-").toIntOrNull() ?: 0, track?.languageTag.orEmpty()))
        }
    }

    /** Long-press on CC: cycle Off -> each track -> Off (spec 4.3 one-tap shortcut). */
    fun cycleSubtitle() {
        val s = _state.value
        val all = s.subtitleTracks.map { it.id } + s.externalSubtitles.map { it.key }
        if (all.isEmpty()) {
            flash("No subtitles for this video")
            return
        }
        val current = when {
            s.subtitlesOff -> null
            s.primaryExternalKey != null -> s.primaryExternalKey
            else -> s.selectedSubtitleId
        }
        val nextIndex = if (current == null) 0 else all.indexOf(current) + 1
        val next = all.getOrNull(nextIndex)
        when {
            next == null -> selectSubtitle(null)
            next.startsWith("text-") -> selectSubtitle(next)
            else -> selectExternal(next)
        }
        val label = when {
            next == null -> "Subtitles off"
            next.startsWith("text-") -> s.subtitleTracks.first { it.id == next }.displayName
            else -> s.externalSubtitles.first { it.key == next }.label
        }
        flash(label)
    }

    fun selectAudio(id: String) {
        if (overrideTrack(C.TRACK_TYPE_AUDIO, "audio-", id)) _state.update { it.copy(selectedAudioId = id) }
    }

    private fun restoreSubtitleChoice() {
        val uri = _state.value.currentUri
        val saved = choices[uri]
        val tracks = _state.value.subtitleTracks
        viewModelScope.launch {
            when {
                saved == SubtitleChoices.OFF -> selectSubtitle(null)
                saved != null && SubtitleChoices.isExternal(saved) -> {
                    // Wait briefly for discovery so the sidecar entry exists
                    repeat(20) {
                        if (_state.value.externalSubtitles.any { e -> e.key == saved.removePrefix("ext:") || e.location == SubtitleChoices.externalLocation(saved) }) return@repeat
                        delay(100)
                    }
                    val loc = SubtitleChoices.externalLocation(saved) ?: return@launch
                    val ext = _state.value.externalSubtitles.firstOrNull { it.location == loc }
                    if (ext != null) selectExternal(ext.key)
                    else if (loc.startsWith("content://")) addExternalSubtitle(Uri.parse(loc), remember = false)
                }
                saved != null && SubtitleChoices.embeddedLanguage(saved) != null -> {
                    val lang = SubtitleChoices.embeddedLanguage(saved).orEmpty()
                    val idx = SubtitleChoices.embeddedIndex(saved) ?: 0
                    val match = tracks.firstOrNull { it.id == "text-$idx" && it.languageTag == lang }
                        ?: tracks.firstOrNull { it.languageTag == lang && lang.isNotBlank() }
                    if (match != null) selectSubtitle(match.id)
                }
                else -> autoSelectSubtitle()
            }
        }
    }

    /** Preferred language + fallback order for videos with no remembered choice (spec 4). */
    private suspend fun autoSelectSubtitle() {
        val preferred = settings.preferredSubtitleLanguage.first()
        val fallbackSetting = settings.subtitleFallbackOrder.first()
        val order = listOf(preferred) + SubtitleSelector.parseFallbackOrder(fallbackSetting)
        val embedded = _state.value.subtitleTracks
        val chosen = order.firstNotNullOfOrNull { lang ->
            embedded.firstOrNull { it.languageTag.lowercase().startsWith(lang.lowercase()) && lang.isNotBlank() }
        }
        if (chosen != null) {
            if (activeSubtitleId() != chosen.id) overrideTrack(C.TRACK_TYPE_TEXT, "text-", chosen.id)
            _state.update { it.copy(selectedSubtitleId = chosen.id, subtitlesOff = false) }
            return
        }
        // A sidecar with a matching language (or an untagged exact match) comes next
        delay(300)
        val ext = _state.value.externalSubtitles
        val sidecar = order.firstNotNullOfOrNull { lang -> ext.firstOrNull { it.languageTag.equals(lang, true) } }
            ?: ext.firstOrNull { it.languageTag.isBlank() }
        if (sidecar != null) {
            selectExternal(sidecar.key, remember = false)
        } else if (embedded.isNotEmpty() && fallbackSetting.trim().endsWith("off").not()) {
            val first = SubtitleSelector.autoSelect(embedded, order)
            if (first != null) selectSubtitle(first.id)
        } else {
            _state.update { it.copy(selectedSubtitleId = activeSubtitleId()) }
        }
    }

    private fun rememberChoice(key: String) {
        val uri = _state.value.currentUri
        if (uri.isBlank()) return
        choices = SubtitleChoices.remember(choices, uri, key)
        viewModelScope.launch {
            settings.updateSubtitleChoices { raw -> SubtitleChoices.encode(SubtitleChoices.remember(SubtitleChoices.decode(raw), uri, key)) }
        }
    }

    // ------------------------------------------------------------------
    // External subtitles (files we render ourselves)
    // ------------------------------------------------------------------

    private suspend fun discoverExternalSubtitles(item: QueueItem) {
        val found = mutableListOf<ExternalSubtitle>()
        if (currentPath.isNotBlank()) found += subtitleLoader.sidecarsFor(currentPath)
        item.subtitleUris.forEach { url ->
            val name = StreamRules.displayName(url)
            val tag = SubtitleSelector.extractLanguageTag(
                item.title.substringBeforeLast('.').lowercase(),
                name.substringBeforeLast('.').lowercase()
            ).orEmpty()
            found += ExternalSubtitle("url:$url", subtitleLoader.labelFor(name, tag), url, tag)
        }
        jellyfin?.subtitleUrls?.forEach { (label, url) ->
            if (found.none { it.location == url }) found += ExternalSubtitle("url:$url", "$label (Server)", url)
        }
        if (found.isNotEmpty()) {
            _state.update { s -> s.copy(externalSubtitles = (s.externalSubtitles + found).distinctBy { it.key }) }
        }
    }

    /** "Add subtitle file…" from the system picker (spec 4.3). */
    fun addExternalSubtitle(uri: Uri, remember: Boolean = true) {
        val app = getApplication<Application>()
        runCatching {
            app.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val name = subtitleLoader.displayName(uri)
        val ext = ExternalSubtitle("pick:$uri", subtitleLoader.labelFor(name, ""), uri.toString())
        _state.update { s -> s.copy(externalSubtitles = (s.externalSubtitles.filterNot { it.key == ext.key } + ext)) }
        selectExternal(ext.key, remember = remember, nameHint = name)
    }

    /** Shows an external subtitle as the main line (loads it on first use). */
    fun selectExternal(key: String, remember: Boolean = true, nameHint: String? = null) {
        val ext = _state.value.externalSubtitles.firstOrNull { it.key == key } ?: return
        setEmbeddedTextEnabled(false)
        _state.update {
            it.copy(
                primaryExternalKey = key,
                subtitlesOff = false,
                selectedSubtitleId = null,
                secondaryExternalKey = it.secondaryExternalKey.takeIf { s -> s != key }
            )
        }
        if (remember) rememberChoice(SubtitleChoices.externalKey(ext.location))
        ensureLoaded(key, nameHint)
    }

    /** Second subtitle shown above the first (spec 4 "Multi-subtitle"); null removes it. */
    fun selectSecondary(key: String?) {
        _state.update { it.copy(secondaryExternalKey = key, secondaryCueText = "") }
        if (key != null) ensureLoaded(key)
    }

    private fun ensureLoaded(key: String, nameHint: String? = null) {
        val ext = _state.value.externalSubtitles.firstOrNull { it.key == key } ?: return
        if (ext.cues != null || ext.loading) return
        updateExternal(key) { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            try {
                val cues = subtitleLoader.load(ext.location, _state.value.subtitleEncoding, nameHint ?: ext.label.substringAfter('(').substringBefore(')').ifBlank { ext.location })
                updateExternal(key) { it.copy(cues = cues, loading = false, error = if (cues.isEmpty()) "No subtitle lines found in this file" else null) }
                if (cues.isEmpty()) flash("No subtitle lines found in ${ext.label}")
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                updateExternal(key) { it.copy(loading = false, error = e.message ?: "Could not load") }
                flash("Could not load subtitle: ${e.message ?: "error"}")
            }
        }
    }

    private fun updateExternal(key: String, transform: (ExternalSubtitle) -> ExternalSubtitle) {
        _state.update { s -> s.copy(externalSubtitles = s.externalSubtitles.map { if (it.key == key) transform(it) else it }) }
    }

    fun setSubtitleDelay(delayMs: Long) {
        _state.update { it.copy(subtitleDelayMs = SubtitleTimeline.clampDelay(delayMs)) }
        refreshExternalCues()
    }

    fun nudgeSubtitleDelay(deltaMs: Long) = setSubtitleDelay(_state.value.subtitleDelayMs + deltaMs)

    fun setSubtitleSpeed(speed: Double) {
        _state.update { it.copy(subtitleSpeed = SubtitleTimeline.clampSpeed(speed)) }
        refreshExternalCues()
    }

    /** Manual encoding choice: reloads external files with the new charset (spec 4). */
    fun setSubtitleEncoding(encoding: SubtitleEncoding) {
        _state.update { s -> s.copy(subtitleEncoding = encoding, externalSubtitles = s.externalSubtitles.map { it.copy(cues = null, error = null) }) }
        viewModelScope.launch { settings.setSubtitleEncoding(encoding.name) }
        _state.value.primaryExternalKey?.let { ensureLoaded(it) }
        _state.value.secondaryExternalKey?.let { ensureLoaded(it) }
    }

    fun setSubtitleStyle(style: SubtitleStyle) {
        val clamped = SubtitleStyle.clamp(style)
        _state.update { it.copy(subtitleStyle = clamped) }
        viewModelScope.launch { settings.setSubtitleStyleRaw(SubtitleStyle.encode(clamped)) }
    }

    /** Subtitle scroll gesture: jump to the next/previous line (spec 4.2). */
    fun jumpSubtitleLine(forward: Boolean) {
        val s = _state.value
        val cues = s.externalSubtitles.firstOrNull { it.key == s.primaryExternalKey }?.cues ?: return
        val target = if (forward) {
            SubtitleTimeline.nextLineStart(cues, s.positionMs, s.subtitleDelayMs, s.subtitleSpeed)
        } else {
            SubtitleTimeline.previousLineStart(cues, s.positionMs, s.subtitleDelayMs, s.subtitleSpeed)
        }
        if (target != null) seekTo(target)
    }

    private fun refreshExternalCues() {
        val s = _state.value
        val position = s.positionMs
        fun textFor(key: String?): String {
            if (key == null) return ""
            val cues = s.externalSubtitles.firstOrNull { it.key == key }?.cues ?: return ""
            return SubtitleTimeline.textAt(cues, position, s.subtitleDelayMs, s.subtitleSpeed)
        }
        val primary = textFor(s.primaryExternalKey)
        val secondary = textFor(s.secondaryExternalKey)
        if (primary != s.primaryCueText || secondary != s.secondaryCueText) {
            _state.update { it.copy(primaryCueText = primary, secondaryCueText = secondary) }
        }
    }

    // ------------------------------------------------------------------
    // Progress loop
    // ------------------------------------------------------------------

    private fun startProgressUpdates() {
        progressJob?.cancel()
        progressJob = viewModelScope.launch {
            while (!released) {
                val exo = _player.value
                if (exo != null) {
                    val position = exo.currentPosition.coerceAtLeast(0L)
                    val duration = exo.duration.takeIf { it != C.TIME_UNSET }?.coerceAtLeast(0L) ?: _state.value.durationMs
                    val loopTarget = _state.value.abRepeat.loopTargetMs(position)
                    if (loopTarget != null) {
                        exo.seekTo(loopTarget)
                    } else if (_state.value.seekPreviewMs == null) {
                        _state.update {
                            it.copy(positionMs = position, durationMs = duration, bufferedMs = exo.bufferedPosition.coerceAtLeast(0L))
                        }
                    }
                    refreshExternalCues()
                    reportJellyfinProgress(force = false)
                    val now = System.currentTimeMillis()
                    if (exo.isPlaying && now - periodicSaveAt > 15_000L) {
                        periodicSaveAt = now
                        saveProgress()
                    }
                }
                val externalActive = _state.value.primaryExternalKey != null || _state.value.secondaryExternalKey != null
                delay(if (externalActive) 100L else 250L)
            }
        }
    }

    // ------------------------------------------------------------------
    // Transport and queue
    // ------------------------------------------------------------------

    fun playPause() {
        val exo = _player.value ?: return
        if (exo.playbackState == Player.STATE_ENDED) {
            exo.seekTo(0)
            exo.play()
            return
        }
        if (exo.isPlaying) exo.pause() else exo.play()
    }

    fun pause() {
        _player.value?.pause()
    }

    fun seekTo(positionMs: Long) {
        val duration = _state.value.durationMs
        val clamped = if (duration > 0) PlaybackRules.clampSeek(positionMs, duration) else positionMs.coerceAtLeast(0L)
        _player.value?.seekTo(clamped)
        _state.update { it.copy(positionMs = clamped) }
        refreshExternalCues()
    }

    fun seekBy(deltaMs: Long) {
        val duration = _state.value.durationMs
        val target = _state.value.positionMs + deltaMs
        seekTo(if (duration > 0) PlaybackRules.clampSeek(target, duration) else target.coerceAtLeast(0L))
    }

    fun skipBack() = seekBy(-_state.value.prefs.seekStepMs)
    fun skipForward() = seekBy(_state.value.prefs.seekStepMs)

    /** "Next" in the bottom bar: next queue item. */
    fun next() {
        val next = _state.value.queue.next() ?: run {
            flash("End of the queue")
            return
        }
        saveProgress()
        _state.update { it.copy(queue = next) }
        loadCurrent(startAtMs = null)
    }

    /** "Previous": restart the current item if past 3 s, else go to the previous one. */
    fun previous() {
        if (_state.value.positionMs > 3_000L || !_state.value.queue.hasPrevious) {
            seekTo(0)
            return
        }
        val prev = _state.value.queue.previous() ?: return
        saveProgress()
        _state.update { it.copy(queue = prev) }
        loadCurrent(startAtMs = null)
    }

    fun playQueueIndex(index: Int) {
        if (index == _state.value.queue.index) return
        saveProgress()
        _state.update { it.copy(queue = it.queue.jumpTo(index)) }
        loadCurrent(startAtMs = null)
    }

    fun removeFromQueue(index: Int) {
        val q = _state.value.queue
        if (index == q.index) {
            val updated = q.removeAt(index)
            if (updated.isEmpty) return
            _state.update { it.copy(queue = updated) }
            loadCurrent(startAtMs = null)
        } else {
            _state.update { it.copy(queue = q.removeAt(index)) }
        }
    }

    fun moveInQueue(from: Int, to: Int) = _state.update { it.copy(queue = it.queue.move(from, to)) }

    fun playNext(item: QueueItem) {
        _state.update { it.copy(queue = it.queue.playNext(item)) }
        flash("Plays next: ${item.title}")
    }

    fun cycleRepeat() {
        val next = _state.value.queue.repeat.next()
        _state.update { it.copy(queue = it.queue.withRepeat(next)) }
        flash(next.label)
    }

    fun toggleShuffle() {
        val q = _state.value.queue
        _state.update { it.copy(queue = q.withShuffle(!q.shuffle, System.currentTimeMillis())) }
        flash(if (!q.shuffle) "Shuffle on" else "Shuffle off")
    }

    fun setSpeed(speed: Float) {
        val clamped = PlaybackRules.clampSpeed(speed)
        _player.value?.setPlaybackSpeed(clamped)
        _state.update { it.copy(speed = clamped) }
    }

    fun toggleTimeDisplay() = _state.update { it.copy(showRemaining = !it.showRemaining) }

    fun setAspect(mode: AspectMode) = _state.update { it.copy(aspectMode = mode) }

    fun cycleDecoder() = setDecoder(_state.value.decoderMode.next())

    /** Rebuilds the player with the new renderer flags at the current position. */
    fun setDecoder(mode: DecoderMode) {
        val at = _player.value?.currentPosition?.coerceAtLeast(0L) ?: _state.value.positionMs
        decoderAttempts = 0
        _state.update { it.copy(decoderMode = mode, activeCodecName = null) }
        viewModelScope.launch { settings.setDecoderMode(mode.name) }
        if (!started) return
        buildPlayer(mode)
        loadCurrent(startAtMs = at)
        flash("Decoder: ${mode.label}")
    }

    fun rotateVideo() = _state.update { it.copy(transform = it.transform.rotate()) }
    fun toggleMirror() = _state.update { it.copy(transform = it.transform.copy(mirrored = !it.transform.mirrored)) }
    fun toggleFlip() = _state.update { it.copy(transform = it.transform.copy(flipped = !it.transform.flipped)) }
    fun setZoom(zoom: Float) = _state.update { it.copy(transform = it.transform.copy(zoom = com.chaoswares.chaosplay.player.VideoTransform.clampZoom(zoom))) }
    fun resetTransform() = _state.update { it.copy(transform = it.transform.reset(), zoomLabel = null) }
    fun panBy(dx: Float, dy: Float) = _state.update { it.copy(transform = it.transform.panBy(dx, dy)) }

    fun cycleOrientation() {
        val next = _state.value.orientation.next()
        _state.update { it.copy(orientation = next) }
        flash("Screen: ${next.label}")
    }

    fun pressAbRepeat() {
        val next = _state.value.abRepeat.press(_state.value.positionMs)
        _state.update { it.copy(abRepeat = next) }
        flash(
            when {
                next.isActive -> "Repeating ${TimeFormat.format(next.aMs!!)} – ${TimeFormat.format(next.bMs!!)}"
                next.isArmed -> "A set at ${TimeFormat.format(next.aMs!!)}. Press again to set B"
                else -> "A-B repeat off"
            }
        )
    }

    // ---- audio ----

    fun setBoost(level: Float) {
        val clamped = level.coerceIn(1f, AudioRules.MAX_BOOST)
        effects?.setBoost(clamped)
        _state.update { it.copy(boostLevel = clamped) }
    }

    fun setEqPreset(preset: AudioRules.EqPreset) {
        effects?.setPreset(preset)
        _state.update { it.copy(eqPreset = preset) }
        viewModelScope.launch { settings.setEqPreset(preset.name) }
    }

    /** Audio delay (spec 6); applied to the live audio clock, no restart. */
    fun setAudioDelay(delayMs: Long) {
        val d = com.chaoswares.chaosplay.network.AvSync.clamp(delayMs)
        rendererFactory?.audioSink?.delayMs = d
        _state.update { it.copy(audioDelayMs = d) }
    }

    fun nudgeAudioDelay(deltaMs: Long) = setAudioDelay(_state.value.audioDelayMs + deltaMs)

    /** Volume levelling on/off (spec 6 "Audio normalization"). */
    fun setLevelling(on: Boolean) {
        val applied = effects?.setLevelling(on) ?: false
        if (on && !applied) {
            flash("Volume levelling needs Android 9 or newer")
            return
        }
        _state.update { it.copy(levelling = on) }
    }

    // ---- Play on TV (DLNA renderers and Google Cast) ----

    private var tvPlayback: com.chaoswares.chaosplay.playback.TvPlayback? = null
    private var tvPollJob: Job? = null
    private var tvScanJob: Job? = null
    private var dlnaTvs: List<com.chaoswares.chaosplay.playback.TvDevice> = emptyList()

    /** Looks for TVs on the Wi-Fi: DLNA renderers (SSDP) and Cast devices. */
    fun startTvScan() {
        val app = getApplication<Application>()
        val cast = com.chaoswares.chaosplay.playback.TvDiscovery.startCastScan(app)
        _state.update { it.copy(tvScanning = true, castAvailable = cast) }
        tvScanJob?.cancel()
        tvScanJob = viewModelScope.launch {
            launch {
                com.chaoswares.chaosplay.playback.TvDiscovery.castDevices.collect { castList ->
                    _state.update { it.copy(tvDevices = dlnaTvs + castList) }
                }
            }
            dlnaTvs = com.chaoswares.chaosplay.playback.TvDiscovery.findDlnaTvs(app)
            _state.update {
                it.copy(tvDevices = dlnaTvs + com.chaoswares.chaosplay.playback.TvDiscovery.castDevices.value, tvScanning = false)
            }
        }
    }

    fun stopTvScan() {
        com.chaoswares.chaosplay.playback.TvDiscovery.stopCastScan()
        _state.update { it.copy(tvScanning = false) }
    }

    /** Sends the current video to [device] from the current position. */
    fun playOnTv(device: com.chaoswares.chaosplay.playback.TvDevice) {
        val item = _state.value.queue.current ?: return
        val app = getApplication<Application>()
        val startAt = _player.value?.currentPosition ?: _state.value.positionMs
        _player.value?.pause()
        stopTvScan()
        _state.update { it.copy(tv = TvUi(device.name, device.kind.label), controlsVisible = false) }
        viewModelScope.launch {
            try {
                val (url, mime) = tvSourceFor(item)
                val playback = when (device.kind) {
                    com.chaoswares.chaosplay.playback.TvDevice.Kind.DLNA ->
                        com.chaoswares.chaosplay.playback.DlnaTvPlayback(device.renderer ?: error("no renderer"))
                    com.chaoswares.chaosplay.playback.TvDevice.Kind.CAST ->
                        com.chaoswares.chaosplay.playback.TvDiscovery.connectCast(app, device)
                }
                tvPlayback = playback
                playback.start(url, _state.value.title, mime, _state.value.durationMs, startAt)
                _state.update { s -> s.copy(tv = s.tv?.copy(connecting = false, positionMs = startAt, durationMs = s.durationMs)) }
                pollTv()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { s -> s.copy(tv = s.tv?.copy(connecting = false, error = e.message ?: "The TV did not start playing")) }
            }
        }
    }

    /** The URL the TV fetches: direct when it can, through the phone's server when it cannot. */
    private fun tvSourceFor(item: QueueItem): Pair<String, String> {
        val app = getApplication<Application>()
        val name = _state.value.title.ifBlank { item.uri.substringAfterLast('/') }
        val named = if ('.' in name.takeLast(6)) name else "$name.mp4"
        val pb = jellyfin
        return when {
            pb != null -> pb.streamUrl to "video/mp4"
            StreamRules.isNetworkUrl(item.uri) && NetHttp.headersFor(item.uri).isEmpty() -> {
                val mime = when (StreamRules.kindOf(item.uri)) {
                    StreamKind.HLS -> "application/x-mpegURL"
                    StreamKind.DASH -> "application/dash+xml"
                    else -> com.chaoswares.chaosplay.network.LocalMediaServer.mimeOf(StreamRules.displayName(item.uri))
                }
                item.uri to mime
            }
            else -> {
                val location = if (com.chaoswares.chaosplay.network.RemoteUris.isRemote(item.uri) || item.uri.startsWith("content://") || StreamRules.isNetworkUrl(item.uri)) {
                    item.uri
                } else currentPath.ifBlank { item.uri }
                com.chaoswares.chaosplay.network.LocalMediaServer.share(app, location, named) to
                    com.chaoswares.chaosplay.network.LocalMediaServer.mimeOf(named)
            }
        }
    }

    private fun pollTv() {
        tvPollJob?.cancel()
        tvPollJob = viewModelScope.launch {
            var failures = 0
            while (true) {
                delay(1_000)
                val p = tvPlayback ?: break
                val status = runCatching { p.status() }.getOrNull()
                if (status == null) {
                    if (++failures >= 5) {
                        _state.update { s -> s.copy(tv = s.tv?.copy(error = "Lost contact with the TV")) }
                    }
                    continue
                }
                failures = 0
                _state.update { s ->
                    s.copy(
                        tv = s.tv?.copy(
                            playing = status.playing,
                            positionMs = status.positionMs,
                            durationMs = if (status.durationMs > 0) status.durationMs else s.durationMs,
                            state = status.state,
                            error = null
                        )
                    )
                }
            }
        }
    }

    fun tvPlayPause() {
        val p = tvPlayback ?: return
        val playing = _state.value.tv?.playing == true
        _state.update { s -> s.copy(tv = s.tv?.copy(playing = !playing)) }
        viewModelScope.launch { runCatching { if (playing) p.pause() else p.play() }.onFailure { flash(it.message ?: "TV error") } }
    }

    fun tvSeek(positionMs: Long) {
        val p = tvPlayback ?: return
        val target = positionMs.coerceAtLeast(0L)
        _state.update { s -> s.copy(tv = s.tv?.copy(positionMs = target)) }
        viewModelScope.launch { runCatching { p.seek(target) }.onFailure { flash(it.message ?: "The TV could not seek") } }
    }

    fun tvSkip(deltaMs: Long) = tvSeek((_state.value.tv?.positionMs ?: 0L) + deltaMs)

    /** Stops the TV and continues on the phone at the TV's position (paused). */
    fun stopTv(resumeOnPhone: Boolean = true) {
        val p = tvPlayback
        val at = _state.value.tv?.positionMs ?: 0L
        tvPlayback = null
        tvPollJob?.cancel()
        AppScope.launch { runCatching { p?.stop() }; com.chaoswares.chaosplay.network.LocalMediaServer.stop() }
        _state.update { it.copy(tv = null, controlsVisible = true) }
        if (resumeOnPhone && at > 0) seekTo(at)
    }

    // ---- bookmarks ----

    private suspend fun loadBookmarks() {
        val all = BookmarkRules.decode(settings.bookmarksRaw.first())
        _state.update { it.copy(bookmarks = BookmarkRules.forUri(all, it.currentUri)) }
    }

    fun addBookmark() {
        val uri = _state.value.currentUri
        val position = _state.value.positionMs
        val bookmark = Bookmark(uri, position, "", System.currentTimeMillis() / 1000)
        viewModelScope.launch {
            settings.updateBookmarks { raw -> BookmarkRules.encode(BookmarkRules.add(BookmarkRules.decode(raw), bookmark)) }
            loadBookmarks()
            flash("Bookmark at ${TimeFormat.format(position)}")
        }
    }

    fun removeBookmark(bookmark: Bookmark) {
        viewModelScope.launch {
            settings.updateBookmarks { raw -> BookmarkRules.encode(BookmarkRules.remove(BookmarkRules.decode(raw), bookmark.uri, bookmark.positionMs)) }
            loadBookmarks()
        }
    }

    fun renameBookmark(bookmark: Bookmark, label: String) {
        viewModelScope.launch {
            settings.updateBookmarks { raw ->
                BookmarkRules.encode(BookmarkRules.rename(BookmarkRules.decode(raw), bookmark.uri, bookmark.positionMs, label))
            }
            loadBookmarks()
        }
    }

    // ---- share / screenshot ----

    fun share(context: Context) {
        val uri = _state.value.currentUri
        if (uri.isBlank()) return
        val intent = if (uri.startsWith("content://")) {
            Intent(Intent.ACTION_SEND).apply {
                type = "video/*"
                putExtra(Intent.EXTRA_STREAM, Uri.parse(uri))
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        } else {
            // Never share a URL with credentials or tokens in it
            if (JellyfinRules.isItemKey(uri)) {
                flash("Jellyfin items can be shared from the Jellyfin app")
                return
            }
            Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, StreamRules.redact(uri))
            }
        }
        runCatching {
            context.startActivity(Intent.createChooser(intent, "Share").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    fun saveScreenshot(bitmap: Bitmap?) {
        if (bitmap == null) {
            flash("Could not capture this frame")
            return
        }
        val title = _state.value.title.substringBeforeLast('.')
        viewModelScope.launch {
            val uri = ScreenshotSaver.save(getApplication(), bitmap, title)
            flash(if (uri != null) "Screenshot saved to Pictures/CPlay" else "Could not save the screenshot")
        }
    }

    // ---- sleep timer ----

    fun startSleepTimer(minutes: Int) {
        sleepJob?.cancel()
        val total = SleepTimer.minutesToMs(minutes)
        if (total <= 0L) {
            _state.update { it.copy(sleepRemainingMs = null) }
            return
        }
        _state.update { it.copy(sleepRemainingMs = total) }
        flash("Pausing in $minutes min")
        sleepJob = viewModelScope.launch {
            var remaining: Long? = total
            while (remaining != null) {
                delay(1_000L)
                val next = SleepTimer.tick(remaining, 1_000L)
                if (next == null) {
                    _player.value?.pause()
                    _state.update { it.copy(sleepRemainingMs = null) }
                    return@launch
                }
                remaining = next
                _state.update { it.copy(sleepRemainingMs = next) }
            }
        }
    }

    fun cancelSleepTimer() {
        sleepJob?.cancel()
        _state.update { it.copy(sleepRemainingMs = null) }
    }

    // ---- background play ----

    fun toggleBackgroundPlay() {
        val enabled = !_state.value.prefs.backgroundPlay
        val prefs = _state.value.prefs.copy(backgroundPlay = enabled)
        _state.update { it.copy(prefs = prefs) }
        viewModelScope.launch { settings.setPlayerPrefsRaw(PlayerPrefs.encode(prefs)) }
        if (enabled) SessionHolder.connectService(getApplication()) else SessionHolder.disconnectService(getApplication())
        flash(if (enabled) "Background play on: keeps playing when you leave the app" else "Background play off")
    }

    /** Activity left the foreground. Without background play, pause (and save). */
    fun onAppBackgrounded(inPip: Boolean) {
        if (inPip) return
        if (!_state.value.prefs.backgroundPlay) {
            _player.value?.pause()
        }
        saveProgress()
    }

    // ------------------------------------------------------------------
    // Gestures
    // ------------------------------------------------------------------

    /** Initial brightness/volume so the first drag starts from the real level. */
    /**
     * Starts a brightness OR volume drag. Only the dragged side's level is
     * set: setting both made the volume indicator (speaker icon) appear on
     * top of the brightness one while dragging on the left.
     */
    fun beginLevelDrag(isVolume: Boolean, currentBrightness: Float) {
        if (isVolume) {
            val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
            val cur = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
            val volume = if (_state.value.boostLevel > 1f) _state.value.boostLevel else cur.toFloat() / max
            _state.update { it.copy(volumeLevel = volume, brightnessVisible = false) }
        } else {
            _state.update {
                it.copy(brightnessLevel = it.brightnessLevel ?: currentBrightness, brightnessVisible = true, volumeLevel = null)
            }
        }
    }

    fun onDrag(dx: Float, dy: Float, x: Float, screenWidth: Float, screenHeight: Float) {
        if (_state.value.locked) return
        endHoldSpeed()
        when (GestureRules.classifyDrag(dx, dy, x, screenWidth, gestureSettings)) {
            GestureRules.Gesture.SEEK -> onSeekDrag(dx, screenWidth)
            GestureRules.Gesture.BRIGHTNESS -> onBrightnessDrag(dy, screenHeight)
            GestureRules.Gesture.VOLUME -> onVolumeDrag(dy, screenHeight)
            else -> Unit
        }
    }

    fun onSeekDrag(dx: Float, screenWidth: Float) {
        if (!gestureSettings.seekEnabled || screenWidth <= 0f || _state.value.durationMs <= 0L) return
        val from = _state.value.seekPreviewMs ?: _state.value.positionMs
        val target = PlaybackRules.seekFromDrag(from, dx / screenWidth, _state.value.durationMs)
        val origin = _state.value.positionMs
        _state.update { it.copy(seekPreviewMs = target, seekLabel = TimeFormat.formatSeekDelta(target - origin, target)) }
    }

    fun onBrightnessDrag(dy: Float, screenHeight: Float) {
        if (!gestureSettings.brightnessEnabled || screenHeight <= 0f) return
        val current = _state.value.brightnessLevel ?: 0.5f
        _state.update { it.copy(brightnessLevel = GestureRules.levelFromDrag(current, dy / screenHeight), brightnessVisible = true) }
    }

    fun onVolumeDrag(dy: Float, screenHeight: Float) {
        if (!gestureSettings.volumeEnabled || screenHeight <= 0f) return
        val current = _state.value.volumeLevel ?: 1f
        val level = GestureRules.volumeFromDrag(current, dy / screenHeight)
        _state.update { it.copy(volumeLevel = level) }
        applyVolume(level)
    }

    /** 0..1 moves the system volume; above 1 pins it at max and boosts (up to 200%). */
    private fun applyVolume(level: Float) {
        val now = System.currentTimeMillis()
        val (fraction, boost) = AudioRules.split(level)
        if (now - lastVolumeWrite >= 40L) {
            lastVolumeWrite = now
            val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            runCatching { audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, AudioRules.streamIndex(fraction, max), 0) }
        }
        effects?.setBoost(boost)
        _state.update { it.copy(boostLevel = boost) }
    }

    fun commitDragSeek() {
        _state.value.seekPreviewMs?.let { seekTo(it) }
        _state.update { it.copy(seekPreviewMs = null, seekLabel = null) }
    }

    fun onPinch(factor: Float) {
        if (_state.value.locked || !gestureSettings.pinchZoomEnabled) return
        val next = _state.value.transform.zoomBy(factor)
        _state.update { it.copy(transform = next, zoomLabel = next.zoomLabel()) }
    }

    fun beginTwoFingerSpeed() {
        twoFingerBaseSpeed = _state.value.speed
    }

    /** Two-finger vertical swipe changes speed (spec 4.2); swipe up is faster. */
    fun onTwoFingerSpeed(totalDy: Float, screenHeight: Float) {
        if (!gestureSettings.twoFingerSpeedEnabled || screenHeight <= 0f) return
        val base = twoFingerBaseSpeed ?: _state.value.speed
        val raw = PlaybackRules.speedFromSwipe(base, -totalDy / screenHeight * 2f)
        val snapped = (Math.round(raw * 20f) / 20f)
        setSpeed(snapped)
        _state.update { it.copy(speedLabel = TimeFormat.formatSpeed(snapped)) }
    }

    /** Long press: temporary 2x while held (spec 4.2). */
    /** Set while a drag/pinch is in progress so a long-press 2x cannot start mid-gesture. */
    var gestureActive: Boolean = false

    /** True when hold-for-2x actually started (for haptics). */
    fun startHoldSpeed(): Boolean {
        if (gestureActive) return false
        if (_state.value.locked || !gestureSettings.longPressSpeedEnabled || holdPreviousSpeed != null) return false
        if (_player.value?.isPlaying != true) return false
        holdPreviousSpeed = _state.value.speed
        _player.value?.setPlaybackSpeed(GestureRules.LONG_PRESS_SPEED)
        _state.update { it.copy(holdSpeedActive = true, speedLabel = "2x ▶▶") }
        return true
    }

    fun endHoldSpeed() {
        val previous = holdPreviousSpeed ?: return
        holdPreviousSpeed = null
        _player.value?.setPlaybackSpeed(previous)
        _state.update { it.copy(holdSpeedActive = false, speedLabel = null) }
    }

    /** Returns what happened so the screen can give matching haptic feedback. */
    fun onDoubleTap(x: Float, screenWidth: Float): GestureRules.Gesture {
        if (_state.value.locked) return GestureRules.Gesture.NONE
        val gesture = GestureRules.classifyDoubleTap(x, screenWidth, gestureSettings)
        when (gesture) {
            GestureRules.Gesture.SKIP_BACK -> { skipBack(); flashGesture(gesture) }
            GestureRules.Gesture.SKIP_FORWARD -> { skipForward(); flashGesture(gesture) }
            else -> {
                playPause()
                _state.update { it.copy(pulse = it.pulse + 1) }
            }
        }
        return gesture
    }

    private var skipClearJob: Job? = null

    private fun flashGesture(gesture: GestureRules.Gesture) {
        val now = System.currentTimeMillis()
        val skip = com.chaoswares.chaosplay.player.SkipAccumulator.next(
            _state.value.skip, gesture == GestureRules.Gesture.SKIP_FORWARD, _state.value.prefs.seekStepMs, now
        )
        _state.update { it.copy(lastGestureSkip = gesture, skip = skip) }
        skipClearJob?.cancel()
        skipClearJob = viewModelScope.launch {
            delay(com.chaoswares.chaosplay.player.SkipAccumulator.WINDOW_MS)
            _state.update { it.copy(lastGestureSkip = null, skip = null) }
        }
    }

    fun clearGestureFeedback() {
        twoFingerBaseSpeed = null
        _state.update {
            it.copy(
                seekLabel = null,
                speedLabel = if (it.holdSpeedActive) it.speedLabel else null,
                zoomLabel = null,
                volumeLevel = null,
                seekPreviewMs = null,
                // Brightness stays applied; only its on-screen indicator hides
                brightnessVisible = false
            )
        }
    }

    // ------------------------------------------------------------------
    // Controls, lock, messages
    // ------------------------------------------------------------------

    fun toggleControls() {
        if (_state.value.locked) return
        val visible = !_state.value.controlsVisible
        _state.update { it.copy(controlsVisible = visible) }
        if (visible) scheduleControlsHide() else hideControlsJob?.cancel()
    }

    fun keepControls() {
        hideControlsJob?.cancel()
        _state.update { it.copy(controlsVisible = true) }
    }

    fun hideControls() {
        hideControlsJob?.cancel()
        _state.update { it.copy(controlsVisible = false) }
    }

    fun scheduleControlsHide() {
        hideControlsJob?.cancel()
        hideControlsJob = viewModelScope.launch {
            delay(_state.value.prefs.autoHideMs)
            // Never hide while paused at the very start or on an error
            if (_state.value.errorMessage == null) _state.update { it.copy(controlsVisible = false) }
        }
    }

    fun toggleLock() {
        val locked = !_state.value.locked
        _state.update { it.copy(locked = locked, lockProgress = 0, controlsVisible = !locked) }
        if (locked) flash("Locked. Touch the four corners clockwise to unlock")
    }

    enum class CornerResult { NONE, PROGRESS, RESET, UNLOCKED }

    fun onCornerTouch(x: Float, y: Float, screenWidth: Float, screenHeight: Float): CornerResult {
        if (!_state.value.locked || screenWidth <= 0f || screenHeight <= 0f) return CornerResult.NONE
        val before = _state.value.lockProgress
        val progress = LockRules.CornerProgress(before).touch(x / screenWidth, y / screenHeight)
        return if (progress.isComplete) {
            _state.update { it.copy(locked = false, lockProgress = 0, controlsVisible = true) }
            scheduleControlsHide()
            flash("Unlocked")
            CornerResult.UNLOCKED
        } else {
            _state.update { it.copy(lockProgress = progress.nextIndex) }
            when {
                progress.nextIndex > before -> CornerResult.PROGRESS
                before > 0 -> CornerResult.RESET
                else -> CornerResult.NONE
            }
        }
    }

    private var messageJob: Job? = null

    fun flash(message: String) {
        messageJob?.cancel()
        _state.update { it.copy(message = message) }
        messageJob = viewModelScope.launch {
            delay(2_200L)
            _state.update { it.copy(message = null) }
        }
    }

    // ------------------------------------------------------------------
    // Jellyfin reporting
    // ------------------------------------------------------------------

    private fun reportJellyfin(kind: NetworkRepository.Report) {
        val pb = jellyfin ?: return
        val position = _player.value?.currentPosition ?: _state.value.positionMs
        val paused = _player.value?.isPlaying != true
        lastJellyfinReport = System.currentTimeMillis()
        AppScope.launch { network.jellyfinReport(pb, kind, position, paused) }
    }

    private fun reportJellyfinProgress(force: Boolean) {
        if (jellyfin == null || !readyHandled) return
        if (JellyfinRules.shouldReportProgress(lastJellyfinReport, System.currentTimeMillis(), force)) {
            reportJellyfin(NetworkRepository.Report.PROGRESS)
        }
    }

    private fun reportJellyfinStop() {
        if (jellyfin != null && readyHandled) reportJellyfin(NetworkRepository.Report.STOP)
    }

    // ------------------------------------------------------------------
    // Persistence and teardown
    // ------------------------------------------------------------------

    /**
     * Persists the current position on the process scope, so it completes
     * even if this view model is cleared a moment later.
     */
    fun saveProgress(finished: Boolean = false): Job? {
        val exo = _player.value ?: return null
        val uri = _state.value.currentUri
        val duration = exo.duration.takeIf { it != C.TIME_UNSET }?.coerceAtLeast(0L) ?: 0L
        if (duration <= 0L || uri.isBlank() || !readyHandled) return null
        val position = if (finished) duration else exo.currentPosition.coerceAtLeast(0L)
        return AppScope.launch {
            runCatching {
                settings.updateResume { raw ->
                    val entries = ResumeCodec.decode(raw)
                    val existing = entries.firstOrNull { it.uri == uri }
                    ResumeCodec.encode(
                        ResumeCodec.upsert(
                            entries,
                            WatchEntry(
                                uri = uri,
                                positionMs = position,
                                durationMs = duration,
                                lastPlayedAtSec = System.currentTimeMillis() / 1000L,
                                playCount = (existing?.playCount ?: 0) + if (finished) 1 else 0
                            )
                        )
                    )
                }
            }
        }
    }

    /**
     * Back pressed: stop sound on this frame, save (bounded wait), report to
     * Jellyfin, and release the player. After this the session is over; a
     * new playback gets a new view model.
     */
    suspend fun exitAndSave(maxWaitMs: Long = 400L) {
        _player.value?.pause()
        val job = saveProgress()
        reportJellyfinStop()
        if (job != null) withTimeoutOrNull(maxWaitMs) { job.join() }
        release()
    }

    /** Idempotent teardown. Called from exitAndSave, onDispose and onCleared. */
    fun release() {
        if (released) return
        if (tvPlayback != null) stopTv(resumeOnPhone = false)
        stopTvScan()
        saveProgress()
        released = true
        progressJob?.cancel()
        hideControlsJob?.cancel()
        sleepJob?.cancel()
        loadJob?.cancel()
        releasePlayer()
        seekPreviews.release()
        SessionHolder.disconnectService(getApplication())
        PlaybackDiagnostics.clear(sessionId)
    }

    private fun releasePlayer() {
        mediaSession?.let {
            if (SessionHolder.session === it) SessionHolder.session = null
            runCatching { it.release() }
        }
        mediaSession = null
        effects?.release()
        effects = null
        _player.value?.let {
            it.removeListener(listener)
            runCatching { it.release() }
        }
        _player.value = null
    }

    override fun onCleared() {
        release()
        super.onCleared()
    }
}
