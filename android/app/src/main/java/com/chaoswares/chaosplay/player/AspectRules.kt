package com.chaoswares.chaosplay.player

/**
 * How each [AspectMode] maps onto the video frame, spec 4.5.
 *
 * The previous build mapped FILL to Media3's RESIZE_MODE_FILL (which
 * *stretches*) and STRETCH to RESIZE_MODE_ZOOM (which *crops*): the two
 * labels did each other's job. This table is now the single source of
 * truth and is unit tested.
 */
object AspectRules {

    /** Mirrors AspectRatioFrameLayout's resize modes without the Android dependency. */
    enum class Resize { FIT, STRETCH, CROP }

    data class Layout(
        val resize: Resize,
        /** Forced display aspect (width / height), null to use the video's own. */
        val forcedAspect: Float? = null,
        /** Draw at the video's pixel size, no scaling (100%). */
        val nativeSize: Boolean = false
    )

    fun layoutFor(mode: AspectMode): Layout = when (mode) {
        AspectMode.FIT, AspectMode.ORIGINAL -> Layout(Resize.FIT)
        AspectMode.FILL -> Layout(Resize.CROP)
        AspectMode.STRETCH -> Layout(Resize.STRETCH)
        AspectMode.RATIO_16_9 -> Layout(Resize.STRETCH, forcedAspect = 16f / 9f)
        AspectMode.RATIO_4_3 -> Layout(Resize.STRETCH, forcedAspect = 4f / 3f)
        AspectMode.PERCENT_100 -> Layout(Resize.FIT, nativeSize = true)
    }

    /**
     * Size of a box with [aspect] fitted inside [containerW] x [containerH].
     * Used for forced 16:9 / 4:3 and for 90-degree rotations.
     */
    fun fitBox(containerW: Float, containerH: Float, aspect: Float): Pair<Float, Float> {
        if (containerW <= 0f || containerH <= 0f || aspect <= 0f || aspect.isNaN()) return containerW to containerH
        return if (containerW / containerH > aspect) {
            containerH * aspect to containerH
        } else {
            containerW to containerW / aspect
        }
    }

    /**
     * Extra scale needed so a frame rotated by 90/270 degrees still fits
     * the container instead of spilling out of it.
     */
    fun rotationFitScale(rotationDegrees: Int, containerW: Float, containerH: Float): Float {
        val quarter = ((rotationDegrees % 360) + 360) % 360
        if (quarter != 90 && quarter != 270) return 1f
        if (containerW <= 0f || containerH <= 0f) return 1f
        return minOf(containerW / containerH, containerH / containerW)
    }

    /** True when the frame needs a TextureView (SurfaceView ignores mirror/rotation transforms). */
    fun needsTextureView(t: VideoTransform): Boolean =
        t.mirrored || t.flipped || t.rotationDegrees % 360 != 0 || t.zoom != 1f

    /**
     * PiP aspect ratio for a video, clamped to what Android accepts
     * (between 1:2.39 and 2.39:1). Returns (numerator, denominator).
     */
    fun pipRatio(width: Int, height: Int): Pair<Int, Int> {
        if (width <= 0 || height <= 0) return 16 to 9
        val ratio = width.toFloat() / height
        return when {
            ratio > 2.39f -> 239 to 100
            ratio < 1f / 2.39f -> 100 to 239
            else -> width to height
        }
    }
}
