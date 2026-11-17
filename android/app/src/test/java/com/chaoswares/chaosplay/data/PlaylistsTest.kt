package com.chaoswares.chaosplay.data

import com.chaoswares.chaosplay.util.FieldCodec
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class PlaylistsTest {

    private val i1 = PlaylistItem("content://v/1", "One", 60_000)
    private val i2 = PlaylistItem("content://v/2", "Two", 30_000)
    private val i3 = PlaylistItem("https://host/three.mp4", "Three")

    private fun one(vararg items: PlaylistItem) =
        listOf(Playlist("p1", "Mine", items.toList(), 10L))

    @Test fun `create cleans the name and makes it unique`() {
        var lists = PlaylistRules.create(emptyList(), "  Road   trip ", "a", 1)
        assertThat(lists.single().name).isEqualTo("Road trip")
        lists = PlaylistRules.create(lists, "road TRIP", "b", 2)
        lists = PlaylistRules.create(lists, "Road trip", "c", 3)
        assertThat(lists.map { it.name }).containsExactly("Road trip", "road TRIP (2)", "Road trip (3)").inOrder()
    }

    @Test fun `create ignores blank names and truncates long ones`() {
        assertThat(PlaylistRules.create(emptyList(), "   ", "a", 1)).isEmpty()
        val long = PlaylistRules.create(emptyList(), "x".repeat(200), "a", 1)
        assertThat(long.single().name).hasLength(PlaylistRules.MAX_NAME_LENGTH)
    }

    @Test fun `rename keeps its own name available`() {
        val lists = listOf(Playlist("a", "Alpha"), Playlist("b", "Beta"))
        assertThat(PlaylistRules.rename(lists, "a", "alpha").first().name).isEqualTo("alpha")
        assertThat(PlaylistRules.rename(lists, "a", "Beta").first().name).isEqualTo("Beta (2)")
        assertThat(PlaylistRules.rename(lists, "a", " ")).isEqualTo(lists)
    }

    @Test fun `delete removes only that playlist`() {
        val lists = listOf(Playlist("a", "A"), Playlist("b", "B"))
        assertThat(PlaylistRules.delete(lists, "a").map { it.id }).containsExactly("b")
    }

    @Test fun `add items skips duplicates already present and within the batch`() {
        val lists = PlaylistRules.addItems(one(i1), "p1", listOf(i1, i2, i2, i3))
        assertThat(lists.single().items).containsExactly(i1, i2, i3).inOrder()
        assertThat(PlaylistRules.contains(lists.single(), i3.uri)).isTrue()
    }

    @Test fun `remove and move items`() {
        val base = one(i1, i2, i3)
        assertThat(PlaylistRules.removeItem(base, "p1", 1).single().items).containsExactly(i1, i3).inOrder()
        assertThat(PlaylistRules.removeItem(base, "p1", 9)).isEqualTo(base)
        assertThat(PlaylistRules.moveItem(base, "p1", 2, 0).single().items).containsExactly(i3, i1, i2).inOrder()
        assertThat(PlaylistRules.moveItem(base, "p1", 0, 5)).isEqualTo(base)
    }

    @Test fun `total duration ignores unknown durations`() {
        assertThat(one(i1, i2, i3).single().totalDurationMs).isEqualTo(90_000L)
    }

    @Test fun `codec round trips names with tabs and newlines`() {
        val lists = listOf(
            Playlist("a", "Tab\there", listOf(i1, i2.copy(title = "multi\nline")), 5L),
            Playlist("b", "Empty", emptyList(), 6L)
        )
        assertThat(PlaylistCodec.decode(PlaylistCodec.encode(lists))).isEqualTo(lists)
    }

    @Test fun `codec skips orphan items and corrupt headers`() {
        val raw = FieldCodec.encode(
            listOf(
                listOf("I", "orphan", "x", "0"),
                listOf("P", "", "nameless", "0"),
                listOf("I", "belongs-to-broken", "x", "0"),
                listOf("P", "ok", "Good", "1"),
                listOf("I", "", "no uri", "0"),
                listOf("I", "u", "t", "bad")
            )
        )
        val decoded = PlaylistCodec.decode(raw)
        assertThat(decoded).hasSize(1)
        assertThat(decoded.single().items.single()).isEqualTo(PlaylistItem("u", "t", 0))
    }

    @Test fun `m3u parses extended entries with attributes`() {
        val text = "\uFEFF#EXTM3U\r\n#EXTINF:123 tvg-id=\"x\",My Title\r\n/sdcard/Movies/a.mp4\r\n" +
            "#EXTGRP:group\n# comment\n\nhttp://host/live.m3u8\n#EXTINF:-1,Stream\nrtsp://cam/1\n"
        val entries = M3u.parse(text)
        assertThat(entries).containsExactly(
            M3u.Entry("/sdcard/Movies/a.mp4", "My Title", 123L),
            M3u.Entry("http://host/live.m3u8", null, null),
            M3u.Entry("rtsp://cam/1", "Stream", null)
        ).inOrder()
    }

    @Test fun `m3u write then parse round trips`() {
        val items = listOf(i1, i3.copy(title = "Has, comma"))
        val text = M3u.write(items)
        assertThat(text).startsWith("#EXTM3U\n#EXTINF:60,One\ncontent://v/1\n")
        val back = M3u.parse(text)
        assertThat(back.map { it.location }).containsExactly(i1.uri, i3.uri).inOrder()
        assertThat(back[1].title).isEqualTo("Has, comma")
        assertThat(back[1].durationSec).isNull()
    }

    @Test fun `m3u write flattens newlines in titles`() {
        assertThat(M3u.write(listOf(i1.copy(title = "a\nb")))).contains("#EXTINF:60,a b\n")
    }

    @Test fun `isUrl distinguishes remote and local`() {
        assertThat(M3u.isUrl("https://x/y.mp4")).isTrue()
        assertThat(M3u.isUrl("rtsp://cam")).isTrue()
        assertThat(M3u.isUrl("file:///sdcard/a.mp4")).isFalse()
        assertThat(M3u.isUrl("/sdcard/a.mp4")).isFalse()
        assertThat(M3u.isUrl("Movies\\a.mp4")).isFalse()
    }

    private data class Lib(val path: String, val uri: String)

    @Test fun `resolve matches by path, uri, then file name`() {
        val lib = listOf(Lib("/sdcard/Movies/Show.mkv", "content://1"), Lib("/sdcard/DCIM/clip.mp4", "content://2"))
        fun r(loc: String) = M3u.resolve(M3u.Entry(loc, null, null), lib, { it.path }, { it.uri })
        assertThat(r("/sdcard/Movies/Show.mkv")).isEqualTo(lib[0])
        assertThat(r("file:///sdcard/DCIM/clip.mp4")).isEqualTo(lib[1])
        assertThat(r("content://2")).isEqualTo(lib[1])
        assertThat(r("D:\\Videos\\show.MKV")).isEqualTo(lib[0])
        assertThat(r("/elsewhere/missing.mp4")).isNull()
        assertThat(r("/folder/")).isNull()
    }
}
