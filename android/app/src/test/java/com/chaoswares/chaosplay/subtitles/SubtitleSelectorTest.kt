package com.chaoswares.chaosplay.subtitles

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * SubtitleSelector and friends: language naming, format detection,
 * sidecar ranking, preferred-language auto-select and fallback order.
 */
class SubtitleSelectorTest {

    // ---- LanguageNames ----

    @Test
    fun `known languages resolve to readable names`() {
        assertThat(LanguageNames.display("en")).isEqualTo("English")
        assertThat(LanguageNames.display("ta")).isEqualTo("Tamil")
        assertThat(LanguageNames.display("hi")).isEqualTo("Hindi")
        assertThat(LanguageNames.display("ml")).isEqualTo("Malayalam")
        assertThat(LanguageNames.display("kn")).isEqualTo("Kannada")
        assertThat(LanguageNames.display("te")).isEqualTo("Telugu")
    }

    @Test
    fun `language lookup is case insensitive`() {
        assertThat(LanguageNames.display("EN")).isEqualTo("English")
        assertThat(LanguageNames.display("Ta")).isEqualTo("Tamil")
    }

    @Test
    fun `region and script subtags use the primary language`() {
        assertThat(LanguageNames.display("pt-BR")).isEqualTo("Portuguese")
        assertThat(LanguageNames.display("zh-Hans")).isEqualTo("Chinese")
        assertThat(LanguageNames.display("en_US")).isEqualTo("English")
    }

    @Test
    fun `unknown languages fall back to an upper case tag`() {
        assertThat(LanguageNames.display("xx")).isEqualTo("XX")
        assertThat(LanguageNames.isKnown("xx")).isFalse()
    }

    @Test
    fun `blank language is unknown`() {
        assertThat(LanguageNames.display("")).isEqualTo("Unknown")
        assertThat(LanguageNames.display("   ")).isEqualTo("Unknown")
        assertThat(LanguageNames.isKnown("")).isFalse()
    }

    // ---- SubtitleFormat ----

    @Test
    fun `every supported format resolves from its extension`() {
        assertThat(SubtitleFormat.fromExtension("srt")).isEqualTo(SubtitleFormat.SRT)
        assertThat(SubtitleFormat.fromExtension("ass")).isEqualTo(SubtitleFormat.ASS)
        assertThat(SubtitleFormat.fromExtension("ssa")).isEqualTo(SubtitleFormat.ASS)
        assertThat(SubtitleFormat.fromExtension("vtt")).isEqualTo(SubtitleFormat.VTT)
        assertThat(SubtitleFormat.fromExtension("sub")).isEqualTo(SubtitleFormat.SUBVIEWER)
        assertThat(SubtitleFormat.fromExtension("smi")).isEqualTo(SubtitleFormat.SAMI)
        assertThat(SubtitleFormat.fromExtension("pjs")).isEqualTo(SubtitleFormat.PJS)
    }

    @Test
    fun `extension lookup handles case and a leading dot`() {
        assertThat(SubtitleFormat.fromExtension(".SRT")).isEqualTo(SubtitleFormat.SRT)
        assertThat(SubtitleFormat.fromExtension("ASS")).isEqualTo(SubtitleFormat.ASS)
    }

    @Test
    fun `unsupported extensions return null`() {
        assertThat(SubtitleFormat.fromExtension("mkv")).isNull()
        assertThat(SubtitleFormat.fromExtension("txt2")).isNull()
        assertThat(SubtitleFormat.fromExtension("")).isNull()
    }

    @Test
    fun `subtitle file detection uses the extension`() {
        assertThat(SubtitleFormat.isSubtitleFile("movie.srt")).isTrue()
        assertThat(SubtitleFormat.isSubtitleFile("movie.ass")).isTrue()
        assertThat(SubtitleFormat.isSubtitleFile("movie.mkv")).isFalse()
        assertThat(SubtitleFormat.isSubtitleFile("noextension")).isFalse()
    }

    // ---- ranking ----

    @Test
    fun `exact base name match outranks a language suffix`() {
        val exact = SubtitleSelector.scoreFor("movie.mkv", "movie.srt")
        val suffixed = SubtitleSelector.scoreFor("movie.mkv", "movie.hi.srt")
        assertThat(exact).isGreaterThan(suffixed)
    }

    @Test
    fun `known language suffix adds to the score`() {
        val known = SubtitleSelector.scoreFor("movie.mkv", "movie.ta.srt")
        val unknown = SubtitleSelector.scoreFor("movie.mkv", "movie.zz.srt")
        assertThat(known).isGreaterThan(unknown)
    }

    @Test
    fun `ranking orders a mixed bag sensibly`() {
        val ranked = SubtitleSelector.rank(
            "movie.mkv",
            listOf("movie.zz.srt", "movie.ta.srt", "movie.srt", "readme.txt")
        )
        // Non-subtitle files are dropped entirely
        assertThat(ranked).doesNotContain("readme.txt")
        // Exact match first, then the known-language sidecar
        assertThat(ranked.first()).isEqualTo("movie.srt")
        assertThat(ranked[1]).isEqualTo("movie.ta.srt")
    }

    @Test
    fun `ranking prefers ASS over other formats when scores tie`() {
        val ranked = SubtitleSelector.rank(
            "movie.mkv",
            listOf("movie.srt", "movie.ass")
        )
        assertThat(ranked.first()).isEqualTo("movie.ass")
    }

    @Test
    fun `ranking an empty candidate list is empty`() {
        assertThat(SubtitleSelector.rank("movie.mkv", emptyList())).isEmpty()
    }

    // ---- language tag extraction ----

    @Test
    fun `language tag is pulled from a suffixed sidecar`() {
        assertThat(SubtitleSelector.extractLanguageTag("movie", "movie.hi"))
            .isEqualTo("hi")
    }

    @Test
    fun `no language marker yields null`() {
        assertThat(SubtitleSelector.extractLanguageTag("movie", "movie")).isNull()
        assertThat(SubtitleSelector.extractLanguageTag("movie", "")).isNull()
    }

    // ---- auto select ----

    @Test
    fun `auto select honours the preferred language order`() {
        val tracks = listOf(
            track("1", "en"),
            track("2", "ta"),
            track("3", "hi")
        )
        val chosen = SubtitleSelector.autoSelect(tracks, listOf("ta", "en"))
        assertThat(chosen?.id).isEqualTo("2")
    }

    @Test
    fun `auto select takes the first preference that exists`() {
        val tracks = listOf(track("1", "en"), track("2", "hi"))
        val chosen = SubtitleSelector.autoSelect(tracks, listOf("ta", "hi", "en"))
        assertThat(chosen?.id).isEqualTo("2")
    }

    @Test
    fun `auto select matches a regional variant of the preferred language`() {
        val tracks = listOf(track("1", "en-GB"), track("2", "ta"))
        val chosen = SubtitleSelector.autoSelect(tracks, listOf("en"))
        assertThat(chosen?.id).isEqualTo("1")
    }

    @Test
    fun `auto select falls back to the first track when nothing matches`() {
        val tracks = listOf(track("1", "fr"), track("2", "de"))
        val chosen = SubtitleSelector.autoSelect(tracks, listOf("ta", "en"))
        assertThat(chosen?.id).isEqualTo("1")
    }

    @Test
    fun `auto select with no tracks returns null so the menu shows Off`() {
        assertThat(SubtitleSelector.autoSelect(emptyList(), listOf("en"))).isNull()
    }

    @Test
    fun `auto select with no preferences falls back to the first track`() {
        val tracks = listOf(track("1", "ta"), track("2", "en"))
        assertThat(SubtitleSelector.autoSelect(tracks, emptyList())?.id).isEqualTo("1")
    }

    // ---- fallback order ----

    @Test
    fun `fallback order parses a comma separated list`() {
        assertThat(SubtitleSelector.parseFallbackOrder("ta,en,off"))
            .containsExactly("ta", "en").inOrder()
    }

    @Test
    fun `fallback order trims spaces and lowercases`() {
        assertThat(SubtitleSelector.parseFallbackOrder(" TA , EN , Off "))
            .containsExactly("ta", "en").inOrder()
    }

    @Test
    fun `fallback order of only off means no fallback languages`() {
        assertThat(SubtitleSelector.parseFallbackOrder("off")).isEmpty()
    }

    @Test
    fun `empty fallback order is empty`() {
        assertThat(SubtitleSelector.parseFallbackOrder("")).isEmpty()
        assertThat(SubtitleSelector.parseFallbackOrder("  ")).isEmpty()
        assertThat(SubtitleSelector.parseFallbackOrder(",,,")).isEmpty()
    }

    // ---- encoding cycling ----

    @Test
    fun `encoding cycles through every option and wraps`() {
        var encoding = SubtitleEncoding.AUTO
        val seen = mutableListOf(encoding)
        repeat(SubtitleEncoding.entries.size - 1) {
            encoding = encoding.next()
            seen += encoding
        }
        assertThat(seen.toSet()).hasSize(SubtitleEncoding.entries.size)
        assertThat(encoding.next()).isEqualTo(SubtitleEncoding.AUTO)
    }

    private fun track(id: String, tag: String) = SubtitleTrack(
        id = id,
        languageTag = tag,
        displayName = "$tag track",
        isEmbedded = true
    )
}