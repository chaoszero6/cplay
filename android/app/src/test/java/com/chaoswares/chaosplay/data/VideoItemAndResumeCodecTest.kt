package com.chaoswares.chaosplay.data

import com.chaoswares.chaosplay.player.WatchEntry
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * VideoItem formatting and the ResumeCodec serialisation used for
 * persisted resume positions.
 */
class VideoItemAndResumeCodecTest {

    // ---- size formatting ----

    @Test
    fun `size formatting covers every unit`() {
        assertThat(VideoItem.formatSize(0L)).isEqualTo("0 B")
        assertThat(VideoItem.formatSize(512L)).isEqualTo("512 B")
        assertThat(VideoItem.formatSize(1024L)).isEqualTo("1.0 KB")
        assertThat(VideoItem.formatSize(1024L * 1024L)).isEqualTo("1.0 MB")
        assertThat(VideoItem.formatSize(1024L * 1024L * 1024L)).isEqualTo("1.0 GB")
        assertThat(VideoItem.formatSize(1024L * 1024L * 1024L * 1024L)).isEqualTo("1.0 TB")
    }

    @Test
    fun `size formatting handles negative and fractional values`() {
        assertThat(VideoItem.formatSize(-1L)).isEqualTo("0 B")
        assertThat(VideoItem.formatSize(1536L)).isEqualTo("1.5 KB")
    }

    @Test
    fun `size formatting stops at terabytes`() {
        val petabyte = 1024L * 1024L * 1024L * 1024L * 1024L
        assertThat(VideoItem.formatSize(petabyte)).isEqualTo("1024.0 TB")
    }

    @Test
    fun `resolution label is empty when dimensions are unknown`() {
        assertThat(item().resolutionLabel).isEmpty()
        assertThat(item(width = 1920, height = 1080).resolutionLabel).isEqualTo("1920x1080")
    }

    @Test
    fun `size label uses the formatter`() {
        assertThat(item(sizeBytes = 1024L * 1024L * 2L).sizeLabel).isEqualTo("2.0 MB")
    }

    // ---- resume codec ----

    @Test
    fun `encoding an empty list yields an empty string`() {
        assertThat(ResumeCodec.encode(emptyList())).isEmpty()
    }

    @Test
    fun `round trip preserves every field`() {
        val entries = listOf(
            WatchEntry("content://media/1", 60_000L, 600_000L, 1_700_000_000L, 2),
            WatchEntry("content://media/2", 5L, 100L, 1_700_000_100L, 0)
        )
        val decoded = ResumeCodec.decode(ResumeCodec.encode(entries))
        assertThat(decoded).isEqualTo(entries)
    }

    @Test
    fun `decoding an empty or blank string yields an empty list`() {
        assertThat(ResumeCodec.decode("")).isEmpty()
        assertThat(ResumeCodec.decode("   ")).isEmpty()
    }

    @Test
    fun `malformed lines are skipped rather than crashing`() {
        val raw = "content://media/1|60000|600000|1700000000|1\nnot-a-valid-line\n"
        val decoded = ResumeCodec.decode(raw)
        assertThat(decoded).hasSize(1)
        assertThat(decoded.first().uri).isEqualTo("content://media/1")
    }

    @Test
    fun `a line with too few fields is skipped`() {
        assertThat(ResumeCodec.decode("a|1|2")).isEmpty()
    }

    @Test
    fun `a line with non numeric fields is skipped`() {
        assertThat(ResumeCodec.decode("a|notanumber|600000|1700000000|1")).isEmpty()
        assertThat(ResumeCodec.decode("a|60000|notanumber|1700000000|1")).isEmpty()
    }

    @Test
    fun `an unparseable play count defaults to zero and keeps the entry`() {
        // The play count is the last field; a corrupt value must not lose
        // the resume position, so it degrades to zero instead.
        val decoded = ResumeCodec.decode("a|60000|600000|1700000000|x")
        assertThat(decoded).hasSize(1)
        assertThat(decoded.first().playCount).isEqualTo(0)
        assertThat(decoded.first().positionMs).isEqualTo(60_000L)
    }

    @Test
    fun `a negative play count is preserved as parsed`() {
        val decoded = ResumeCodec.decode("a|60000|600000|1700000000|-3")
        assertThat(decoded).hasSize(1)
        assertThat(decoded.first().playCount).isEqualTo(-3)
    }

    @Test
    fun `uris containing separators survive the round trip`() {
        // Content URIs can contain slashes and colons, never pipes
        val entry = WatchEntry(
            "content://media/external/video/media/42:with:colons",
            1_000L, 2_000L, 3L, 0
        )
        val decoded = ResumeCodec.decode(ResumeCodec.encode(listOf(entry)))
        assertThat(decoded).hasSize(1)
        assertThat(decoded.first().uri).isEqualTo(entry.uri)
    }

    // ---- upsert ----

    @Test
    fun `upsert adds a new entry`() {
        val result = ResumeCodec.upsert(emptyList(), WatchEntry("a", 1L, 10L, 1L))
        assertThat(result).hasSize(1)
    }

    @Test
    fun `upsert replaces an existing entry rather than duplicating`() {
        val existing = listOf(WatchEntry("a", 1L, 10L, 1L, 0))
        val result = ResumeCodec.upsert(existing, WatchEntry("a", 5L, 10L, 9L, 1))
        assertThat(result).hasSize(1)
        assertThat(result.first().positionMs).isEqualTo(5L)
        assertThat(result.first().playCount).isEqualTo(1)
    }

    @Test
    fun `upsert keeps the newest entries first`() {
        val existing = listOf(
            WatchEntry("a", 1L, 10L, 1L),
            WatchEntry("b", 1L, 10L, 5L)
        )
        val result = ResumeCodec.upsert(existing, WatchEntry("c", 1L, 10L, 9L))
        assertThat(result.map { it.uri }).containsExactly("c", "b", "a").inOrder()
    }

    @Test
    fun `upsert caps the table size`() {
        val existing = (1..10).map { WatchEntry("u$it", 1L, 10L, it.toLong()) }
        val result = ResumeCodec.upsert(existing, WatchEntry("new", 1L, 10L, 999L), maxEntries = 5)
        assertThat(result).hasSize(5)
        assertThat(result.first().uri).isEqualTo("new")
    }

    @Test
    fun `upsert into an empty table with a zero cap stays empty`() {
        val result = ResumeCodec.upsert(emptyList(), WatchEntry("a", 1L, 10L, 1L), maxEntries = 0)
        assertThat(result).isEmpty()
    }

    private fun item(
        id: Long = 1L,
        width: Int = 0,
        height: Int = 0,
        sizeBytes: Long = 0L
    ) = VideoItem(
        id = id,
        uri = "content://media/$id",
        title = "video.mkv",
        path = "/storage/Movies/video.mkv",
        folder = "/storage/Movies",
        durationMs = 1_000L,
        sizeBytes = sizeBytes,
        width = width,
        height = height
    )
}