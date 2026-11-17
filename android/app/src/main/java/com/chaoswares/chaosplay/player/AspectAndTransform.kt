package com.chaoswares.chaosplay.player

/**
 * Aspect ratio options, spec section 3 and 4.5:
 * Fit, Fill, Stretch, 16:9, 4:3, 100%, original.
 */
enum class AspectMode(val label: String) {
    FIT("Fit"),
    FILL("Fill"),
    STRETCH("Stretch"),
    RATIO_16_9("16:9"),
    RATIO_4_3("4:3"),
    PERCENT_100("100%"),
    ORIGINAL("Original");

    fun next(): AspectMode {
        val values = entries
        return values[(ordinal + 1) % values.size]
    }
}

/**
 * Video transform state: zoom, pan, mirror and flip (spec section 3,
 * "Zoom and pan; screen reversal (mirror) and upside-down flip; rotation").
 */
data class VideoTransform(
    val zoom: Float = 1.0f,
    val panX: Float = 0f,
    val panY: Float = 0f,
    val mirrored: Boolean = false,
    val flipped: Boolean = false,
    val rotationDegrees: Int = 0
) {
    companion object {
        const val MIN_ZOOM = 1.0f
        const val MAX_ZOOM = 5.0f

        fun clampZoom(zoom: Float): Float {
            if (zoom.isNaN()) return 1.0f
            return zoom.coerceIn(MIN_ZOOM, MAX_ZOOM)
        }
    }

    /** Pinch-to-zoom, spec section 4.2. */
    fun zoomBy(factor: Float): VideoTransform {
        if (factor.isNaN() || factor <= 0f) return this
        return copy(zoom = clampZoom(zoom * factor))
    }

    /** Drag to pan, spec section 3. Resets to centre when fully zoomed out. */
    fun panBy(dx: Float, dy: Float): VideoTransform {
        if (zoom <= MIN_ZOOM) return copy(panX = 0f, panY = 0f)
        return copy(panX = panX + dx, panY = panY + dy)
    }

    /** 90-degree rotation steps (spec: "rotation"). */
    fun rotate(): VideoTransform {
        return copy(rotationDegrees = (rotationDegrees + 90) % 360)
    }

    fun reset(): VideoTransform = VideoTransform()

    /** Zoom percent label shown during a pinch gesture. */
    fun zoomLabel(): String = "${(zoom * 100).toInt()}%"
}