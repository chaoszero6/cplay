package com.chaoswares.chaosplay.subtitles

/**
 * One timed subtitle line. [text] keeps a small, normalised markup set
 * (<i>, <b>, <u>) that [SubtitleMarkup] turns into styled spans; every
 * other tag from the source format is stripped by the parser.
 */
data class Cue(
    val startMs: Long,
    val endMs: Long,
    val text: String
)

/**
 * Subtitle file parsers, spec section 4 "Formats to support": SubRip,
 * WebVTT, SubStation Alpha (dialogue text, styling tags reduced to
 * italic/bold/underline), MicroDVD, MPL2 and TMPlayer.
 *
 * Every parser is lenient: a malformed block is skipped, never fatal, so
 * a half-broken file still shows the lines that are fine (spec 13:
 * "Corrupt and partial files (no crashes)").
 */
object SubtitleParser {

    /** Default frame rate for MicroDVD files that do not declare one. */
    const val DEFAULT_MICRODVD_FPS = 23.976

    fun parse(content: String, format: SubtitleFormat?, fps: Double = DEFAULT_MICRODVD_FPS): List<Cue> {
        val text = normaliseNewlines(content.removePrefix("﻿"))
        val cues = when (format ?: detectFormat(text)) {
            SubtitleFormat.SRT -> parseSrt(text)
            SubtitleFormat.VTT -> parseVtt(text)
            SubtitleFormat.ASS -> parseAss(text)
            SubtitleFormat.SUBVIEWER -> parseMicroDvd(text, fps)
            SubtitleFormat.MPL2 -> parseMpl2(text)
            SubtitleFormat.TMPLAYER -> parseTmPlayer(text)
            // SAMI and PJS are listed in the spec but not parsed yet:
            // fall back to sniffing the content rather than showing nothing.
            SubtitleFormat.SAMI, SubtitleFormat.PJS, null -> when (detectFormat(text)) {
                SubtitleFormat.SRT -> parseSrt(text)
                SubtitleFormat.VTT -> parseVtt(text)
                else -> emptyList()
            }
        }
        return cues
            .filter { it.endMs > it.startMs && it.text.isNotBlank() }
            .sortedWith(compareBy<Cue> { it.startMs }.thenBy { it.endMs })
    }

    /**
     * Sniffs the format from the content. The file extension is a better
     * signal when present, but ".sub" and ".txt" are shared by several
     * formats, so callers fall back to this.
     */
    fun detectFormat(content: String): SubtitleFormat? {
        val head = content.removePrefix("﻿").trimStart().take(4096)
        return when {
            head.startsWith("WEBVTT") -> SubtitleFormat.VTT
            head.contains("[Script Info]", ignoreCase = true) ||
                head.contains("[Events]", ignoreCase = true) -> SubtitleFormat.ASS
            SRT_TIME.containsMatchIn(head) -> SubtitleFormat.SRT
            MICRODVD_LINE.containsMatchIn(head) -> SubtitleFormat.SUBVIEWER
            MPL2_LINE.containsMatchIn(head) -> SubtitleFormat.MPL2
            TMPLAYER_LINE.containsMatchIn(head) -> SubtitleFormat.TMPLAYER
            else -> null
        }
    }

    /** Format for a file name; ".txt" and unknown extensions return null (sniff). */
    fun formatForFileName(fileName: String): SubtitleFormat? =
        SubtitleFormat.fromExtension(fileName.substringAfterLast('.', ""))

    // ---- SubRip ----

    private val SRT_TIME = Regex(
        """(\d{1,2}):(\d{1,2}):(\d{1,2})[,.](\d{1,3})\s*-->\s*(\d{1,2}):(\d{1,2}):(\d{1,2})[,.](\d{1,3})"""
    )

    fun parseSrt(text: String): List<Cue> {
        val cues = mutableListOf<Cue>()
        val lines = text.split('\n')
        var i = 0
        while (i < lines.size) {
            val match = SRT_TIME.find(lines[i])
            if (match == null) {
                i++
                continue
            }
            val (h1, m1, s1, f1, h2, m2, s2, f2) = match.destructured
            val start = toMs(h1, m1, s1, f1)
            val end = toMs(h2, m2, s2, f2)
            i++
            val body = StringBuilder()
            while (i < lines.size && lines[i].isNotBlank()) {
                // A missing blank line before the next cue: stop at its timing line
                if (SRT_TIME.containsMatchIn(lines[i])) break
                // ...and drop the numeric index that precedes that timing line
                if (i + 1 < lines.size && lines[i].trim().all { it.isDigit() } &&
                    SRT_TIME.containsMatchIn(lines[i + 1])
                ) break
                if (body.isNotEmpty()) body.append('\n')
                body.append(lines[i].trimEnd())
                i++
            }
            cues += Cue(start, end, cleanHtmlLike(body.toString()))
        }
        return cues
    }

    // ---- WebVTT ----

    private val VTT_TIME = Regex(
        """(?:(\d{1,2}):)?(\d{1,2}):(\d{1,2})\.(\d{1,3})\s*-->\s*(?:(\d{1,2}):)?(\d{1,2}):(\d{1,2})\.(\d{1,3})"""
    )

    fun parseVtt(text: String): List<Cue> {
        val cues = mutableListOf<Cue>()
        val blocks = text.split(Regex("\n\\s*\n"))
        for (block in blocks) {
            val lines = block.split('\n').filter { it.isNotEmpty() }
            val timingIndex = lines.indexOfFirst { VTT_TIME.containsMatchIn(it) }
            if (timingIndex < 0) continue
            val first = lines.first().trim()
            if (first.startsWith("NOTE") || first.startsWith("STYLE") || first.startsWith("REGION")) continue
            val m = VTT_TIME.find(lines[timingIndex])!!.groupValues
            val start = toMs(m[1].ifEmpty { "0" }, m[2], m[3], m[4])
            val end = toMs(m[5].ifEmpty { "0" }, m[6], m[7], m[8])
            val body = lines.drop(timingIndex + 1).joinToString("\n") { it.trimEnd() }
            // Voice spans and class tags: <v Bob>, <c.yellow> - keep the words
            val cleaned = body
                .replace(Regex("<v[^>]*>"), "")
                .replace(Regex("</v>"), "")
                .replace(Regex("<c[^>]*>"), "")
                .replace(Regex("</c>"), "")
                .replace(Regex("<\\d{1,2}:\\d{2}[:.][\\d.]+>"), "")
            cues += Cue(start, end, cleanHtmlLike(cleaned))
        }
        return cues
    }

    // ---- SubStation Alpha / Advanced SubStation ----

    fun parseAss(text: String): List<Cue> {
        val cues = mutableListOf<Cue>()
        var inEvents = false
        var format: List<String> = DEFAULT_ASS_FORMAT
        for (raw in text.split('\n')) {
            val line = raw.trim()
            if (line.startsWith("[")) {
                inEvents = line.equals("[Events]", ignoreCase = true)
                continue
            }
            if (!inEvents) continue
            if (line.startsWith("Format:", ignoreCase = true)) {
                format = line.substringAfter(':').split(',').map { it.trim().lowercase() }
                continue
            }
            if (!line.startsWith("Dialogue:", ignoreCase = true)) continue
            val body = line.substringAfter(':').trimStart()
            // Text is the last field and may itself contain commas
            val parts = body.split(',', limit = format.size)
            if (parts.size < format.size) continue
            val start = parseAssTime(parts[format.indexOf("start").takeIf { it >= 0 } ?: 1]) ?: continue
            val end = parseAssTime(parts[format.indexOf("end").takeIf { it >= 0 } ?: 2]) ?: continue
            val textIndex = format.indexOf("text").takeIf { it >= 0 } ?: parts.lastIndex
            cues += Cue(start, end, assTextToMarkup(parts[textIndex]))
        }
        return cues
    }

    private val DEFAULT_ASS_FORMAT = listOf(
        "layer", "start", "end", "style", "name", "marginl", "marginr", "marginv", "effect", "text"
    )

    /** "0:01:02.50" -> 62500 ms (centiseconds). */
    fun parseAssTime(value: String): Long? {
        val m = Regex("""^\s*(\d+):(\d{1,2}):(\d{1,2})[.:](\d{1,3})\s*$""").find(value) ?: return null
        val (h, mm, s, frac) = m.destructured
        return toMs(h, mm, s, frac)
    }

    /**
     * ASS override blocks -> our markup: {\i1}..{\i0} italic, {\b1} bold,
     * {\u1} underline; every other override ({\pos}, {\c&H..}) is dropped.
     * \N and \n are line breaks, \h is a hard space.
     */
    fun assTextToMarkup(text: String): String {
        val out = StringBuilder()
        var italic = false
        var bold = false
        var underline = false
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (c == '{') {
                val close = text.indexOf('}', i)
                if (close < 0) break
                val block = text.substring(i + 1, close)
                for (tag in block.split('\\').filter { it.isNotEmpty() }) {
                    when {
                        tag == "i1" && !italic -> { out.append("<i>"); italic = true }
                        tag == "i0" && italic -> { out.append("</i>"); italic = false }
                        tag == "b1" && !bold -> { out.append("<b>"); bold = true }
                        tag == "b0" && bold -> { out.append("</b>"); bold = false }
                        tag == "u1" && !underline -> { out.append("<u>"); underline = true }
                        tag == "u0" && underline -> { out.append("</u>"); underline = false }
                    }
                }
                i = close + 1
                continue
            }
            if (c == '\\' && i + 1 < text.length) {
                when (text[i + 1]) {
                    'N', 'n' -> { out.append('\n'); i += 2; continue }
                    'h' -> { out.append(' '); i += 2; continue }
                }
            }
            out.append(c)
            i++
        }
        if (underline) out.append("</u>")
        if (bold) out.append("</b>")
        if (italic) out.append("</i>")
        return out.toString().trim()
    }

    // ---- MicroDVD: {start}{end}text, frames ----

    private val MICRODVD_LINE = Regex("""^\{(\d+)\}\{(\d*)\}(.*)$""", RegexOption.MULTILINE)

    fun parseMicroDvd(text: String, defaultFps: Double = DEFAULT_MICRODVD_FPS): List<Cue> {
        var fps = defaultFps
        val cues = mutableListOf<Cue>()
        for (line in text.split('\n')) {
            val m = MICRODVD_LINE.find(line.trim()) ?: continue
            val startFrame = m.groupValues[1].toLongOrNull() ?: continue
            val endFrame = m.groupValues[2].toLongOrNull()
            val body = m.groupValues[3]
            // "{1}{1}23.976" declares the frame rate
            if (startFrame <= 1 && (endFrame ?: 0L) <= 1) {
                val declared = body.trim().toDoubleOrNull()
                if (declared != null && declared in 1.0..240.0) {
                    fps = declared
                    continue
                }
            }
            val start = (startFrame * 1000.0 / fps).toLong()
            // An empty end frame means "until the next line"; give it 3 s
            val end = endFrame?.let { (it * 1000.0 / fps).toLong() } ?: (start + 3_000L)
            cues += Cue(start, end, microDvdToMarkup(body))
        }
        return cues
    }

    /** '|' is a line break; {y:i} / {Y:i} is italic; "/" starts an italic line (spec 4). */
    fun microDvdToMarkup(body: String): String {
        var italicAll = false
        var text = body
        val styleTag = Regex("""\{[yY]:([ibus,]+)\}""")
        styleTag.find(text)?.let { tag ->
            if ('i' in tag.groupValues[1]) italicAll = true
        }
        text = text.replace(Regex("""\{[^}]*\}"""), "")
        val lines = text.split('|').map { line ->
            val trimmed = line.trim()
            if (trimmed.startsWith("/")) "<i>${trimmed.removePrefix("/").trim()}</i>" else trimmed
        }
        val joined = lines.joinToString("\n")
        return if (italicAll) "<i>$joined</i>" else joined
    }

    // ---- MPL2: [start][end]text, deciseconds ----

    private val MPL2_LINE = Regex("""^\[(\d+)\]\[(\d*)\](.*)$""", RegexOption.MULTILINE)

    fun parseMpl2(text: String): List<Cue> = text.split('\n').mapNotNull { line ->
        val m = MPL2_LINE.find(line.trim()) ?: return@mapNotNull null
        val start = (m.groupValues[1].toLongOrNull() ?: return@mapNotNull null) * 100L
        val end = m.groupValues[2].toLongOrNull()?.times(100L) ?: (start + 3_000L)
        Cue(start, end, microDvdToMarkup(m.groupValues[3]))
    }

    // ---- TMPlayer: hh:mm:ss:text, ends at the next line ----

    private val TMPLAYER_LINE = Regex("""^(\d{1,2}):(\d{2}):(\d{2})[:=](.*)$""", RegexOption.MULTILINE)

    fun parseTmPlayer(text: String): List<Cue> {
        val starts = text.split('\n').mapNotNull { line ->
            val m = TMPLAYER_LINE.find(line.trim()) ?: return@mapNotNull null
            val (h, mm, s, body) = m.destructured
            val start = (h.toLong() * 3600 + mm.toLong() * 60 + s.toLong()) * 1000L
            start to microDvdToMarkup(body)
        }
        return starts.mapIndexed { i, (start, body) ->
            val nextStart = starts.getOrNull(i + 1)?.first
            val end = when {
                nextStart != null && nextStart > start -> minOf(nextStart, start + 5_000L)
                else -> start + 3_000L
            }
            Cue(start, end, body)
        }
    }

    // ---- shared helpers ----

    private fun normaliseNewlines(s: String): String = s.replace("\r\n", "\n").replace('\r', '\n')

    /** hours, minutes, seconds and a 1-3 digit fraction -> ms ("5" = 500, "05" = 50). */
    private fun toMs(h: String, m: String, s: String, frac: String): Long {
        val fracMs = when (frac.length) {
            1 -> frac.toLong() * 100
            2 -> frac.toLong() * 10
            else -> frac.take(3).toLong()
        }
        return ((h.toLong() * 60 + m.toLong()) * 60 + s.toLong()) * 1000 + fracMs
    }

    /**
     * Keeps <i>/<b>/<u> (normalised to lower case, attributes dropped) and
     * strips every other tag such as <font color=...>. Decodes the common
     * HTML entities found in SRT and VTT files.
     */
    fun cleanHtmlLike(text: String): String {
        val kept = Regex("""<\s*(/?)\s*([a-zA-Z]+)[^>]*>""").replace(text) { m ->
            val closing = m.groupValues[1]
            when (val name = m.groupValues[2].lowercase()) {
                "i", "b", "u" -> "<$closing$name>"
                else -> ""
            }
        }
        // Lines starting with "/" are italic (spec 4: optional italic styling for lines starting with "/")
        val withSlashItalic = kept.split('\n').joinToString("\n") { line ->
            if (line.startsWith("/") && !line.startsWith("//")) "<i>${line.removePrefix("/").trimStart()}</i>" else line
        }
        return withSlashItalic
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
            .replace("&nbsp;", " ")
            .trim()
    }
}

/**
 * Turns the normalised markup into styled spans. Pure, so the renderer is a
 * thin loop over [Span]s and the rules are tested on the JVM.
 */
object SubtitleMarkup {

    data class Span(
        val text: String,
        val italic: Boolean = false,
        val bold: Boolean = false,
        val underline: Boolean = false
    )

    fun spans(markup: String): List<Span> {
        val out = mutableListOf<Span>()
        var italic = 0
        var bold = 0
        var underline = 0
        val buffer = StringBuilder()
        fun flush() {
            if (buffer.isNotEmpty()) {
                out += Span(buffer.toString(), italic > 0, bold > 0, underline > 0)
                buffer.clear()
            }
        }
        var i = 0
        val tag = Regex("""^<(/?)([ibu])>""")
        while (i < markup.length) {
            if (markup[i] == '<') {
                val m = tag.find(markup.substring(i, minOf(markup.length, i + 4)))
                if (m != null) {
                    flush()
                    val delta = if (m.groupValues[1].isEmpty()) 1 else -1
                    when (m.groupValues[2]) {
                        "i" -> italic = (italic + delta).coerceAtLeast(0)
                        "b" -> bold = (bold + delta).coerceAtLeast(0)
                        "u" -> underline = (underline + delta).coerceAtLeast(0)
                    }
                    i += m.value.length
                    continue
                }
            }
            buffer.append(markup[i])
            i++
        }
        flush()
        return out
    }

    /** Plain text with every markup tag removed (accessibility, tests, search). */
    fun plain(markup: String): String = spans(markup).joinToString("") { it.text }
}
