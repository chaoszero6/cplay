package com.chaoswares.chaosplay.subtitles

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class SubtitleParserTest {

    @Test fun `srt basic with crlf bom and html`() {
        val srt = "﻿1\r\n00:00:01,000 --> 00:00:02,500\r\n<font color=\"red\">Hello</font> <I>world</I>\r\n\r\n" +
            "2\r\n00:00:03,000 --> 00:00:04,000\r\nLine one\r\nLine two\r\n"
        val cues = SubtitleParser.parse(srt, SubtitleFormat.SRT)
        assertThat(cues).containsExactly(
            Cue(1_000, 2_500, "Hello <i>world</i>"),
            Cue(3_000, 4_000, "Line one\nLine two")
        ).inOrder()
    }

    @Test fun `srt tolerates dots, short fractions, missing blank lines and junk`() {
        val srt = "1\n00:00:01.5 --> 00:00:02.25\nA\n2\n00:00:03,000 --> 00:00:04,000\nB\n\ngarbage\n\n" +
            "00:00:05,000-->00:00:06,000\nC &amp; D\n"
        val cues = SubtitleParser.parseSrt(srt)
        assertThat(cues.map { it.text }).containsExactly("A", "B", "C & D").inOrder()
        assertThat(cues[0].startMs).isEqualTo(1_500L)
        assertThat(cues[0].endMs).isEqualTo(2_250L)
    }

    @Test fun `parse drops empty and inverted cues and sorts`() {
        val srt = "1\n00:00:05,000 --> 00:00:06,000\nlate\n\n2\n00:00:01,000 --> 00:00:00,500\nbad\n\n" +
            "3\n00:00:02,000 --> 00:00:03,000\n\n\n4\n00:00:01,000 --> 00:00:02,000\nearly\n"
        val cues = SubtitleParser.parse(srt, SubtitleFormat.SRT)
        assertThat(cues.map { it.text }).containsExactly("early", "late").inOrder()
    }

    @Test fun `slash line becomes italic`() {
        assertThat(SubtitleParser.cleanHtmlLike("/Thinking aloud\nNormal")).isEqualTo("<i>Thinking aloud</i>\nNormal")
        assertThat(SubtitleParser.cleanHtmlLike("//not italic")).isEqualTo("//not italic")
    }

    @Test fun `vtt with header, notes, settings, voices and short timestamps`() {
        val vtt = "WEBVTT - title\n\nNOTE this is a note\n00:00:00.000 --> 00:00:09.000\n\n" +
            "intro\n00:01.000 --> 00:02.000 align:start position:10%\n<v Bob>Hi</v> <c.yellow>there</c>\n\n" +
            "01:00:00.000 --> 01:00:01.000\n<b>Bold</b> <00:00:01.500>karaoke\n"
        val cues = SubtitleParser.parse(vtt, null)
        assertThat(cues).containsExactly(
            Cue(1_000, 2_000, "Hi there"),
            Cue(3_600_000, 3_601_000, "<b>Bold</b> karaoke")
        ).inOrder()
    }

    @Test fun `ass dialogue with commas, overrides and custom format order`() {
        val ass = """
            [Script Info]
            Title: test
            [V4+ Styles]
            Format: Name, Fontname
            [Events]
            Format: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text
            Dialogue: 0,0:00:01.00,0:00:03.50,Default,,0,0,0,,{\pos(10,10)}Hello, {\i1}world{\i0}\Nnext\hline
            Comment: 0,0:00:04.00,0:00:05.00,Default,,0,0,0,,ignored
            Dialogue: 0,0:00:06.00,0:00:07.00,Default,,0,0,0,,{\b1}{\u1}unclosed
        """.trimIndent()
        val cues = SubtitleParser.parse(ass, SubtitleFormat.ASS)
        assertThat(cues).containsExactly(
            Cue(1_000, 3_500, "Hello, <i>world</i>\nnext line"),
            Cue(6_000, 7_000, "<b><u>unclosed</u></b>")
        ).inOrder()
    }

    @Test fun `ass with reordered format columns`() {
        val ass = "[Events]\nFormat: Start, End, Text\nDialogue: 0:00:02.00,0:00:04.00,Hi, there\n"
        assertThat(SubtitleParser.parseAss(ass)).containsExactly(Cue(2_000, 4_000, "Hi, there"))
    }

    @Test fun `ass time parsing`() {
        assertThat(SubtitleParser.parseAssTime("1:02:03.45")).isEqualTo(3_723_450L)
        assertThat(SubtitleParser.parseAssTime("nope")).isNull()
    }

    @Test fun `microdvd honours declared fps, line breaks and italics`() {
        val sub = "{1}{1}25.000\n{25}{50}Hello|world\n{75}{100}{y:i}All italic\n{125}{}/Slash italic|plain\n"
        val cues = SubtitleParser.parse(sub, SubtitleFormat.SUBVIEWER)
        assertThat(cues).containsExactly(
            Cue(1_000, 2_000, "Hello\nworld"),
            Cue(3_000, 4_000, "<i>All italic</i>"),
            Cue(5_000, 8_000, "<i>Slash italic</i>\nplain")
        ).inOrder()
    }

    @Test fun `microdvd defaults to 23_976 fps`() {
        val cue = SubtitleParser.parseMicroDvd("{2398}{2400}x").single()
        assertThat(cue.startMs).isEqualTo(100_016L)
    }

    @Test fun `mpl2 deciseconds and tmplayer end at next line`() {
        assertThat(SubtitleParser.parse("[10][25]Hi|there", SubtitleFormat.MPL2))
            .containsExactly(Cue(1_000, 2_500, "Hi\nthere"))
        val tmp = SubtitleParser.parse("00:00:01:First\n00:00:03:Second\n00:01:00=Third", SubtitleFormat.TMPLAYER)
        assertThat(tmp).containsExactly(
            Cue(1_000, 3_000, "First"),
            Cue(3_000, 8_000, "Second"),
            Cue(60_000, 63_000, "Third")
        ).inOrder()
    }

    @Test fun `format detection from content`() {
        assertThat(SubtitleParser.detectFormat("WEBVTT\n")).isEqualTo(SubtitleFormat.VTT)
        assertThat(SubtitleParser.detectFormat("[Script Info]\n")).isEqualTo(SubtitleFormat.ASS)
        assertThat(SubtitleParser.detectFormat("1\n00:00:01,000 --> 00:00:02,000\nx")).isEqualTo(SubtitleFormat.SRT)
        assertThat(SubtitleParser.detectFormat("{1}{2}x")).isEqualTo(SubtitleFormat.SUBVIEWER)
        assertThat(SubtitleParser.detectFormat("[1][2]x")).isEqualTo(SubtitleFormat.MPL2)
        assertThat(SubtitleParser.detectFormat("00:00:01:x")).isEqualTo(SubtitleFormat.TMPLAYER)
        assertThat(SubtitleParser.detectFormat("just text")).isNull()
    }

    @Test fun `unknown format sniffs content, unparseable gives nothing`() {
        assertThat(SubtitleParser.parse("1\n00:00:01,000 --> 00:00:02,000\nx", SubtitleFormat.SAMI)).hasSize(1)
        assertThat(SubtitleParser.parse("<SAMI></SAMI>", SubtitleFormat.SAMI)).isEmpty()
        assertThat(SubtitleParser.parse("", null)).isEmpty()
    }

    @Test fun `format for file name`() {
        assertThat(SubtitleParser.formatForFileName("Movie.HI.SRT")).isEqualTo(SubtitleFormat.SRT)
        assertThat(SubtitleParser.formatForFileName("movie.txt")).isNull()
    }

    @Test fun `markup spans nest and plain strips`() {
        val spans = SubtitleMarkup.spans("a <i>b <b>c</b></i> d")
        assertThat(spans).containsExactly(
            SubtitleMarkup.Span("a "),
            SubtitleMarkup.Span("b ", italic = true),
            SubtitleMarkup.Span("c", italic = true, bold = true),
            SubtitleMarkup.Span(" d")
        ).inOrder()
        assertThat(SubtitleMarkup.plain("<u>x</u> < y")).isEqualTo("x < y")
        assertThat(SubtitleMarkup.spans("</i>stray")).containsExactly(SubtitleMarkup.Span("stray"))
    }

    @Test fun `encoding detection`() {
        assertThat(SubtitleEncodingDetector.detect("Hello".toByteArray())).isEqualTo(SubtitleEncoding.UTF_8)
        assertThat(SubtitleEncodingDetector.detect("தமிழ்".toByteArray(Charsets.UTF_8))).isEqualTo(SubtitleEncoding.UTF_8)
        val bom8 = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + "x".toByteArray()
        assertThat(SubtitleEncodingDetector.detect(bom8)).isEqualTo(SubtitleEncoding.UTF_8)
        assertThat(SubtitleEncodingDetector.detect("Café crème".toByteArray(Charsets.ISO_8859_1)))
            .isEqualTo(SubtitleEncoding.WINDOWS_1252)
        val utf16 = "1\n00:00:01,000 --> x".toByteArray(Charsets.UTF_16LE)
        assertThat(SubtitleEncodingDetector.detect(utf16)).isEqualTo(SubtitleEncoding.UTF_16)
        val utf16bom = byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + "hi".toByteArray(Charsets.UTF_16LE)
        assertThat(SubtitleEncodingDetector.detect(utf16bom)).isEqualTo(SubtitleEncoding.UTF_16)
    }

    @Test fun `decode with auto and manual encodings strips bom`() {
        val bytes = "Café".toByteArray(charset("windows-1252"))
        assertThat(SubtitleEncodingDetector.decode(bytes)).isEqualTo("Café")
        assertThat(SubtitleEncodingDetector.decode(bytes, SubtitleEncoding.ISO_8859_1)).isEqualTo("Café")
        val be = byteArrayOf(0xFE.toByte(), 0xFF.toByte()) + "Hi".toByteArray(Charsets.UTF_16BE)
        assertThat(SubtitleEncodingDetector.decode(be)).isEqualTo("Hi")
        val le = "Tamil தமிழ் line".toByteArray(Charsets.UTF_16LE)
        assertThat(SubtitleEncodingDetector.decode(le, SubtitleEncoding.UTF_16)).isEqualTo("Tamil தமிழ் line")
        val bom8 = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + "ok".toByteArray()
        assertThat(SubtitleEncodingDetector.decode(bom8)).isEqualTo("ok")
    }

    @Test fun `short buffers are not mistaken for utf16`() {
        assertThat(SubtitleEncodingDetector.looksLikeUtf16(byteArrayOf(0, 1, 0))).isFalse()
    }
}
