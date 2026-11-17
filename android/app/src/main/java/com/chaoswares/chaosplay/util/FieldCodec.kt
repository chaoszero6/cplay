package com.chaoswares.chaosplay.util

/**
 * Tiny line/tab record codec used by every persisted list in the app
 * (playlists, bookmarks, saved streams, per-video subtitle choices).
 *
 * Records are separated by '\n' and fields by '\t'. Backslash, tab and
 * newline inside a field are escaped, so titles and URLs survive a round
 * trip byte for byte. Pure Kotlin: no org.json (which is stubbed out in
 * JVM unit tests) and no Android.
 */
object FieldCodec {

    private const val FIELD_SEP = '\t'
    private const val RECORD_SEP = '\n'

    fun escape(value: String): String {
        if (value.none { it == '\\' || it == FIELD_SEP || it == RECORD_SEP || it == '\r' }) return value
        val out = StringBuilder(value.length + 8)
        for (c in value) {
            when (c) {
                '\\' -> out.append("\\\\")
                FIELD_SEP -> out.append("\\t")
                RECORD_SEP -> out.append("\\n")
                '\r' -> out.append("\\r")
                else -> out.append(c)
            }
        }
        return out.toString()
    }

    fun unescape(value: String): String {
        if ('\\' !in value) return value
        val out = StringBuilder(value.length)
        var i = 0
        while (i < value.length) {
            val c = value[i]
            if (c == '\\' && i + 1 < value.length) {
                when (value[i + 1]) {
                    '\\' -> out.append('\\')
                    't' -> out.append(FIELD_SEP)
                    'n' -> out.append(RECORD_SEP)
                    'r' -> out.append('\r')
                    else -> {
                        // Unknown escape: keep it literally rather than lose data
                        out.append(c).append(value[i + 1])
                    }
                }
                i += 2
            } else {
                out.append(c)
                i++
            }
        }
        return out.toString()
    }

    fun encodeRecord(fields: List<String>): String =
        fields.joinToString(FIELD_SEP.toString()) { escape(it) }

    /** Splits one record line into its unescaped fields. */
    fun decodeRecord(line: String): List<String> =
        line.split(FIELD_SEP).map { unescape(it) }

    fun encode(records: List<List<String>>): String =
        records.joinToString(RECORD_SEP.toString()) { encodeRecord(it) }

    /** Decodes all non-blank records. */
    fun decode(raw: String): List<List<String>> {
        if (raw.isBlank()) return emptyList()
        return raw.split(RECORD_SEP)
            .filter { it.isNotBlank() }
            .map { decodeRecord(it) }
    }
}
