package com.chaoswares.chaosplay.subtitles

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

/**
 * Subtitle text encoding detection, spec section 4:
 * "Encoding detection and manual encoding choice (UTF-8, UTF-16,
 * Windows-1252, Indian language encodings)".
 *
 * Order: byte order mark, then strict UTF-8 validation (which also covers
 * Unicode Tamil/Hindi files - by far the most common case), then a UTF-16
 * zero-byte heuristic for BOM-less files, and finally Windows-1252, which
 * accepts every byte so legacy Western files always decode to something.
 */
object SubtitleEncodingDetector {

    fun detect(bytes: ByteArray): SubtitleEncoding {
        if (bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()) {
            return SubtitleEncoding.UTF_8
        }
        if (bytes.size >= 2) {
            val b0 = bytes[0].toInt() and 0xFF
            val b1 = bytes[1].toInt() and 0xFF
            if ((b0 == 0xFF && b1 == 0xFE) || (b0 == 0xFE && b1 == 0xFF)) return SubtitleEncoding.UTF_16
        }
        if (looksLikeUtf16(bytes)) return SubtitleEncoding.UTF_16
        if (isValidUtf8(bytes)) return SubtitleEncoding.UTF_8
        return SubtitleEncoding.WINDOWS_1252
    }

    /** Decodes with [encoding]; AUTO runs [detect] first. Strips any BOM. */
    fun decode(bytes: ByteArray, encoding: SubtitleEncoding = SubtitleEncoding.AUTO): String {
        val chosen = if (encoding == SubtitleEncoding.AUTO) detect(bytes) else encoding
        val charset = charsetFor(chosen, bytes)
        return String(bytes, charset).removePrefix("﻿")
    }

    fun charsetFor(encoding: SubtitleEncoding, bytes: ByteArray = ByteArray(0)): Charset = when (encoding) {
        SubtitleEncoding.AUTO, SubtitleEncoding.UTF_8 -> Charsets.UTF_8
        SubtitleEncoding.UTF_16 -> utf16Variant(bytes)
        SubtitleEncoding.WINDOWS_1252 -> Charset.forName("windows-1252")
        SubtitleEncoding.ISO_8859_1 -> Charsets.ISO_8859_1
    }

    fun isValidUtf8(bytes: ByteArray): Boolean {
        val decoder = Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        return try {
            decoder.decode(ByteBuffer.wrap(bytes))
            true
        } catch (e: CharacterCodingException) {
            false
        }
    }

    /**
     * BOM-less UTF-16: ASCII-heavy text has a zero in every other byte.
     * More than 40% zeros on one parity (and almost none on the other) is
     * a strong signal that 8-bit encodings never produce.
     */
    fun looksLikeUtf16(bytes: ByteArray): Boolean {
        val sample = bytes.size.coerceAtMost(4096)
        if (sample < 8) return false
        var evenZeros = 0
        var oddZeros = 0
        for (i in 0 until sample) {
            if (bytes[i] == 0.toByte()) {
                if (i % 2 == 0) evenZeros++ else oddZeros++
            }
        }
        val half = sample / 2.0
        return (evenZeros > half * 0.4 && oddZeros < half * 0.05) ||
            (oddZeros > half * 0.4 && evenZeros < half * 0.05)
    }

    private fun utf16Variant(bytes: ByteArray): Charset {
        if (bytes.size >= 2) {
            val b0 = bytes[0].toInt() and 0xFF
            val b1 = bytes[1].toInt() and 0xFF
            if (b0 == 0xFF && b1 == 0xFE) return Charsets.UTF_16LE
            if (b0 == 0xFE && b1 == 0xFF) return Charsets.UTF_16BE
        }
        // No BOM: zeros in odd positions mean little-endian ("H\0e\0")
        var evenZeros = 0
        var oddZeros = 0
        for (i in 0 until bytes.size.coerceAtMost(4096)) {
            if (bytes[i] == 0.toByte()) if (i % 2 == 0) evenZeros++ else oddZeros++
        }
        return if (oddZeros >= evenZeros) Charsets.UTF_16LE else Charsets.UTF_16BE
    }
}
