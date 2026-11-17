package com.chaoswares.chaosplay.subtitles

/**
 * Sync and speed for subtitle cues, spec section 4:
 * "Sync adjustment (delay/advance)" and "Subtitle speed adjustment (fixes
 * frame-rate mismatch)".
 *
 * A cue's on-screen time is `cueTime * speed + delay`. Speed rescales the
 * whole file (a 25 fps file on a 23.976 fps video drifts linearly); delay
 * shifts it. Positive delay shows the text later.
 */
object SubtitleTimeline {

    const val DELAY_STEP_MS = 100L
    const val MAX_DELAY_MS = 600_000L

    /** Frame-rate conversion presets offered in the subtitle sheet. */
    val SPEED_PRESETS: List<Pair<String, Double>> = listOf(
        "1.000 (no change)" to 1.0,
        "23.976 → 25" to 23.976 / 25.0,
        "25 → 23.976" to 25.0 / 23.976,
        "24 → 25" to 24.0 / 25.0,
        "25 → 24" to 25.0 / 24.0,
        "29.97 → 25" to 29.97 / 25.0,
        "25 → 29.97" to 25.0 / 29.97
    )

    fun clampDelay(delayMs: Long): Long = delayMs.coerceIn(-MAX_DELAY_MS, MAX_DELAY_MS)

    fun clampSpeed(speed: Double): Double =
        if (speed.isNaN() || speed <= 0.0) 1.0 else speed.coerceIn(0.5, 2.0)

    fun displayStart(cue: Cue, delayMs: Long, speed: Double): Long =
        (cue.startMs * speed).toLong() + delayMs

    fun displayEnd(cue: Cue, delayMs: Long, speed: Double): Long =
        (cue.endMs * speed).toLong() + delayMs

    /**
     * Cues visible at [positionMs]. [cues] must be sorted by start (the
     * parser guarantees it). Overlapping cues are all returned, earliest
     * first, so two speakers at once both show.
     */
    fun activeCues(cues: List<Cue>, positionMs: Long, delayMs: Long = 0L, speed: Double = 1.0): List<Cue> {
        if (cues.isEmpty()) return emptyList()
        val s = clampSpeed(speed)
        // Binary search for the last cue whose display start <= position,
        // then walk backwards collecting cues that are still showing.
        var lo = 0
        var hi = cues.lastIndex
        var last = -1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            if (displayStart(cues[mid], delayMs, s) <= positionMs) {
                last = mid
                lo = mid + 1
            } else {
                hi = mid - 1
            }
        }
        if (last < 0) return emptyList()
        val result = ArrayList<Cue>(2)
        var i = last
        // Long cues can start well before shorter ones that already ended;
        // look back a bounded window so this stays O(log n) in practice.
        val floor = (last - 32).coerceAtLeast(0)
        while (i >= floor) {
            val cue = cues[i]
            if (displayEnd(cue, delayMs, s) > positionMs) result += cue
            i--
        }
        return result.asReversed()
    }

    /** Text of every active cue, joined by newlines; empty when nothing shows. */
    fun textAt(cues: List<Cue>, positionMs: Long, delayMs: Long = 0L, speed: Double = 1.0): String =
        activeCues(cues, positionMs, delayMs, speed).joinToString("\n") { it.text }

    /**
     * Playback position of the next line's start, for the "scroll to jump
     * to the next/previous line" subtitle gesture. Null when there is none.
     */
    fun nextLineStart(cues: List<Cue>, positionMs: Long, delayMs: Long = 0L, speed: Double = 1.0): Long? {
        val s = clampSpeed(speed)
        return cues.asSequence()
            .map { displayStart(it, delayMs, s) }
            .firstOrNull { it > positionMs + 50L }
            ?.coerceAtLeast(0L)
    }

    fun previousLineStart(cues: List<Cue>, positionMs: Long, delayMs: Long = 0L, speed: Double = 1.0): Long? {
        val s = clampSpeed(speed)
        // Skip the line we are currently inside, as a music player's "previous" does
        val starts = cues.map { displayStart(it, delayMs, s) }.filter { it < positionMs - 1_000L }
        return starts.lastOrNull()?.coerceAtLeast(0L)
    }

    /** "+0.5 s", "-1.2 s", "0.0 s" for the delay control. */
    fun formatDelay(delayMs: Long): String {
        val sign = when {
            delayMs > 0 -> "+"
            delayMs < 0 -> "-"
            else -> ""
        }
        val abs = kotlin.math.abs(delayMs)
        return "$sign${abs / 1000}.${(abs % 1000) / 100} s"
    }
}

/**
 * Subtitle look, spec section 4 "Style: font, size, color, bold, italic,
 * outline, background, position" and "Clear text: border and shadow".
 */
data class SubtitleStyle(
    val sizeSp: Int = 20,
    val colorArgb: Long = 0xFFFFFFFF,
    val background: Boolean = false,
    val outline: Boolean = true,
    val bold: Boolean = false,
    /** Distance from the bottom edge, percent of the video height. */
    val bottomMarginPercent: Int = 8
) {
    companion object {
        const val MIN_SIZE = 12
        const val MAX_SIZE = 48
        const val MAX_MARGIN = 60

        val SIZE_PRESETS = listOf(14, 16, 20, 24, 28, 34, 40)

        val COLOR_PRESETS: List<Pair<String, Long>> = listOf(
            "White" to 0xFFFFFFFF,
            "Yellow" to 0xFFFFE066,
            "Cyan" to 0xFF7FE7FF,
            "Green" to 0xFF9CF59C,
            "Pink" to 0xFFFFA8D8
        )

        fun clamp(style: SubtitleStyle): SubtitleStyle = style.copy(
            sizeSp = style.sizeSp.coerceIn(MIN_SIZE, MAX_SIZE),
            bottomMarginPercent = style.bottomMarginPercent.coerceIn(0, MAX_MARGIN)
        )

        fun encode(style: SubtitleStyle): String = listOf(
            "size=${style.sizeSp}",
            "color=${style.colorArgb.toString(16)}",
            "bg=${style.background}",
            "outline=${style.outline}",
            "bold=${style.bold}",
            "margin=${style.bottomMarginPercent}"
        ).joinToString(";")

        fun decode(raw: String): SubtitleStyle {
            if (raw.isBlank()) return SubtitleStyle()
            val map = raw.split(';').mapNotNull {
                val k = it.substringBefore('=', "").trim()
                if (k.isEmpty()) null else k to it.substringAfter('=').trim()
            }.toMap()
            val d = SubtitleStyle()
            return clamp(
                SubtitleStyle(
                    sizeSp = map["size"]?.toIntOrNull() ?: d.sizeSp,
                    colorArgb = map["color"]?.toLongOrNull(16) ?: d.colorArgb,
                    background = map["bg"]?.toBooleanStrictOrNull() ?: d.background,
                    outline = map["outline"]?.toBooleanStrictOrNull() ?: d.outline,
                    bold = map["bold"]?.toBooleanStrictOrNull() ?: d.bold,
                    bottomMarginPercent = map["margin"]?.toIntOrNull() ?: d.bottomMarginPercent
                )
            )
        }
    }
}

/**
 * Per-video subtitle memory, spec 4.3: "Remember the choice per video".
 * Keys: "off", "emb:<index>:<lang>" for an embedded track, "ext:<location>"
 * for a loaded file.
 */
object SubtitleChoices {

    const val OFF = "off"
    const val MAX_ENTRIES = 300

    fun embeddedKey(index: Int, language: String) = "emb:$index:$language"
    fun externalKey(location: String) = "ext:$location"

    fun isExternal(key: String) = key.startsWith("ext:")
    fun externalLocation(key: String): String? = if (isExternal(key)) key.removePrefix("ext:") else null

    /** Language part of an embedded key, used to re-match when indices shift. */
    fun embeddedLanguage(key: String): String? =
        if (key.startsWith("emb:")) key.split(':', limit = 3).getOrNull(2) else null

    fun embeddedIndex(key: String): Int? =
        if (key.startsWith("emb:")) key.split(':', limit = 3).getOrNull(1)?.toIntOrNull() else null

    fun encode(map: Map<String, String>): String =
        com.chaoswares.chaosplay.util.FieldCodec.encode(map.entries.map { listOf(it.key, it.value) })

    fun decode(raw: String): Map<String, String> =
        com.chaoswares.chaosplay.util.FieldCodec.decode(raw)
            .filter { it.size >= 2 && it[0].isNotBlank() }
            .associate { it[0] to it[1] }

    /** Stores [key] for [uri], keeping the newest [MAX_ENTRIES]. */
    fun remember(map: Map<String, String>, uri: String, key: String): Map<String, String> {
        val updated = LinkedHashMap<String, String>()
        map.forEach { (k, v) -> if (k != uri) updated[k] = v }
        updated[uri] = key
        while (updated.size > MAX_ENTRIES) updated.remove(updated.keys.first())
        return updated
    }
}
