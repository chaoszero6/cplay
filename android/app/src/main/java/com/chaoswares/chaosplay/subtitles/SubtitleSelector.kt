package com.chaoswares.chaosplay.subtitles

/**
 * Subtitle file discovery and preference rules, spec section 4:
 * "Auto-detect subtitle files next to the video", "Remember the chosen
 * subtitle per video", "Preferred subtitle language ... with auto-select",
 * "Fallback order".
 *
 * Pure Kotlin so all of this is unit testable.
 */
object SubtitleSelector {

    /**
     * Scores a sidecar subtitle file against the video it sits next to.
     * Exact base-name matches beat language-only matches, and known
     * languages beat unknown ones.
     */
    fun scoreFor(videoFileName: String, subtitleFileName: String): Int {
        val videoBase = videoFileName.substringBeforeLast('.', videoFileName).lowercase()
        val subBase = subtitleFileName.substringBeforeLast('.', subtitleFileName).lowercase()
        var score = 0

        // An exact base-name match ("movie.srt" for "movie.mkv") is the
        // strongest signal, so it must outrank any language variant even
        // when that variant also has a known language bonus. Take the
        // best of the two rather than adding them.
        val nameScore = when {
            subBase == videoBase -> 100
            subBase.startsWith("$videoBase.") -> 80
            else -> 0
        }
        score += nameScore

        // Language recognition is a secondary bonus and must not close the
        // gap to an exact match, so it is capped below the 20 point band
        // that separates 100 from 80.
        val tag = extractLanguageTag(videoBase, subBase)
        if (tag != null && LanguageNames.isKnown(tag)) score += 10
        return score
    }

    /**
     * Pulls the language tag out of a sidecar file name.
     * "movie.hi.srt" against "movie.mkv" -> "hi".
     * "movie.srt" -> null (no language marker).
     */
    fun extractLanguageTag(videoBase: String, subBase: String): String? {
        val suffix = when {
            subBase == videoBase -> return null
            subBase.startsWith("$videoBase.") -> subBase.removePrefix("$videoBase.")
            else -> subBase.substringAfterLast('.', "")
        }
        if (suffix.isBlank()) return null
        val candidate = suffix.substringBefore('.').trim()
        if (candidate.isEmpty() || candidate.length > 8) return null
        return candidate
    }

    /**
     * Orders sidecar subtitle files best-first for a video.
     * Ties fall back to ASS before SRT etc. via [SubtitleFormat] ordering,
     * since ASS carries styling (spec: "Styled ASS/SSA rendering with libass").
     */
    fun rank(videoFileName: String, candidates: List<String>): List<String> {
        return candidates
            .filter { SubtitleFormat.isSubtitleFile(it) }
            .sortedWith(
                compareByDescending<String> { scoreFor(videoFileName, it) }
                    .thenBy { formatRank(it) }
                    .thenBy { it }
            )
    }

    private fun formatRank(fileName: String): Int {
        val ext = fileName.substringAfterLast('.', "")
        val format = SubtitleFormat.fromExtension(ext)
        return format?.ordinal ?: Int.MAX_VALUE
    }

    /**
     * Picks the track to auto-select on playback start.
     *
     * Implements the preferred-language + fallback-order rule: walk the
     * preferred list in order and take the first language that has a track;
     * if none match, fall back to the first available track, or null when
     * there are no tracks (menu shows "Off").
     */
    fun autoSelect(
        tracks: List<SubtitleTrack>,
        preferredLanguages: List<String>
    ): SubtitleTrack? {
        if (tracks.isEmpty()) return null
        for (preferred in preferredLanguages) {
            val normalised = preferred.trim().lowercase()
            val match = tracks.firstOrNull { track ->
                val tag = track.languageTag.trim().lowercase()
                tag == normalised || tag.startsWith("$normalised-") || tag.startsWith("${normalised}_")
            }
            if (match != null) return match
        }
        return tracks.firstOrNull()
    }

    /**
     * Parses a fallback-order setting such as "ta,en,off" into a list.
     * A trailing "off" simply means "no further fallback", so it is dropped;
     * an empty result means the user wants subtitles off by default.
     */
    fun parseFallbackOrder(setting: String): List<String> {
        return setting.split(',')
            .map { it.trim().lowercase() }
            .filter { it.isNotEmpty() && it != "off" }
    }
}