package com.chaoswares.chaosplay.ui.player

import android.content.Context
import android.os.Handler
import android.view.Surface
import androidx.media3.common.Format
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.mediacodec.MediaCodecAdapter
import androidx.media3.exoplayer.mediacodec.MediaCodecInfo
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.video.MediaCodecVideoRenderer
import androidx.media3.exoplayer.video.VideoRendererEventListener
import com.chaoswares.chaosplay.player.DecoderMode
import com.chaoswares.chaosplay.player.DecoderTuning
import android.media.MediaCrypto

/**
 * MediaCodec video renderer that makes the decoder modes actually different.
 *
 * Verified against Media3 1.3.1 with javap, the three levers available are:
 *
 *  1. Codec preference — [getDecoderInfos]. HW/HW+ keep Media3's order
 *     (hardware first). SW moves any non-hardware platform decoder
 *     (`c2.android.*`, `OMX.google.*`) to the front, so the file is decoded
 *     in the platform's own software implementation. When a device ships no
 *     software decoder for the format, SW keeps the hardware codec and relies
 *     on lever 3, which is the compatibility behaviour that mode promises.
 *
 *  2. How the codec is driven — [getCodecOperatingRateV23]. This is the only
 *     public hook that reaches MediaCodec: Media3 turns it into
 *     MediaFormat.KEY_OPERATING_RATE. HW leaves it at the platform default,
 *     HW+ asks for double (throughput hint: do not throttle to display
 *     rate), SW caps it at the mode's frame rate so the decoder is paced.
 *
 *  3. Frame dropping — [shouldDropFrame] and [shouldDropBuffersToKeyframe].
 *     HW+ disables dropping entirely: every decoded frame is shown even when
 *     rendering falls behind, which is what makes slow-motion and 24 fps
 *     content look smooth instead of juddery. SW enables all of it so a codec
 *     that cannot keep up cuts frames instead of drifting further behind.
 *
 * The constructor also carries Media3's own `enableDroppedFrameSkipping`,
 * exposed only through `MediaCodecVideoRenderer.Builder`; not used here so the
 * class stays a plain subclass.
 */
@UnstableApi
class TuningMediaCodecVideoRenderer(
    context: Context,
    private val mode: DecoderMode,
    private val tuning: DecoderTuning,
    mediaCodecSelector: MediaCodecSelector,
    allowedJoiningTimeMs: Long,
    enableDecoderFallback: Boolean,
    eventHandler: Handler?,
    eventListener: VideoRendererEventListener?,
    maxDroppedFramesToNotify: Int
) : MediaCodecVideoRenderer(
    context,
    mediaCodecSelector,
    allowedJoiningTimeMs,
    enableDecoderFallback,
    eventHandler,
    eventListener,
    maxDroppedFramesToNotify
) {

    /** Codec the renderer finally initialised, for the decoder badge. */
    @Volatile
    var activeCodecName: String? = null
        private set

    override fun onCodecInitialized(
        name: String,
        configuration: MediaCodecAdapter.Configuration,
        initializedTimestampMs: Long,
        initializationDurationMs: Long
    ) {
        super.onCodecInitialized(
            name, configuration, initializedTimestampMs, initializationDurationMs
        )
        activeCodecName = name
    }

    /** Lever 1: codec preference. SW moves a software decoder to the front. */
    override fun getDecoderInfos(
        mediaCodecSelector: MediaCodecSelector,
        format: Format,
        requiresSecureDecoder: Boolean
    ): List<MediaCodecInfo> {
        val all = super.getDecoderInfos(mediaCodecSelector, format, requiresSecureDecoder)
        if (all.size <= 1 || mode != DecoderMode.SW) return all
        val software = all.filter { it.softwareOnly }
        if (software.isEmpty()) return all
        return software + all.filterNot { it.softwareOnly }
    }

    /**
     * Lever 2: operating rate. This is the value Media3 writes into
     * MediaFormat.KEY_OPERATING_RATE for the codec.
     *
     * Media3 passes CODEC_OPERATING_RATE_UNSET (-1) when it cannot tell, and
     * in that case the mode's own hint is used instead of guessing.
     */
    override fun getCodecOperatingRateV23(
        operatingRate: Float,
        format: Format,
        streamFormats: Array<Format>
    ): Float {
        val base = super.getCodecOperatingRateV23(operatingRate, format, streamFormats)
        val unset = base == CODEC_OPERATING_RATE_UNSET
        return when (mode) {
            // Platform default: let the vendor codec pick its own rate.
            DecoderMode.HW -> base

            // Ask for headroom so the codec does not throttle to the display
            // rate and keeps a deep input queue. Doubling is enough to be a
            // clear hint without asking for an impossible rate.
            DecoderMode.HW_PLUS -> when {
                unset -> tuning.maxFrameRate.toFloat().takeIf { it > 0f } ?: 60f
                else -> base * 2f
            }

            // Pace the decoder at a rate it can sustain.
            DecoderMode.SW -> {
                val cap = tuning.maxFrameRate.toFloat()
                when {
                    unset -> if (cap > 0f) cap else base
                    cap > 0f -> minOf(base, cap)
                    else -> base
                }
            }
        }
    }

    /** Lever 3: HW+ shows every frame. */
    override fun shouldDropFrame(
        frameTimeUs: Long,
        elapsedRealtimeUs: Long,
        isLastFrame: Boolean
    ): Boolean {
        if (!tuning.dropLateFrames) return false
        return super.shouldDropFrame(frameTimeUs, elapsedRealtimeUs, isLastFrame)
    }

    override fun shouldDropOutputBuffer(
        earlyUs: Long,
        elapsedRealtimeUs: Long,
        isLastFrame: Boolean
    ): Boolean {
        if (!tuning.dropLateFrames) return false
        return super.shouldDropOutputBuffer(earlyUs, elapsedRealtimeUs, isLastFrame)
    }

    override fun shouldDropBuffersToKeyframe(
        earlyUs: Long,
        elapsedRealtimeUs: Long,
        isLastFrame: Boolean
    ): Boolean {
        if (!tuning.dropLateFrames) return false
        return super.shouldDropBuffersToKeyframe(earlyUs, elapsedRealtimeUs, isLastFrame)
    }
}
