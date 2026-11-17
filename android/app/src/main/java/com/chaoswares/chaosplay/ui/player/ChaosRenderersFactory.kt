package com.chaoswares.chaosplay.ui.player

import android.content.Context
import android.os.Handler
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.ForwardingAudioSink
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.video.VideoRendererEventListener
import com.chaoswares.chaosplay.player.DecoderMode
import com.chaoswares.chaosplay.player.DecoderTuning

/**
 * Renderers factory that swaps Media3's stock video renderer for
 * [TuningMediaCodecVideoRenderer], so the selected [DecoderMode] reaches the
 * codec. Audio and text renderers stay stock.
 *
 * Written against the Media3 1.3.1 signature, verified with javap:
 *   protected void buildVideoRenderers(Context, int extensionRendererMode,
 *       MediaCodecSelector, boolean enableDecoderFallback, Handler,
 *       VideoRendererEventListener, long allowedVideoJoiningTimeMs,
 *       ArrayList<Renderer> out)
 */
@UnstableApi
class ChaosRenderersFactory(
    context: Context,
    private val mode: DecoderMode,
    private val tuning: DecoderTuning
) : DefaultRenderersFactory(context) {

    /** The renderer instance built here, so the view model can read its codec. */
    var videoRenderer: TuningMediaCodecVideoRenderer? = null
        private set

    init {
        // Fall back to the next decoder when the preferred one fails to
        // initialise: this is what lets SW rescue a file when HW cannot.
        setEnableDecoderFallback(true)
        // FFmpeg audio decoder (AC3, E-AC3, DTS, TrueHD, ...) is bundled:
        // platform decoders are tried first, FFmpeg covers what the phone
        // lacks. Video stays on MediaCodec (see buildVideoRenderers).
        setExtensionRendererMode(EXTENSION_RENDERER_MODE_ON)
    }

    /** The audio sink, so the view model can set the audio delay live. */
    var audioSink: DelayAudioSink? = null
        private set

    override fun buildAudioSink(
        context: Context,
        enableFloatOutput: Boolean,
        enableAudioTrackPlaybackParams: Boolean
    ): AudioSink? {
        val base = super.buildAudioSink(context, enableFloatOutput, enableAudioTrackPlaybackParams) ?: return null
        return DelayAudioSink(base).also { audioSink = it }
    }

    override fun buildVideoRenderers(
        context: Context,
        extensionRendererMode: Int,
        mediaCodecSelector: MediaCodecSelector,
        enableDecoderFallback: Boolean,
        eventHandler: Handler,
        eventListener: VideoRendererEventListener,
        allowedVideoJoiningTimeMs: Long,
        out: ArrayList<Renderer>
    ) {
        // Extension renderers stay off (see init), so only the platform
        // renderer is built, with the mode's tuning attached.
        val renderer = TuningMediaCodecVideoRenderer(
            context,
            mode,
            tuning,
            mediaCodecSelector,
            allowedVideoJoiningTimeMs,
            enableDecoderFallback,
            eventHandler,
            eventListener,
            MAX_DROPPED_VIDEO_FRAME_COUNT_TO_NOTIFY
        )
        videoRenderer = renderer
        out.add(renderer)
    }
}

/**
 * Audio delay (spec 6 "Audio delay / sync adjustment"). The player clock
 * is the audio position; reporting it [delayMs] ahead makes video frames
 * show earlier relative to the sound, i.e. the sound plays later.
 */
@UnstableApi
class DelayAudioSink(sink: AudioSink) : ForwardingAudioSink(sink) {
    @Volatile var delayMs: Long = 0L

    override fun getCurrentPositionUs(sourceEnded: Boolean): Long =
        com.chaoswares.chaosplay.network.AvSync.clockUs(
            super.getCurrentPositionUs(sourceEnded),
            delayMs,
            AudioSink.CURRENT_POSITION_NOT_SET
        )
}
