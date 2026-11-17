package com.chaoswares.chaosplay.ui.player

import com.chaoswares.chaosplay.player.AbRepeatState
import com.chaoswares.chaosplay.player.AspectMode
import com.chaoswares.chaosplay.player.AudioRules
import com.chaoswares.chaosplay.player.Bookmark
import com.chaoswares.chaosplay.player.CodecSupport
import com.chaoswares.chaosplay.player.DecoderMode
import com.chaoswares.chaosplay.player.GestureRules
import com.chaoswares.chaosplay.player.OrientationMode
import com.chaoswares.chaosplay.player.PlayQueue
import com.chaoswares.chaosplay.player.PlaybackRules
import com.chaoswares.chaosplay.player.PlayerPrefs
import com.chaoswares.chaosplay.player.SleepTimer
import com.chaoswares.chaosplay.player.TimeFormat
import com.chaoswares.chaosplay.player.VideoTransform
import com.chaoswares.chaosplay.subtitles.Cue
import com.chaoswares.chaosplay.subtitles.LanguageNames
import com.chaoswares.chaosplay.subtitles.SubtitleEncoding
import com.chaoswares.chaosplay.subtitles.SubtitleStyle
import com.chaoswares.chaosplay.subtitles.SubtitleTrack

/** One selectable audio track, flattened from the player's track groups. */
data class AudioTrackOption(
    val id: String,
    val languageTag: String,
    val label: String
) {
    val displayName: String
        get() = if (languageTag.isBlank()) label else LanguageNames.display(languageTag)
}

/**
 * A subtitle file the app renders itself: a sidecar next to a local video,
 * a file picked by the user, a WebDAV sidecar or a Jellyfin external
 * subtitle. Rendering these in-app is what makes delay, speed, encoding,
 * style and a second simultaneous subtitle possible (spec 4).
 */
data class ExternalSubtitle(
    val key: String,
    val label: String,
    val location: String,
    val languageTag: String = "",
    val cues: List<Cue>? = null,
    val error: String? = null,
    val loading: Boolean = false
)

/** "Play on TV" session shown as a remote control over the player. */
data class TvUi(
    val deviceName: String,
    val kindLabel: String,
    val connecting: Boolean = true,
    val playing: Boolean = false,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val state: String = "",
    val error: String? = null
)

/** Playback state exposed to the player UI (spec section 4.2). */
data class PlayerUiState(
    val sessionId: Int = 0,
    val isPlaying: Boolean = false,
    val isBuffering: Boolean = true,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val bufferedMs: Long = 0L,
    val speed: Float = PlaybackRules.DEFAULT_SPEED,
    val controlsVisible: Boolean = true,
    val showRemaining: Boolean = false,
    val aspectMode: AspectMode = AspectMode.FIT,
    val transform: VideoTransform = VideoTransform(),
    val decoderMode: DecoderMode = DecoderMode.HW,
    val videoMimeType: String = "",
    val hardwareDecodable: Boolean? = null,
    val activeCodecName: String? = null,
    val videoWidth: Int = 0,
    val videoHeight: Int = 0,
    val videoBitrate: Int = 0,
    val firstFrameRendered: Boolean = false,
    val abRepeat: AbRepeatState = AbRepeatState(),
    val locked: Boolean = false,
    val lockProgress: Int = 0,
    val errorMessage: String? = null,
    val title: String = "",
    val currentUri: String = "",
    val sourceLabel: String = "This device",
    val queue: PlayQueue = PlayQueue(),
    val prefs: PlayerPrefs = PlayerPrefs(),
    // ---- subtitles ----
    val subtitleTracks: List<SubtitleTrack> = emptyList(),
    val selectedSubtitleId: String? = null,
    val subtitlesOff: Boolean = false,
    val externalSubtitles: List<ExternalSubtitle> = emptyList(),
    /** Key of the external subtitle shown as the main line, null when an embedded track or off. */
    val primaryExternalKey: String? = null,
    /** Second subtitle shown at the same time (spec 4 "Multi-subtitle"). */
    val secondaryExternalKey: String? = null,
    val subtitleDelayMs: Long = 0L,
    val subtitleSpeed: Double = 1.0,
    val subtitleEncoding: SubtitleEncoding = SubtitleEncoding.AUTO,
    val subtitleStyle: SubtitleStyle = SubtitleStyle(),
    val embeddedCueText: String = "",
    val primaryCueText: String = "",
    val secondaryCueText: String = "",
    // ---- audio ----
    val audioTracks: List<AudioTrackOption> = emptyList(),
    val selectedAudioId: String? = null,
    val boostLevel: Float = 1f,
    val eqPreset: AudioRules.EqPreset = AudioRules.EqPreset.FLAT,
    val effectsAvailable: Boolean = true,
    /** Audio delay in ms; positive plays the sound later (spec 6). */
    val audioDelayMs: Long = 0L,
    val levelling: Boolean = false,
    val levellingAvailable: Boolean = false,
    /** Decoder actually used for audio, e.g. "c2.android.aac.decoder" or "ffmpeg-ac3". */
    val audioDecoder: String = "",
    val audioMime: String = "",
    // ---- extras ----
    val sleepRemainingMs: Long? = null,
    val bookmarks: List<Bookmark> = emptyList(),
    val orientation: OrientationMode = OrientationMode.AUTO,
    val holdSpeedActive: Boolean = false,
    val lastGestureSkip: GestureRules.Gesture? = null,
    /** Accumulated double-tap skip shown as an expanding ripple ("+30 s"). */
    val skip: com.chaoswares.chaosplay.player.SkipAccumulator.Skip? = null,
    /** Bumped on a centre double-tap so the play/pause pulse animates once. */
    val pulse: Int = 0,
    /** Applied window brightness (0..1), null = follow the system. */
    val brightnessLevel: Float? = null,
    val brightnessVisible: Boolean = false,
    val volumeLevel: Float? = null,
    val seekLabel: String? = null,
    val seekPreviewMs: Long? = null,
    val speedLabel: String? = null,
    val zoomLabel: String? = null,
    val message: String? = null,
    val tv: TvUi? = null,
    val tvDevices: List<com.chaoswares.chaosplay.playback.TvDevice> = emptyList(),
    val tvScanning: Boolean = false,
    val castAvailable: Boolean = false
) {
    val progressFraction: Float
        get() = if (durationMs > 0L) (positionMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f) else 0f

    val timeLabels: Pair<String, String>
        get() = TimeFormat.seekBarLabels(durationMs, positionMs)

    val sleepLabel: String get() = SleepTimer.label(sleepRemainingMs)

    /** Subtitle text to draw: an external primary wins over an embedded track. */
    val mainSubtitleText: String
        get() = when {
            subtitlesOff -> ""
            primaryExternalKey != null -> primaryCueText
            else -> embeddedCueText
        }

    val seekStepLabel: String get() = PlayerPrefs.seekStepLabel(prefs.seekStepMs)

    val decoderDetail: String
        get() {
            val codec = if (videoMimeType.isBlank()) "video" else CodecSupport.codecLabel(videoMimeType)
            return when {
                hardwareDecodable == false -> "no hardware decoder → $codec decoded on CPU"
                decoderMode == DecoderMode.SW -> "$codec on the platform codec, compatibility tuning"
                decoderMode == DecoderMode.HW_PLUS -> "$codec on the hardware codec, throughput tuning"
                else -> "$codec on the hardware codec, platform defaults"
            }
        }
}
