package com.chaoswares.chaosplay.subtitles

/**
 * Subtitle formats the app can parse, spec section 4.
 * Order matters: it is the preference order when several files sit next to
 * a video with the same base name.
 */
enum class SubtitleFormat(val extensions: List<String>, val label: String) {
    ASS(listOf("ass", "ssa"), "SubStation Alpha"),
    SRT(listOf("srt"), "SubRip"),
    VTT(listOf("vtt"), "WebVTT"),
    SUBVIEWER(listOf("sub"), "SubViewer / MicroDVD"),
    SAMI(listOf("smi"), "SAMI"),
    MPL2(listOf("mpl2"), "MPL2"),
    TMPLAYER(listOf("tmp"), "TMPlayer"),
    PJS(listOf("pjs"), "PJS");

    companion object {
        private val byExtension: Map<String, SubtitleFormat> =
            entries.flatMap { fmt -> fmt.extensions.map { ext -> ext.lowercase() to fmt } }.toMap()

        /** Resolves a file extension to a format, or null when unsupported. */
        fun fromExtension(extension: String): SubtitleFormat? {
            return byExtension[extension.lowercase().removePrefix(".")]
        }

        /** True when a filename looks like a subtitle file. */
        fun isSubtitleFile(fileName: String): Boolean {
            val ext = fileName.substringAfterLast('.', "")
            return ext.isNotEmpty() && fromExtension(ext) != null
        }
    }
}

/**
 * A subtitle track offered in the subtitle menu (spec section 4.3).
 * Either embedded in the container or loaded from a sidecar file.
 */
data class SubtitleTrack(
    val id: String,
    /** Raw language tag, e.g. "ta", "en", "hi". */
    val languageTag: String,
    /** Human readable name, e.g. "Tamil (Embedded)" or "Hindi (movie.hi.srt)". */
    val displayName: String,
    val isEmbedded: Boolean,
    val format: SubtitleFormat? = null,
    /** File path for external tracks, null for embedded. */
    val filePath: String? = null
) {
    val isExternal: Boolean get() = !isEmbedded
}

/**
 * Language display names, spec section 4 "Language handling": the menu must
 * show readable language names rather than bare ISO codes.
 */
object LanguageNames {

    private val names: Map<String, String> = mapOf(
        "en" to "English",
        "hi" to "Hindi",
        "ta" to "Tamil",
        "te" to "Telugu",
        "ml" to "Malayalam",
        "kn" to "Kannada",
        "bn" to "Bengali",
        "mr" to "Marathi",
        "es" to "Spanish",
        "ar" to "Arabic",
        "fr" to "French",
        "de" to "German",
        "ja" to "Japanese",
        "ko" to "Korean",
        "zh" to "Chinese",
        "ru" to "Russian",
        "pt" to "Portuguese",
        "it" to "Italian"
    )

    /**
     * Resolves a language tag to a display name.
     * Handles region and script subtags: "pt-BR" -> "Portuguese",
     * "zh-Hans" -> "Chinese", and bare tags like "ta".
     * Unknown tags are returned with the primary subtag upper-cased.
     */
    fun display(tag: String): String {
        if (tag.isBlank()) return "Unknown"
        val primary = tag.trim().split('-', '_').firstOrNull()?.lowercase() ?: return "Unknown"
        return names[primary] ?: primary.uppercase()
    }

    /** True when we have a known readable name for this tag. */
    fun isKnown(tag: String): Boolean {
        if (tag.isBlank()) return false
        val primary = tag.trim().split('-', '_').firstOrNull()?.lowercase() ?: return false
        return names.containsKey(primary)
    }
}

/**
 * Subtitle encoding options, spec section 4:
 * "Encoding detection and manual encoding choice (UTF-8, UTF-16,
 * Windows-1252, Indian language encodings)".
 */
enum class SubtitleEncoding(val label: String) {
    AUTO("Auto"),
    UTF_8("UTF-8"),
    UTF_16("UTF-16"),
    WINDOWS_1252("Windows-1252"),
    ISO_8859_1("ISO-8859-1");

    fun next(): SubtitleEncoding = entries[(ordinal + 1) % entries.size]
}