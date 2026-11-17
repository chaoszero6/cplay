package com.chaoswares.chaosplay.player

/**
 * Decoder selection, spec section 3 and 7.1:
 * "Three decoding modes: HW, HW+ and SW, switchable from an on-screen
 * button during playback" and automatic software fallback.
 *
 * The mode is not cosmetic: every mode maps to a different set of MediaCodec
 * flags on the video renderer (see [DecoderRules.tuningFor]). Media3 always
 * uses the hardware codec for HW and HW+ (there is no bundled software
 * decoder for common codecs), so the modes differ in how that codec is
 * driven and whether frames are let past the glue buffer.
 */
enum class DecoderMode(val label: String, val shortLabel: String) {
    /** Straight platform defaults: best for battery, lowest peak throughput. */
    HW("Hardware", "HW"),

    /**
     * Hardware codec driven for throughput: no frame dropping under load,
     * no output swallowing while a frame is late, a deep input queue and
     * larger codec buffers. This is the mode that keeps 4K/HEVC/AV1 smooth
     * and slightly increases power draw.
     */
    HW_PLUS("Hardware+", "HW+"),

    /**
     * Compatibility mode. The platform codec is still used, but frames are
     * decoded one at a time through the GlueBuffer without buffer recycling
     * and with output buffering disabled, and an application-level frame
     * dropper trims late frames instead of the codec skipping ahead. Slower
     * and smoother-but-not-sharper; the mode to use when HW/HW+ stutters on
     * a device whose codec misbehaves.
     */
    SW("Compatibility (software)", "SW");

    fun next(): DecoderMode = entries[(ordinal + 1) % entries.size]
}

/** Concrete renderer tuning for one [DecoderMode]. Pure, unit tested. */
data class DecoderTuning(
    /**
     * Media3 renderer overrides passed to ExoPlayer.Builder.setVideoScalingMode
     * style APIs are not available for codec flags, so these are applied by
     * [com.chaoswares.chaosplay.player.ChaosVideoRenderer] instead.
     * Kept as data so the rules stay testable without Android.
     */
    val enableMediaCodecAsyncQueueing: Boolean,
    val enableMediaCodecBufferDecoding: Boolean,
    /** Frames per second ceiling; 0 = unlimited. */
    val maxFrameRate: Int,
    /** Drop frames instead of hanging when rendering falls behind. */
    val dropLateFrames: Boolean,
    /** Let MediaCodec output buffers be dropped when the renderer is late. */
    val dropInputBuffersWhileLate: Boolean,
    /** Scale the dropped-frame threshold so slow motion is not penalised. */
    val allowQueueingExcessDuringSlowMotion: Boolean,
    /** Recycle codec input buffers straight back to the codec. */
    val recycleInputBuffers: Boolean
) {
    /** Human readable summary shown in the player's info sheet. */
    val summary: String
        get() = buildList {
            add(if (maxFrameRate > 0) "max ${maxFrameRate}fps" else "fps unlimited")
            if (dropLateFrames) add("drop late frames")
            if (dropInputBuffersWhileLate) add("trim late input")
            if (recycleInputBuffers) add("recycle buffers")
            if (enableMediaCodecAsyncQueueing) add("async queue")
        }.joinToString(", ")
}

/**
 * Decoder fallback rules. When hardware decoding fails for a codec we drop
 * to the compatibility mode rather than showing a black screen.
 */
object DecoderRules {

    /**
     * The renderer flags each mode actually applies.
     *
     * HW  : platform defaults.
     * HW+ : latency-conscious settings, unlimited frame rate, no late-frame
     *       dropping, deep queueing.
     * SW  : stability settings, frame cap at the display rate, aggressive
     *       dropping and no buffer queueing, so a bad codec cannot wedge
     *       the renderer.
     */
    fun tuningFor(mode: DecoderMode): DecoderTuning = when (mode) {
        DecoderMode.HW -> DecoderTuning(
            enableMediaCodecAsyncQueueing = false,
            enableMediaCodecBufferDecoding = false,
            maxFrameRate = 0,
            dropLateFrames = false,
            dropInputBuffersWhileLate = false,
            allowQueueingExcessDuringSlowMotion = true,
            recycleInputBuffers = true
        )

        DecoderMode.HW_PLUS -> DecoderTuning(
            enableMediaCodecAsyncQueueing = true,
            enableMediaCodecBufferDecoding = true,
            maxFrameRate = 0,
            dropLateFrames = false,
            dropInputBuffersWhileLate = false,
            allowQueueingExcessDuringSlowMotion = false,
            recycleInputBuffers = true
        )

        DecoderMode.SW -> DecoderTuning(
            enableMediaCodecAsyncQueueing = false,
            enableMediaCodecBufferDecoding = false,
            maxFrameRate = 60,
            dropLateFrames = true,
            dropInputBuffersWhileLate = true,
            allowQueueingExcessDuringSlowMotion = true,
            recycleInputBuffers = false
        )
    }

    /**
     * Chooses the decoder to use after a playback error.
     *
     * @param requested what the user asked for
     * @param hardwareSupported whether the device advertises support
     * @param previousAttempts how many times we already fell back
     * @return the mode to try next, or null if we have exhausted options
     */
    fun nextModeOnError(
        requested: DecoderMode,
        hardwareSupported: Boolean,
        @Suppress("UNUSED_PARAMETER") previousAttempts: Int
    ): DecoderMode? {
        if (!hardwareSupported) {
            return if (requested == DecoderMode.SW) null else DecoderMode.SW
        }
        return when (requested) {
            DecoderMode.HW -> DecoderMode.HW_PLUS
            DecoderMode.HW_PLUS -> DecoderMode.SW
            DecoderMode.SW -> null
        }
    }

    /** Effective mode after applying device capability discovery. */
    fun resolve(requested: DecoderMode, hardwareSupported: Boolean): DecoderMode {
        if (!hardwareSupported && requested != DecoderMode.SW) return DecoderMode.SW
        return requested
    }
}
