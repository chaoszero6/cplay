package com.chaoswares.chaosplay.subtitles

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class SubtitleTimelineTest {

    private val cues = listOf(
        Cue(1_000, 3_000, "one"),
        Cue(2_000, 10_000, "long"),
        Cue(4_000, 5_000, "two"),
        Cue(6_000, 7_000, "three")
    )

    @Test fun `active cues include overlaps in start order`() {
        assertThat(SubtitleTimeline.activeCues(cues, 2_500).map { it.text }).containsExactly("one", "long").inOrder()
        assertThat(SubtitleTimeline.textAt(cues, 4_500)).isEqualTo("long\ntwo")
        assertThat(SubtitleTimeline.activeCues(cues, 500)).isEmpty()
        assertThat(SubtitleTimeline.activeCues(cues, 10_000)).isEmpty()
        assertThat(SubtitleTimeline.activeCues(emptyList(), 1)).isEmpty()
    }

    @Test fun `end is exclusive and start inclusive`() {
        assertThat(SubtitleTimeline.textAt(cues, 1_000)).isEqualTo("one")
        assertThat(SubtitleTimeline.textAt(cues, 3_000)).isEqualTo("long")
    }

    @Test fun `positive delay shows text later, negative earlier`() {
        assertThat(SubtitleTimeline.textAt(cues, 1_200, delayMs = 500)).isEqualTo("")
        assertThat(SubtitleTimeline.textAt(cues, 1_600, delayMs = 500)).isEqualTo("one")
        assertThat(SubtitleTimeline.textAt(cues, 5_500, delayMs = -2_000)).isEqualTo("long")
        assertThat(SubtitleTimeline.textAt(cues, 4_500, delayMs = -2_000)).isEqualTo("long\nthree")
    }

    @Test fun `speed rescales cue times`() {
        // A 25->23.976 style factor: cue at 4 s now shows around 4.17 s
        val factor = 25.0 / 23.976
        assertThat(SubtitleTimeline.textAt(cues, 4_100, speed = factor)).isEqualTo("long")
        assertThat(SubtitleTimeline.textAt(cues, 4_200, speed = factor)).isEqualTo("long\ntwo")
        // Nonsense speed falls back to 1.0
        assertThat(SubtitleTimeline.clampSpeed(Double.NaN)).isEqualTo(1.0)
        assertThat(SubtitleTimeline.clampSpeed(0.0)).isEqualTo(1.0)
        assertThat(SubtitleTimeline.clampSpeed(9.0)).isEqualTo(2.0)
    }

    @Test fun `next and previous line starts for subtitle scrolling`() {
        assertThat(SubtitleTimeline.nextLineStart(cues, 1_000)).isEqualTo(2_000)
        assertThat(SubtitleTimeline.nextLineStart(cues, 6_500)).isNull()
        assertThat(SubtitleTimeline.previousLineStart(cues, 6_500)).isEqualTo(4_000)
        assertThat(SubtitleTimeline.previousLineStart(cues, 1_500)).isNull()
        assertThat(SubtitleTimeline.nextLineStart(cues, 0, delayMs = -1_500)).isEqualTo(500)
    }

    @Test fun `delay formatting and clamping`() {
        assertThat(SubtitleTimeline.formatDelay(0)).isEqualTo("0.0 s")
        assertThat(SubtitleTimeline.formatDelay(1_500)).isEqualTo("+1.5 s")
        assertThat(SubtitleTimeline.formatDelay(-200)).isEqualTo("-0.2 s")
        assertThat(SubtitleTimeline.clampDelay(Long.MAX_VALUE)).isEqualTo(SubtitleTimeline.MAX_DELAY_MS)
    }

    @Test fun `many cues stay correct with binary search`() {
        val many = (0 until 5_000).map { Cue(it * 1_000L, it * 1_000L + 800L, "c$it") }
        assertThat(SubtitleTimeline.textAt(many, 4_321_500)).isEqualTo("c4321")
        assertThat(SubtitleTimeline.textAt(many, 4_321_900)).isEqualTo("")
    }

    @Test fun `style codec round trips and clamps`() {
        val style = SubtitleStyle(sizeSp = 28, colorArgb = 0xFFFFE066, background = true, outline = false, bold = true, bottomMarginPercent = 20)
        assertThat(SubtitleStyle.decode(SubtitleStyle.encode(style))).isEqualTo(style)
        assertThat(SubtitleStyle.decode("")).isEqualTo(SubtitleStyle())
        val clamped = SubtitleStyle.decode("size=400;margin=-4;color=zz")
        assertThat(clamped.sizeSp).isEqualTo(SubtitleStyle.MAX_SIZE)
        assertThat(clamped.bottomMarginPercent).isEqualTo(0)
        assertThat(clamped.colorArgb).isEqualTo(SubtitleStyle().colorArgb)
    }

    @Test fun `per video subtitle choices`() {
        var map = SubtitleChoices.remember(emptyMap(), "v1", SubtitleChoices.embeddedKey(1, "ta"))
        map = SubtitleChoices.remember(map, "v2", SubtitleChoices.externalKey("content://s/1"))
        map = SubtitleChoices.remember(map, "v1", SubtitleChoices.OFF)
        val back = SubtitleChoices.decode(SubtitleChoices.encode(map))
        assertThat(back).containsExactly("v2", "ext:content://s/1", "v1", "off").inOrder()
        assertThat(SubtitleChoices.externalLocation("ext:content://s/1")).isEqualTo("content://s/1")
        assertThat(SubtitleChoices.externalLocation("off")).isNull()
        assertThat(SubtitleChoices.embeddedLanguage("emb:3:pt-BR")).isEqualTo("pt-BR")
        assertThat(SubtitleChoices.embeddedIndex("emb:3:pt-BR")).isEqualTo(3)
        assertThat(SubtitleChoices.embeddedIndex("ext:x")).isNull()
    }

    @Test fun `subtitle choices are bounded`() {
        var map = emptyMap<String, String>()
        for (i in 0..SubtitleChoices.MAX_ENTRIES + 5) map = SubtitleChoices.remember(map, "v$i", "off")
        assertThat(map).hasSize(SubtitleChoices.MAX_ENTRIES)
        assertThat(map.containsKey("v0")).isFalse()
        assertThat(map.containsKey("v${SubtitleChoices.MAX_ENTRIES + 5}")).isTrue()
    }
}
