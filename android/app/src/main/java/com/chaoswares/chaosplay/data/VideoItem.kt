package com.chaoswares.chaosplay.data

/**
 * A video file discovered on the device.
 * Pure data holder so it can be unit tested without Android.
 */
data class VideoItem(
    val id: Long,
    val uri: String,
    val title: String,
    val path: String,
    val folder: String,
    val durationMs: Long,
    val sizeBytes: Long,
    val width: Int = 0,
    val height: Int = 0,
    val dateAddedSec: Long = 0,
    val mimeType: String = "",
    /** File mtime; stamps the generated thumbnail so re-encodes refresh it. */
    val dateModifiedSec: Long = 0
) {
    /** "1920x1080" or "" when unknown. */
    val resolutionLabel: String
        get() = if (width > 0 && height > 0) "${width}x$height" else ""

    /** Human readable size, e.g. "1.2 GB". */
    val sizeLabel: String
        get() = formatSize(sizeBytes)

    companion object {
        fun formatSize(bytes: Long): String {
            if (bytes <= 0) return "0 B"
            val units = listOf("B", "KB", "MB", "GB", "TB")
            var value = bytes.toDouble()
            var unitIndex = 0
            while (value >= 1024.0 && unitIndex < units.lastIndex) {
                value /= 1024.0
                unitIndex++
            }
            return if (unitIndex == 0) {
                "${bytes} ${units[unitIndex]}"
            } else {
                String.format("%.1f %s", value, units[unitIndex])
            }
        }
    }
}