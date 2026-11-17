package com.chaoswares.chaosplay.player

import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat

/**
 * Probes which video codecs this device actually decodes in hardware, so the
 * decoder badge and the fallback ladder reflect the device instead of always
 * assuming hardware is available.
 *
 * Only used for reporting and fallback decisions; Media3 still owns codec
 * selection. The probe result is cached per codec MIME type; MediaCodecList
 * enumeration is a few milliseconds, not free.
 */
object CodecSupport {

    /**
     * Probes the codec list on the calling thread — call from Dispatchers.IO
     * or a worker, never from the main thread. The list walk touches the
     * media codec service, which is tens of milliseconds on first use.
     */
    fun isHardwareDecodable(mime: String?): Boolean {
        val m = normaliseMime(mime ?: return false)
        if (m.isBlank()) return false
        return runCatching {
            val list = MediaCodecList(MediaCodecList.REGULAR_CODECS)
            list.codecInfos.any { info ->
                !info.isEncoder && info.isHardwareAccelerated &&
                    info.supportedTypes.any { it.equals(m, ignoreCase = true) }
            }
        }.getOrDefault(false)
    }

    /** Codec names that can decode [mime] in hardware, for the info sheet. */
    fun hardwareDecoderNames(mime: String?): List<String> {
        val m = normaliseMime(mime ?: return emptyList())
        if (m.isBlank()) return emptyList()
        return runCatching {
            MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
                .filter { !it.isEncoder && it.isHardwareAccelerated }
                .filter { info -> info.supportedTypes.any { it.equals(m, ignoreCase = true) } }
                .map { it.name }
        }.getOrDefault(emptyList())
    }

    /** The widest hardware profile level hint we can find, e.g. "AV1 4096x2160". */
    fun hardwareMaxSize(mime: String?): String? {
        val m = normaliseMime(mime ?: return null)
        if (m.isBlank()) return null
        return runCatching {
            val list = MediaCodecList(MediaCodecList.REGULAR_CODECS)
            var best: Pair<Int, Int>? = null
            for (info in list.codecInfos) {
                if (info.isEncoder || !info.isHardwareAccelerated) continue
                if (info.supportedTypes.none { it.equals(m, ignoreCase = true) }) continue
                val caps = info.getCapabilitiesForType(m)
                val w = caps.videoCapabilities?.supportedWidths?.upper ?: continue
                val h = caps.videoCapabilities?.supportedHeights?.upper ?: continue
                val current = best
                if (current == null || w * h > current.first * current.second) best = w to h
            }
            best?.let { (w, h) -> "$w" + "x" + "$h" }
        }.getOrNull()
    }

    fun normaliseMime(mime: String?): String {
        val m = mime.orEmpty().trim().lowercase()
        return when {
            m.isBlank() -> ""
            m.contains("/") -> if (m.startsWith("video/")) m else "video/" + m.substringAfterLast('/')
            else -> "video/" + m
        }
    }

    /** Codec short name for the info sheet, e.g. "hevc" -> "HEVC". */
    fun codecLabel(mime: String?): String {
        val m = normaliseMime(mime)
        return when (m) {
            MediaFormat.MIMETYPE_VIDEO_AVC -> "H.264"
            MediaFormat.MIMETYPE_VIDEO_HEVC -> "HEVC"
            MediaFormat.MIMETYPE_VIDEO_VP9 -> "VP9"
            MediaFormat.MIMETYPE_VIDEO_AV1 -> "AV1"
            MediaFormat.MIMETYPE_VIDEO_MPEG4 -> "MPEG-4"
            MediaFormat.MIMETYPE_VIDEO_H263 -> "H.263"
            MediaFormat.MIMETYPE_VIDEO_MPEG2 -> "MPEG-2"
            else -> m.substringAfterLast('/').uppercase()
        }
    }
}
