package com.chaoswares.chaosplay.player

/**
 * Player settings, spec 4.8 "Playback: decoder, default speed, seek step,
 * resume, sleep timer", "Gestures (Android): each gesture on/off" and
 * 2.5 "auto-hide delay is 3 seconds (configurable 2 to 8 s)".
 *
 * Stored as one "key=value;..." string so adding a setting never needs a
 * DataStore migration; unknown keys are ignored and missing keys default.
 */
data class PlayerPrefs(
    val defaultSpeed: Float = PlaybackRules.DEFAULT_SPEED,
    val seekStepMs: Long = PlaybackRules.DEFAULT_SEEK_STEP_MS,
    val autoHideMs: Long = 3_000L,
    val resumeEnabled: Boolean = true,
    val autoPlayNext: Boolean = true,
    val backgroundPlay: Boolean = false,
    val autoPip: Boolean = false,
    val keepScreenOn: Boolean = true,
    val landscapeForWideVideo: Boolean = true,
    val showClockAndBattery: Boolean = false,
    /** Volume levelling (compressor + limiter) on by default for new sessions. */
    val volumeLevelling: Boolean = false,
    /** Vibrate lightly on skips, lock corners and hold-for-2x (spec 7 accessibility). */
    val haptics: Boolean = true,
    /** Colourful ripples where a child touches the locked screen (spec 7.3). */
    val kidsTouchEffects: Boolean = true,
    // Swipe-down PiP reserves the middle third for itself, which takes
    // brightness/volume drags away from the centre, so it is opt-in.
    val gestures: GestureRules.GestureSettings = GestureRules.GestureSettings(swipeDownPipEnabled = false)
) {
    companion object {
        val SEEK_STEP_OPTIONS_MS = listOf(5_000L, 10_000L, 15_000L, 30_000L, 60_000L)
        val AUTO_HIDE_OPTIONS_MS = listOf(2_000L, 3_000L, 4_000L, 5_000L, 8_000L)
        val DEFAULT_SPEED_OPTIONS = listOf(0.75f, 1.0f, 1.25f, 1.5f, 2.0f)

        fun clamp(p: PlayerPrefs): PlayerPrefs = p.copy(
            defaultSpeed = PlaybackRules.clampSpeed(p.defaultSpeed),
            seekStepMs = p.seekStepMs.coerceIn(1_000L, 120_000L),
            autoHideMs = p.autoHideMs.coerceIn(2_000L, 8_000L)
        )

        fun encode(p: PlayerPrefs): String {
            val g = p.gestures
            return listOf(
                "speed" to p.defaultSpeed,
                "seek" to p.seekStepMs,
                "hide" to p.autoHideMs,
                "resume" to p.resumeEnabled,
                "next" to p.autoPlayNext,
                "bg" to p.backgroundPlay,
                "pip" to p.autoPip,
                "screen" to p.keepScreenOn,
                "land" to p.landscapeForWideVideo,
                "clock" to p.showClockAndBattery,
                "level" to p.volumeLevelling,
                "haptic" to p.haptics,
                "kidsfx" to p.kidsTouchEffects,
                "g.bright" to g.brightnessEnabled,
                "g.vol" to g.volumeEnabled,
                "g.seek" to g.seekEnabled,
                "g.dtap" to g.doubleTapSkipEnabled,
                "g.pinch" to g.pinchZoomEnabled,
                "g.speed" to g.twoFingerSpeedEnabled,
                "g.pip" to g.swipeDownPipEnabled,
                "g.long" to g.longPressSpeedEnabled
            ).joinToString(";") { (k, v) -> "$k=$v" }
        }

        fun decode(raw: String): PlayerPrefs {
            val d = PlayerPrefs()
            if (raw.isBlank()) return d
            val m = raw.split(';').mapNotNull {
                val k = it.substringBefore('=', "").trim()
                if (k.isEmpty()) null else k to it.substringAfter('=').trim()
            }.toMap()
            fun b(key: String, def: Boolean) = m[key]?.toBooleanStrictOrNull() ?: def
            val dg = d.gestures
            return clamp(
                PlayerPrefs(
                    defaultSpeed = m["speed"]?.toFloatOrNull() ?: d.defaultSpeed,
                    seekStepMs = m["seek"]?.toLongOrNull() ?: d.seekStepMs,
                    autoHideMs = m["hide"]?.toLongOrNull() ?: d.autoHideMs,
                    resumeEnabled = b("resume", d.resumeEnabled),
                    autoPlayNext = b("next", d.autoPlayNext),
                    backgroundPlay = b("bg", d.backgroundPlay),
                    autoPip = b("pip", d.autoPip),
                    keepScreenOn = b("screen", d.keepScreenOn),
                    landscapeForWideVideo = b("land", d.landscapeForWideVideo),
                    showClockAndBattery = b("clock", d.showClockAndBattery),
                    volumeLevelling = b("level", d.volumeLevelling),
                    haptics = b("haptic", d.haptics),
                    kidsTouchEffects = b("kidsfx", d.kidsTouchEffects),
                    gestures = GestureRules.GestureSettings(
                        brightnessEnabled = b("g.bright", dg.brightnessEnabled),
                        volumeEnabled = b("g.vol", dg.volumeEnabled),
                        seekEnabled = b("g.seek", dg.seekEnabled),
                        doubleTapSkipEnabled = b("g.dtap", dg.doubleTapSkipEnabled),
                        pinchZoomEnabled = b("g.pinch", dg.pinchZoomEnabled),
                        twoFingerSpeedEnabled = b("g.speed", dg.twoFingerSpeedEnabled),
                        swipeDownPipEnabled = b("g.pip", dg.swipeDownPipEnabled),
                        longPressSpeedEnabled = b("g.long", dg.longPressSpeedEnabled)
                    )
                )
            )
        }

        /** "10 s", "1 min" for the settings rows and skip ripple. */
        fun seekStepLabel(ms: Long): String =
            if (ms >= 60_000L && ms % 60_000L == 0L) "${ms / 60_000L} min" else "${ms / 1000L} s"
    }
}

/**
 * Volume boost and equalizer maths, spec section 6: "Volume boost up to
 * 200%" and "Equalizer with presets". The Android effect objects are thin
 * wrappers around these numbers.
 */
object AudioRules {

    const val MAX_BOOST = 2.0f

    /**
     * LoudnessEnhancer target gain in millibels for a 0..2 volume level.
     * Up to 1.0 there is no boost; 2.0 is +6.02 dB (602 mB), i.e. double
     * amplitude.
     */
    fun boostGainMb(level: Float): Int {
        if (level.isNaN() || level <= 1.0f) return 0
        val clamped = level.coerceAtMost(MAX_BOOST)
        return (2000.0 * kotlin.math.log10(clamped.toDouble())).toInt()
    }

    /**
     * Splits a 0..2 gesture level into (system volume fraction, boost
     * level). Below 1.0 the system volume moves; above it the system
     * volume is pinned to max and the remainder is boost.
     */
    fun split(level: Float): Pair<Float, Float> {
        val l = if (level.isNaN()) 1f else level.coerceIn(0f, MAX_BOOST)
        return if (l <= 1f) l to 1f else 1f to l
    }

    /** System stream index for a 0..1 fraction. */
    fun streamIndex(fraction: Float, maxIndex: Int): Int {
        if (maxIndex <= 0) return 0
        return kotlin.math.round(fraction.coerceIn(0f, 1f) * maxIndex).toInt().coerceIn(0, maxIndex)
    }

    /**
     * Volume levelling (spec 6 "Audio normalization"): a single-band
     * compressor that lifts quiet dialogue and tames loud effects, then a
     * limiter so the make-up gain never clips. Values are dB / ms as
     * Android's DynamicsProcessing expects.
     */
    data class Leveller(
        val thresholdDb: Float = -30f,
        val ratio: Float = 4f,
        val attackMs: Float = 10f,
        val releaseMs: Float = 250f,
        val kneeDb: Float = 6f,
        val makeUpGainDb: Float = 9f,
        val limiterThresholdDb: Float = -1.5f,
        val limiterRatio: Float = 12f
    )

    val LEVELLER = Leveller()

    /**
     * Output level for an input level in dB through the compressor
     * (hard-knee approximation), used to sanity-check the settings: quiet
     * input is raised, loud input is lowered towards the limiter.
     */
    fun levelledDb(inputDb: Float, l: Leveller = LEVELLER): Float {
        val compressed = if (inputDb <= l.thresholdDb) inputDb else l.thresholdDb + (inputDb - l.thresholdDb) / l.ratio
        val withGain = compressed + l.makeUpGainDb
        return if (withGain <= l.limiterThresholdDb) withGain
        else l.limiterThresholdDb + (withGain - l.limiterThresholdDb) / l.limiterRatio
    }

    /** Equalizer presets as a 5-point curve from low to high, -1..1 of range. */
    enum class EqPreset(val label: String, val curve: List<Float>) {
        FLAT("Flat", listOf(0f, 0f, 0f, 0f, 0f)),
        BASS("Bass boost", listOf(0.8f, 0.5f, 0f, 0f, 0f)),
        VOCAL("Voice / dialogue", listOf(-0.3f, 0f, 0.6f, 0.4f, 0f)),
        TREBLE("Treble boost", listOf(0f, 0f, 0f, 0.4f, 0.8f)),
        MOVIE("Movie", listOf(0.5f, 0.2f, 0.3f, 0.2f, 0.4f)),
        ROCK("Rock", listOf(0.5f, 0.3f, -0.2f, 0.3f, 0.5f)),
        NIGHT("Night (soft bass)", listOf(-0.6f, -0.3f, 0.2f, 0.1f, -0.2f))
    }

    /**
     * Maps a preset curve onto a device equalizer with [bandCount] bands
     * whose levels range over [minMb]..[maxMb], interpolating the 5-point
     * curve so 3-band and 10-band equalizers both get a sensible shape.
     */
    fun bandLevels(preset: EqPreset, bandCount: Int, minMb: Int, maxMb: Int): List<Int> {
        if (bandCount <= 0) return emptyList()
        val curve = preset.curve
        val positive = maxMb.coerceAtLeast(0)
        val negative = (-minMb).coerceAtLeast(0)
        return (0 until bandCount).map { band ->
            val t = if (bandCount == 1) 0f else band.toFloat() / (bandCount - 1) * (curve.size - 1)
            val i = t.toInt().coerceAtMost(curve.size - 2)
            val frac = t - i
            val v = curve[i] + (curve[i + 1] - curve[i]) * frac
            val mb = if (v >= 0f) v * positive else v * negative
            mb.toInt().coerceIn(minMb, maxMb)
        }
    }
}

/** Screen orientation choices for the Rotate button (spec 6: "Auto-rotate with a manual lock"). */
enum class OrientationMode(val label: String) {
    AUTO("Auto"),
    LANDSCAPE("Landscape"),
    PORTRAIT("Portrait");

    fun next(): OrientationMode = entries[(ordinal + 1) % entries.size]

    companion object {
        /**
         * Initial orientation for a video: landscape for wide video when the
         * setting is on, else follow the sensor. Unknown size means auto.
         */
        fun forVideo(width: Int, height: Int, landscapeForWide: Boolean): OrientationMode {
            if (!landscapeForWide || width <= 0 || height <= 0) return AUTO
            return if (width > height) LANDSCAPE else AUTO
        }
    }
}

/**
 * Seek-bar thumbnail preview granularity (spec 4.2 "While dragging:
 * thumbnail preview above the thumb"). Frames are decoded per bucket so
 * dragging does not request a new frame on every pixel.
 */
object SeekPreviewRules {

    const val MIN_BUCKET_MS = 1_000L
    const val MAX_BUCKET_MS = 10_000L

    fun bucketSizeMs(durationMs: Long): Long {
        if (durationMs <= 0L) return MIN_BUCKET_MS
        return (durationMs / 120L).coerceIn(MIN_BUCKET_MS, MAX_BUCKET_MS)
    }

    /** Start of the bucket containing [positionMs]. */
    fun bucket(positionMs: Long, durationMs: Long): Long {
        val size = bucketSizeMs(durationMs)
        val clamped = if (durationMs > 0) positionMs.coerceIn(0L, durationMs) else positionMs.coerceAtLeast(0L)
        return clamped / size * size
    }
}
