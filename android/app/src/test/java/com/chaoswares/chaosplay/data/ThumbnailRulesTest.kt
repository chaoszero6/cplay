package com.chaoswares.chaosplay.data

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Thumbnail resolution rules: sidecar discovery, cache stamping and the
 * process-wide memo that keeps a scrolling grid from re-decoding.
 */
class ThumbnailRulesTest {

    @Test
    fun `sidecar candidates cover the common image formats`() {
        val candidates = ThumbnailRules.sidecarCandidates("/storage/emulated/0/Movies/Trip/beach.mp4")
        assertThat(candidates.first()).isEqualTo("/storage/emulated/0/Movies/Trip/beach.jpg")
        assertThat(candidates).contains("/storage/emulated/0/Movies/Trip/beach.png")
        assertThat(candidates).contains("/storage/emulated/0/Movies/Trip/beach.webp")
        assertThat(candidates).contains("/storage/emulated/0/Movies/Trip/beach.jpeg")
    }

    @Test
    fun `sidecar keeps dots in the file name`() {
        val candidates = ThumbnailRules.sidecarCandidates("/sdcard/Show.S01E02.1080p.mkv")
        assertThat(candidates.first()).isEqualTo("/sdcard/Show.S01E02.1080p.jpg")
    }

    @Test
    fun `no directory means no sidecar candidates`() {
        assertThat(ThumbnailRules.sidecarCandidates("beach.mp4")).isEmpty()
        assertThat(ThumbnailRules.sidecarCandidates("")).isEmpty()
        assertThat(ThumbnailRules.sidecarCandidates("   ")).isEmpty()
    }

    @Test
    fun `cache file name carries the video mtime so edits invalidate it`() {
        assertThat(ThumbnailRules.cacheFileName(42L, 1_700_000_000L))
            .isEqualTo("42_1700000000.jpg")
    }

    @Test
    fun `thumbnail is regenerated when the video is newer`() {
        assertThat(ThumbnailRules.needsRegeneration(100L, 200L)).isTrue()
        assertThat(ThumbnailRules.needsRegeneration(200L, 200L)).isFalse()
        assertThat(ThumbnailRules.needsRegeneration(300L, 200L)).isFalse()
    }

    @Test
    fun `memo is keyed by id and mtime`() {
        ThumbnailRules.clearMemo()
        ThumbnailRules.memoise(7L, 100L, "/cache/7_100.jpg")
        assertThat(ThumbnailRules.memoised(7L, 100L)).isEqualTo("/cache/7_100.jpg")
        // Same id, newer file: the old poster must not be served.
        assertThat(ThumbnailRules.memoised(7L, 200L)).isNull()
        ThumbnailRules.clearMemo()
        assertThat(ThumbnailRules.memoised(7L, 100L)).isNull()
    }
}
